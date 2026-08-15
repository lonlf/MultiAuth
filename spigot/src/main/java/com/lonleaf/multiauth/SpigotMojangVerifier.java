package com.lonleaf.multiauth;

import com.lonleaf.multiauth.auth.AuthCrypto;
import com.lonleaf.multiauth.auth.AuthManager;
import com.lonleaf.multiauth.listener.SpigotPacketListener;
import com.lonleaf.multiauth.mojang.MojangSessionService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import javax.crypto.Cipher;

/**
 * Spigot 端 Mojang 加密握手验证器（proxy=false 模式）。
 */
public class SpigotMojangVerifier {

    private final AuthManager authManager;
    private final boolean useMojangUuid;
    private final Logger logger;
    private final SpigotPacketListener packetListener;

    /**
     * 全局共享的 RSA 密钥对：启动时（类加载）生成一次，所有握手复用。
     */
    private static final java.security.KeyPair SHARED_RSA_KEY_PAIR = generateSharedKeyPair();

    private static java.security.KeyPair generateSharedKeyPair() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    public SpigotMojangVerifier(AuthManager authManager, boolean useMojangUuid, Logger logger,
                                 SpigotPacketListener packetListener) {
        this.authManager = authManager;
        this.useMojangUuid = useMojangUuid;
        this.logger = logger;
        this.packetListener = packetListener;
    }

    // ==================== 主验证方法 ====================

