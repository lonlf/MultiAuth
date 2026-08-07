package com.lonleaf.multiauth.listener;

import com.lonleaf.multiauth.Messages;
import com.lonleaf.multiauth.auth.AuthCrypto;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

/**
 * PacketEvents 数据包监听器，用于 Spigot proxy=false 模式的加密握手。
 */
public class SpigotPacketListener extends PacketListenerAbstract {

    private final Logger logger;

    /** Channel → 待处理握手 映射（在发送 EncryptionRequest 前设置） */
    private final ConcurrentMap<Channel, PendingHandshake> pendingHandshakes = new ConcurrentHashMap<>();

    /** 已验证的 Channel 集合 — WeakHashMap 自动清理断开的 channel。
     */
    private final java.util.Set<Channel> verifiedChannels =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** 用户名 → 验证结果（供 AsyncPlayerPreLoginEvent 检查） */
    private final ConcurrentMap<String, VerificationResult> verifiedUsers = new ConcurrentHashMap<>();

    /** Channel → 登录状态（用户名 + 关闭标志），用于 channel 断开时清理 verifiedUsers 等状态，避免内存泄漏 */
    private final ConcurrentMap<Channel, LoginState> channelToUsername = new ConcurrentHashMap<>();

    /** 单个 Channel 的登录状态：记录用户名，并标记 channel 是否已关闭（处理验证完成与关闭之间的竞态） */
    private static final class LoginState {
        final String username;
        final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        LoginState(String username) {
            this.username = username;
        }
    }

