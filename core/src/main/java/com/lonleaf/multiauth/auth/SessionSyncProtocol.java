package com.lonleaf.multiauth.auth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 跨服会话同步消息协议（HMAC-SHA256 签名保护）。
 */
public final class SessionSyncProtocol {

    /** Plugin Messaging 通道名（namespace:name 格式） */
    public static final String CHANNEL_ID = "multiauth:session";

    /** 消息类型：玩家登录成功（Velocity → Spigot） */
    public static final String ACTION_LOGIN = "login";

    /** 消息类型：玩家断开连接（Velocity → Spigot） */
    public static final String ACTION_LOGOUT = "logout";

    private SessionSyncProtocol() {}

    /**
     * 构造登录同步消息（首字段为签名，验签范围 = 后续全部 payload 字节）。
     *
     * @param username   玩家名
     * @param uuid       玩家 UUID
     * @param ip         登录 IP
     * @param isPremium  是否正版
     * @param loginTime  登录时间戳
     * @param secret     签名密钥（空或 null 时不签名，签名位写空字符串）
     */
    public static byte[] buildLoginMessage(String username, UUID uuid, String ip,
                                           boolean isPremium, long loginTime, String secret) {
        try (ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(payloadStream)) {
            out.writeUTF(ACTION_LOGIN);
            out.writeUTF(username);
            out.writeUTF(uuid.toString());
            out.writeUTF(ip != null ? ip : "?");
            out.writeBoolean(isPremium);
            out.writeLong(loginTime);
            return attachSignature(payloadStream.toByteArray(), secret);
        } catch (IOException e) {
            throw new RuntimeException("Failed to build login message", e);
        }
    }

    /**
     * 构造登出同步消息（首字段为签名，验签范围 = 后续全部 payload 字节）。
     */
    public static byte[] buildLogoutMessage(String username, UUID uuid, String secret) {
        try (ByteArrayOutputStream payloadStream = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(payloadStream)) {
            out.writeUTF(ACTION_LOGOUT);
            out.writeUTF(username);
            out.writeUTF(uuid.toString());
            return attachSignature(payloadStream.toByteArray(), secret);
        } catch (IOException e) {
            throw new RuntimeException("Failed to build logout message", e);
        }
    }

    /** 为 payload 附加签名：writeUTF(signature) + payload */
    private static byte[] attachSignature(byte[] payload, String secret) throws IOException {
        String signature = hmacHex(payload, secret);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF(signature);
            out.write(payload);
            return baos.toByteArray();
        }
    }

    /**
     * 解析并验签消息。配置了 secret 时验签失败抛 {@link InvalidSignatureException}。
     */
    public static SessionSyncMessage parse(byte[] data, String secret) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream in = new DataInputStream(bais)) {
            String signature = in.readUTF();
            // payload = 从签名之后到消息末尾的全部字节
            int payloadOffset = data.length - in.available();
            byte[] payload = Arrays.copyOfRange(data, payloadOffset, data.length);
            if (secret != null && !secret.isBlank()) {
                if (!hmacVerify(payload, signature, secret)) {
                    throw new InvalidSignatureException();
                }
            }
            try (DataInputStream pin = new DataInputStream(new ByteArrayInputStream(payload))) {
                String action = pin.readUTF();
                String username = pin.readUTF();
                String uuidStr = pin.readUTF();
                UUID uuid = UUID.fromString(uuidStr);
                if (ACTION_LOGOUT.equals(action)) {
                    return new SessionSyncMessage(action, username, uuid, null, false, 0);
                }
                String ip = pin.readUTF();
                boolean isPremium = pin.readBoolean();
                long loginTime = pin.readLong();
                return new SessionSyncMessage(action, username, uuid, ip, isPremium, loginTime);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse session sync message", e);
        }
    }

    /** 计算 payload 的 HMAC-SHA256 十六进制签名（secret 为空时返回空字符串） */
    private static String hmacHex(byte[] data, String secret) {
        if (secret == null || secret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 unavailable", e);
        }
    }

    /** 常量时间比较验签 */
    private static boolean hmacVerify(byte[] data, String signature, String secret) {
        if (signature == null || signature.isEmpty()) {
            return false;
        }
        try {
            String expected = hmacHex(data, secret);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    signature.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            return false;
        }
    }

    /** 签名不匹配异常（配置了 secret 时由 parse 抛出） */
    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException() {
            super("Session sync message signature mismatch");
        }
    }

    /** 会话同步消息数据 */
    public record SessionSyncMessage(
            String action,
            String username,
            UUID uuid,
            String ip,
            boolean isPremium,
            long loginTime
    ) {}
}