    /**
     * @param channel 客户端 Netty Channel（由 PacketEvents LOGIN_START 回调直接传入）
     * @param username 玩家名
     * @return 验证结果
     */
    public MojangSessionService.HasJoinedResult verify(Channel channel, String username) {
        // PacketEvents 不可用 → 无法完成加密握手验证（身份验证缺失，直接拒绝）
        // 注意：不能回退到 API-only（仅检查用户名正版性，无法验证客户端身份，盗版可冒用正版名）。
        // API-only 降级决策已由 SpigotAuthListener.handleApiOnlyLogin 在监听器层面统一处理。
        if (packetListener == null) {
            logger.warning(Messages.get(Messages.VERIFY_NO_PACKETEVENT, username));
            return null;
        }

        logger.fine(Messages.get(Messages.VERIFY_HANDSHAKE_START, username));

        // 复用全局 RSA 密钥对，仅生成随机 verify token（每次握手独立）
        AuthCrypto crypto = new AuthCrypto(SHARED_RSA_KEY_PAIR);

        // 注册待处理握手（PacketEvents 收到 ENCRYPTION_RESPONSE 时完成 future）
        SpigotPacketListener.PendingHandshake handshake = packetListener.registerHandshake(username, channel, crypto);

        try {
            packetListener.sendEncryptionRequest(channel, crypto);
            logger.fine(Messages.get(Messages.VERIFY_ENC_REQUEST_SENT_WAITING, username));

            // 等待 EncryptionResponse（5s 超时）
            // 注意：盗版客户端收到 EncryptionRequest 后会自行断开（约 1s），显示客户端默认消息。
            // 不使用抢先踢出策略，因为：
            //   - 短超时（<800ms）会误踢正版客户端（RSA 解密 + 网络往返可能 >800ms）
            //   - 长超时（>1000ms）时盗版客户端已断开，Disconnect 包无法送达
            // 盗版客户端显示客户端默认的"无效会话"消息，不影响安全性（已被拒绝登录）。
            SpigotPacketListener.EncryptionResponseData response;
            try {
                response = handshake.future.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                // 盗版客户端收到 EncryptionRequest 后不响应，5s 内未发送 EncryptionResponse
                logger.warning(Messages.get(Messages.VERIFY_HANDSHAKE_TIMEOUT, username));
                return new MojangSessionService.HasJoinedResult(
                        MojangSessionService.HasJoinedResult.Status.NOT_PREMIUM, null);
            } catch (java.util.concurrent.ExecutionException e) {
                // 客户端发送了非法/损坏的 EncryptionResponse（解析失败），
                // 或连接在验证完成前断开（closeFuture 主动 completeExceptionally）
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof java.nio.channels.ClosedChannelException) {
                    logger.warning(Messages.get(Messages.AUTH_DENY_CLIENT_DISCONNECTED, username));
                } else {
                    logger.warning(Messages.get(Messages.VERIFY_ENC_RESPONSE_PARSE_FAILED, username,
                            cause.getMessage()));
                }
                return new MojangSessionService.HasJoinedResult(
                        MojangSessionService.HasJoinedResult.Status.NOT_PREMIUM, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warning(Messages.get(Messages.VERIFY_ENC_RESPONSE_INTERRUPTED, username));
                return new MojangSessionService.HasJoinedResult(
                        MojangSessionService.HasJoinedResult.Status.NOT_PREMIUM, null);
            }

            logger.fine(Messages.get(Messages.VERIFY_ENC_RESPONSE_RECEIVED_DEBUG, username));

            // 先解密 sharedSecret（RSA→16 字节 AES 密钥），后续启用加密需要解密后的密钥
            byte[] decryptedSharedSecret;
            try {
                decryptedSharedSecret = crypto.decryptSharedSecret(
                        response.sharedSecret(), response.verifyToken());
            } catch (Exception e) {
                logger.warning(Messages.get(Messages.VERIFY_DECRYPT_FAILED, username, e.getMessage()));
                return new MojangSessionService.HasJoinedResult(
                        MojangSessionService.HasJoinedResult.Status.NOT_PREMIUM, null);
            }

            // 获取客户端 IP 随 hasJoined 请求提交（Mojang 校验会话须来自该 IP，防止 serverId 跨 IP 重放）。
            // 仅公网 IPv4 携带 ip 参数（参考 FastLogin/CraftAPI 的处理）：
            // 1) 回环/私网/链路本地：Mojang 会话记录的是客户端公网出口 IP，本地/内网测试时与服务器看到的内网
            //    地址必然不一致，带 ip 会返回 204 导致验证失败；
            // 2) IPv6 一律不携带：Mojang 对 IPv6 地址校验存在缺陷，CraftAPI 亦跳过（含 ULA fc00::/7，
            //    其不在 Java isSiteLocalAddress 覆盖范围内）
            String clientIp = null;
            if (channel.remoteAddress() instanceof java.net.InetSocketAddress socketAddr) {
                java.net.InetAddress addr = socketAddr.getAddress();
                if (addr != null && !(addr instanceof java.net.Inet6Address)
                        && !addr.isLoopbackAddress()
                        && !addr.isSiteLocalAddress() && !addr.isLinkLocalAddress()) {
                    clientIp = addr.getHostAddress();
                }
            }

            // 计算 serverId 并调用 hasJoined 验证（复用已解密的 sharedSecret，避免重复 RSA 解密）
            MojangSessionService.HasJoinedResult result = authManager.verifyWithMojangDetailed(
                    username, crypto, decryptedSharedSecret, clientIp);

            if (result.status() == MojangSessionService.HasJoinedResult.Status.SUCCESS) {
                UUID premiumUuid = result.profile().uuid();
                // 过程细节：hasJoined 验证通过（最终结果由 [LOGIN] 聚合日志输出）
                logger.fine(Messages.get(Messages.VERIFY_HASJOINED_PASSED, username, String.valueOf(premiumUuid)));

                // 启用 AES 加密（使用解密后的 16 字节密钥）
                // 必须在发送假 LOGIN_START 包之前启用，否则服务器发送的 LoginSuccess 是明文，客户端会断开
                if (!enableEncryption(channel, crypto, decryptedSharedSecret)) {
                    logger.warning(Messages.get(Messages.VERIFY_AES_ANCHOR_MISSING, username));
                    return new MojangSessionService.HasJoinedResult(
                            MojangSessionService.HasJoinedResult.Status.NOT_PREMIUM, null);
                }

                // 设置正版 UUID（spoofedUUID）
                // 必须在发送假 LOGIN_START 包之前设置，让服务器用正版 UUID 而非离线 UUID 创建 gameProfile
                // use-mojang-uuid=false 时不设置，让服务器保持离线 UUID（正版与盗版共享存档）
                if (useMojangUuid) {
                    setSpoofedUUID(channel, premiumUuid);
                } else {
                    logger.fine(Messages.get(Messages.VERIFY_SKIP_SPOOFED_UUID, username));
                }

                // 方案 A：不修改 gameProfile 字段，而是由 SpigotAuthListener 发送假 LOGIN_START 包
                // 服务器收到假包后，检查 spoofedUUID，用正版 UUID 创建 gameProfile
                return result;
            } else {
                logger.warning(Messages.get(Messages.VERIFY_HASJOINED_FAILED, username, result.status().toString()));
                return result;
            }
        } finally {
            packetListener.cancelHandshake(channel);
        }
    }

    // ==================== 加密启用 ====================

    /**
     * 启用 AES/CFB8 加密（通过 Netty Handler）。
     *
     * @return true = 加密已启用；false = 启用失败（调用方必须拒绝登录）
     */
    private boolean enableEncryption(Channel channel, AuthCrypto crypto, byte[] sharedSecret) {
        return enableAesViaNetty(channel, sharedSecret);
    }