    /** 已发送过 Disconnect 包的 Channel 集合（防止重复发送）— WeakHashMap 自动清理断开的 channel */
    private final java.util.Set<Channel> disconnectedChannels =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>()));

    /** LOGIN_START 回调（由 SpigotAuthListener 设置） */
    private volatile LoginStartCallback loginStartCallback;

    /** 加密响应数据（sharedSecret + verifyToken） */
    public record EncryptionResponseData(byte[] sharedSecret, byte[] verifyToken) {}

    /** 待处理的加密握手 */
    public static class PendingHandshake {
        public final CompletableFuture<EncryptionResponseData> future = new CompletableFuture<>();
        public final AuthCrypto crypto;
        public final String username;

        public PendingHandshake(AuthCrypto crypto, String username) {
            this.crypto = crypto;
            this.username = username;
        }
    }

    /** 验证结果（传递到 AsyncPlayerPreLoginEvent）。携带 Channel 以区分同用户名的并发连接。 */
    public record VerificationResult(boolean allowed, UUID uuid, String denyMessage, Channel channel) {}

    /** LOGIN_START 异步回调接口 */
    @FunctionalInterface
    public interface LoginStartCallback {
        void onLoginStart(String username, Channel channel, InetAddress address);
    }

    public SpigotPacketListener(Logger logger) {
        this.logger = logger;
    }

    /** 注册到 PacketEvents */
    public void register() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
        logger.fine(Messages.get(Messages.PACKET_LISTENER_REGISTERED));
    }

    /** 注销 */
    public void unregister() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    /** 设置 LOGIN_START 回调 */
    public void setLoginStartCallback(LoginStartCallback callback) {
        this.loginStartCallback = callback;
    }

    /** PacketEvents 是否可用 */
    public static boolean isAvailable() {
        try {
            Class.forName("com.github.retrooper.packetevents.PacketEvents");
            return PacketEvents.getAPI() != null;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return false;
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    /**
     * 处理 LOGIN_START 包。
     */
    private void handleLoginStart(PacketReceiveEvent event) {
        try {
            WrapperLoginClientLoginStart start = new WrapperLoginClientLoginStart(event);
            String username = start.getUsername();
            Object channelObj = event.getUser().getChannel();

            if (username == null || !(channelObj instanceof Channel)) {
                return;
            }
            Channel channel = (Channel) channelObj;

            // 检查是否已验证：仅在 receivePacket 回退路径下假包会再次触发 LOGIN_START，
            // 此时放行假包让服务器处理（receivePacketSilently 主路径不经过本监听器）
            if (verifiedChannels.remove(channel)) {
                // 已验证，放行假包让服务器处理
                logger.fine(Messages.get(Messages.PACKET_LOGIN_START_VERIFIED, username));
                return;
            }

            // 第一次收到：取消包，不让服务器处理
            event.setCancelled(true);
            logger.fine(Messages.get(Messages.PACKET_LOGIN_START_USERNAME, username));

            // 记录 channel → 登录状态 映射，并注册 close future 监听器
            // channel 断开时主动清理 verifiedUsers / pendingHandshakes，避免内存泄漏
            // 注意：closeFuture 只触发一次。若验证在 channel 关闭之后才完成，
            // putVerificationResult 会通过 closed 标志跳过缓存，防止 verifiedUsers 残留
            LoginState state = new LoginState(username);
            channelToUsername.put(channel, state);
            channel.closeFuture().addListener((io.netty.channel.ChannelFutureListener) future -> {
                Channel ch = future.channel();
                LoginState st = channelToUsername.remove(ch);
                if (st != null) {
                    st.closed.set(true);
                    // 仅移除属于该 Channel 的验证结果，避免误删同用户名其他连接的 ALLOW 结果
                    VerificationResult cur = verifiedUsers.get(st.username);
                    if (cur != null && cur.channel() == ch) {
                        verifiedUsers.remove(st.username);
                    }
                }
                PendingHandshake hs = pendingHandshakes.remove(ch);
                if (hs != null) {
                    // 立即释放 verify() 等待线程：channel 已关闭，EncryptionResponse 永远不会到来，
                    // 不 complete future 会让 verify 线程白等满 5s（恶意"连上即断"可打满验证线程池）
                    hs.future.completeExceptionally(new java.nio.channels.ClosedChannelException());
                }
            });

            // 获取客户端 IP
            InetAddress address = null;
            if (channel.remoteAddress() instanceof InetSocketAddress socketAddr) {
                address = socketAddr.getAddress();
            }

            // 异步触发验证回调
            if (loginStartCallback != null) {
                loginStartCallback.onLoginStart(username, channel, address);
            } else {
                logger.warning(Messages.get(Messages.PACKET_NO_VERIFY_CALLBACK, username));
            }
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.PACKET_LOGIN_START_PARSE_FAILED, e.getMessage()));
        }
    }

    /**
     * 处理 ENCRYPTION_RESPONSE 包：提取加密数据，完成 CompletableFuture，取消包传递。
     */
    private void handleEncryptionResponse(PacketReceiveEvent event) {
        Object channelObj = event.getUser().getChannel();
        if (!(channelObj instanceof Channel)) {
            return;
        }
        Channel channel = (Channel) channelObj;

        PendingHandshake handshake = pendingHandshakes.get(channel);
        if (handshake == null) {
            return;
        }

        try {
            WrapperLoginClientEncryptionResponse response = new WrapperLoginClientEncryptionResponse(event);
            byte[] sharedSecret = response.getEncryptedSharedSecret();
            byte[] verifyToken = response.getEncryptedVerifyToken().orElse(null);

            if (sharedSecret == null || verifyToken == null) {
                throw new IllegalStateException("加密响应数据缺失");
            }

            logger.fine(Messages.get(Messages.PACKET_ENC_RESPONSE_RECEIVED, handshake.username));

            event.setCancelled(true);
            handshake.future.complete(new EncryptionResponseData(sharedSecret, verifyToken));
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.PACKET_ENC_RESPONSE_PARSE_FAILED, e.getMessage()));
            event.setCancelled(true);
            handshake.future.completeExceptionally(e);
        } finally {
            pendingHandshakes.remove(channel);
        }
    }

    // ==================== 供 SpigotMojangVerifier 调用的 API ====================

    /** 注册待处理的加密握手。 */
    public PendingHandshake registerHandshake(String username, Channel channel, AuthCrypto crypto) {
        PendingHandshake handshake = new PendingHandshake(crypto, username);
        pendingHandshakes.put(channel, handshake);
        return handshake;
    }

    /** 取消待处理的握手（超时或错误时调用）。 */
    public void cancelHandshake(Channel channel) {
        pendingHandshakes.remove(channel);
    }

    /** 发送 EncryptionRequest 包。 */
    public void sendEncryptionRequest(Channel channel, AuthCrypto crypto) {
        String serverId = "";
        byte[] publicKey = crypto.getPublicKeyBytes();
        byte[] verifyToken = crypto.getVerifyToken();

        try {
            WrapperLoginServerEncryptionRequest request = new WrapperLoginServerEncryptionRequest(
                    serverId, publicKey, verifyToken);
            PacketEvents.getAPI().getProtocolManager().sendPacket(channel, request);
            logger.fine(Messages.get(Messages.PACKET_ENC_REQUEST_SENT));
        } catch (Throwable e) {
            logger.warning(Messages.get(Messages.PACKET_ENC_REQUEST_WRAPPER_FAILED, e.getMessage()));
            sendRawEncryptionRequest(channel, serverId, publicKey, verifyToken);
        }
    }

    // ==================== 方案 A 核心：假包 + 验证状态管理 ====================

    /**
     * 标记 Channel 已验证。下一次 LOGIN_START 会放行（假包）。
     */
    public void markVerified(Channel channel) {
        verifiedChannels.add(channel);
    }

    /**
     * 缓存验证结果（供 AsyncPlayerPreLoginEvent 检查）。
     */
    public void putVerificationResult(String username, Channel channel, VerificationResult result) {
        LoginState state = channelToUsername.get(channel);
        if (state != null && state.closed.get()) {
            logger.fine(Messages.get(Messages.PACKET_VERIFY_CHANNEL_CLOSED, username));
            return;
        }
        // ALLOW 覆盖保护：同一用户名已有其他连接的 ALLOW 结果时，
        // 禁止用本连接的 DENY 覆盖（否则并发登录中盗版连接可能踢掉正版连接）。
        // 反向场景（DENY 被 ALLOW 覆盖）无害：DENY 连接已被 Disconnect 踢出，不会触发预登录。
        VerificationResult existing = verifiedUsers.get(username);
        if (existing != null && existing.allowed() && !result.allowed() && existing.channel() != channel) {
            logger.warning(Messages.get(Messages.AUTH_ALLOW_WINS_DENY_IGNORED, username));
            return;
        }
        verifiedUsers.put(username, result);
    }

    /**
     * 获取并移除验证结果。
     */
    public VerificationResult getAndRemoveVerificationResult(String username) {
        return verifiedUsers.remove(username);
    }

    /**
     * 发送包含正版 UUID 的假 LOGIN_START 包（方案 A 核心）。
     *
     * @return true = 注入成功；false = 注入失败（已兜底踢出玩家，防止卡死在登录界面）
     */
    public boolean sendFakeLoginStart(Channel channel, String username, UUID uuid) {
        try {
            WrapperLoginClientLoginStart fakePacket = new WrapperLoginClientLoginStart(
                    ClientVersion.UNKNOWN,
                    username,
                    null,  // SignatureData（1.19+ 的签名，null 表示无）
                    uuid   // 正版 UUID
            );
            // receivePacketSilently：不触发 PacketEvents 监听器，避免循环拦截
            PacketEvents.getAPI().getProtocolManager().receivePacketSilently(channel, fakePacket);
            logger.fine(Messages.get(Messages.PACKET_FAKE_LOGIN_START_SENT, username, String.valueOf(uuid)));
            return true;
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.PACKET_FAKE_LOGIN_START_FAILED, e.getMessage()));
            // 回退：尝试 receivePacket
            try {
                WrapperLoginClientLoginStart fakePacket = new WrapperLoginClientLoginStart(
                        ClientVersion.UNKNOWN, username, null, uuid);
                PacketEvents.getAPI().getProtocolManager().receivePacket(channel, fakePacket);
                logger.fine(Messages.get(Messages.PACKET_FAKE_LOGIN_START_FALLBACK_SENT));
                return true;
            } catch (Exception e2) {
                // 兜底：注入失败时玩家会卡死在登录界面（LOGIN_START 已被取消且服务器不再收到包），
                // 直接踢出并发送错误消息，让玩家重连
                logger.warning(Messages.get(Messages.PACKET_FAKE_LOGIN_START_FALLBACK_FAILED, username, e2.getMessage()));
                sendDisconnect(channel, Messages.PACKET_FAKE_LOGIN_START_KICK);
                return false;
            }
        }
    }

    /**
     * 发送 Disconnect 包踢出客户端。
     * 如果 channel 已断开或已发送过 Disconnect 包，跳过发送。
     */
    public void sendDisconnect(Channel channel, String message) {
        LoginState state = channelToUsername.get(channel);
        String username = state != null ? state.username : null;
        if (!channel.isActive()) {
            logger.fine(Messages.get(Messages.PACKET_DISCONNECT_CHANNEL_CLOSED));
            return;
        }
        if (!disconnectedChannels.add(channel)) {
            logger.fine(Messages.get(Messages.PACKET_DISCONNECT_ALREADY_SENT));
            return;
        }
        try {
            WrapperLoginServerDisconnect disconnect = new WrapperLoginServerDisconnect(
                    Component.text(message));
            PacketEvents.getAPI().getProtocolManager().sendPacket(channel, disconnect);
            logger.fine(Messages.get(Messages.PACKET_DISCONNECT_SENT, message));
            logger.warning(Messages.get(Messages.KICK_MESSAGE_SENT,
                    username != null ? username : "?", message.replace("\n", "\\n")));
        } catch (Exception e) {
            logger.warning(Messages.get(Messages.PACKET_DISCONNECT_SEND_FAILED, e.getMessage()));
            channel.close();
        }
    }

    // ==================== 原始二进制回退 ====================

    private void sendRawEncryptionRequest(Channel channel, String serverId, byte[] publicKey, byte[] verifyToken) {
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
        writeVarInt(buf, 0x01);
        writeByteArray(buf, serverId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        writeByteArray(buf, publicKey);
        writeByteArray(buf, verifyToken);
        channel.writeAndFlush(buf);
        logger.fine(Messages.get(Messages.PACKET_ENC_REQUEST_RAW_SENT));
    }

    private void writeVarInt(io.netty.buffer.ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private void writeByteArray(io.netty.buffer.ByteBuf buf, byte[] data) {
        writeVarInt(buf, data.length);
        buf.writeBytes(data);
    }
}