    /**
     * @return true = 加密已启用；false = 锚点缺失，加密启用失败（decrypt 半启用状态已回滚）
     */
    private boolean enableAesViaNetty(Channel channel, byte[] sharedSecret) {
        try {
            Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            decryptCipher.init(Cipher.DECRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(sharedSecret, "AES"),
                    new javax.crypto.spec.IvParameterSpec(sharedSecret));

            Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
            encryptCipher.init(Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(sharedSecret, "AES"),
                    new javax.crypto.spec.IvParameterSpec(sharedSecret));

            // 入站解密：必须在 frame decoder 之前，解密整个字节流
            String decryptAnchor = findPipelineHandler(channel, "splitter", "decompress", "decoder");
            if (decryptAnchor == null) {
                logger.warning(Messages.VERIFY_INBOUND_ANCHOR_MISSING);
                return false;
            }
            channel.pipeline().addBefore(decryptAnchor, "multiauth-decrypt",
                    new AesDecryptHandler(decryptCipher));

            // 出站加密：必须在 frame prepender 之前，加密含长度前缀的完整数据
            String encryptAnchor = findPipelineHandler(channel, "prepender", "compress", "encoder");
            if (encryptAnchor == null) {
                logger.warning(Messages.VERIFY_OUTBOUND_ANCHOR_MISSING);
                // 回滚已添加的 decrypt handler，避免半启用状态
                try {
                    channel.pipeline().remove("multiauth-decrypt");
                } catch (Exception re) {
                    logger.fine(Messages.get(Messages.VERIFY_HANDLER_ROLLBACK_DEBUG, "multiauth-decrypt", re.getMessage()));
                }
                return false;
            }
            channel.pipeline().addBefore(encryptAnchor, "multiauth-encrypt",
                    new AesEncryptHandler(encryptCipher));

            logger.fine(Messages.get(Messages.VERIFY_AES_ENABLED, decryptAnchor, encryptAnchor));
            return true;
        } catch (Exception e) {
            // 回滚已添加的 decrypt/encrypt handler，避免半启用状态
            try {
                channel.pipeline().remove("multiauth-decrypt");
            } catch (Exception re) {
                logger.fine(Messages.get(Messages.VERIFY_HANDLER_ROLLBACK_DEBUG, "multiauth-decrypt", re.getMessage()));
            }
            try {
                channel.pipeline().remove("multiauth-encrypt");
            } catch (Exception re) {
                logger.fine(Messages.get(Messages.VERIFY_HANDLER_ROLLBACK_DEBUG, "multiauth-encrypt", re.getMessage()));
            }
            logger.warning(Messages.get(Messages.VERIFY_AES_ENABLE_FAILED, e.getMessage()));
            return false;
        }
    }

    /** 在 pipeline 中查找第一个存在的 handler 名称 */
    private static String findPipelineHandler(Channel channel, String... candidates) {
        for (String name : candidates) {
            if (channel.pipeline().get(name) != null) {
                return name;
            }
        }
        return null;
    }

    // ==================== spoofedUUID 设置 ====================

    /**
     * Spigot 在创建 GameProfile 时会检查此字段，如果已设置则使用此 UUID 而非离线 UUID。
     */
    private void setSpoofedUUID(Channel channel, UUID uuid) {
        try {
            Object connection = getConnectionFromChannel(channel);
            if (connection == null) {
                logger.warning(Messages.VERIFY_SPOOFED_CONNECTION_MISSING);
                return;
            }
            connection.getClass().getField("spoofedUUID").set(connection, uuid);
            logger.fine(Messages.get(Messages.VERIFY_SPOOFED_UUID_SET, String.valueOf(uuid)));
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.VERIFY_SPOOFED_FAILED, e.getMessage()));
        }
    }

    // ==================== NMS 工具 ====================

    /**
     * 从 Channel pipeline 获取 Connection 对象（packet_handler）。
     */
    private Object getConnectionFromChannel(Channel channel) {
        try {
            Object handler = channel.pipeline().get("packet_handler");
            if (handler == null) {
                logger.warning(Messages.VERIFY_PACKET_HANDLER_MISSING);
                return null;
            }
            return handler;
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.VERIFY_CONNECTION_FAILED, e.getMessage()));
            return null;
        }
    }

    // ==================== AES 加密 Handler（回退用） ====================

    public static class AesDecryptHandler extends MessageToMessageDecoder<ByteBuf> {
        private final Cipher cipher;

        public AesDecryptHandler(Cipher cipher) {
            this.cipher = cipher;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
            byte[] encrypted = new byte[msg.readableBytes()];
            msg.readBytes(encrypted);
            byte[] decrypted = cipher.update(encrypted);
            // cipher.update 对空输入返回 null，wrappedBuffer(null) 会抛 NPE（连接断开瞬间可能触发）
            out.add(decrypted != null ? Unpooled.wrappedBuffer(decrypted) : Unpooled.EMPTY_BUFFER);
        }
    }

    public static class AesEncryptHandler extends MessageToMessageEncoder<ByteBuf> {
        private final Cipher cipher;

        public AesEncryptHandler(Cipher cipher) {
            this.cipher = cipher;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
            byte[] plain = new byte[msg.readableBytes()];
            msg.readBytes(plain);
            byte[] encrypted = cipher.update(plain);
            // cipher.update 对空输入返回 null，wrappedBuffer(null) 会抛 NPE（连接断开瞬间可能触发）
            out.add(encrypted != null ? Unpooled.wrappedBuffer(encrypted) : Unpooled.EMPTY_BUFFER);
        }
    }
}
