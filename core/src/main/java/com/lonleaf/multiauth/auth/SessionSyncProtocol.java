package com.lonleaf.multiauth.auth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * 跨服会话同步消息协议。
 */
public final class SessionSyncProtocol {

    /** Plugin Messaging 通道名（namespace:name 格式） */
    public static final String CHANNEL_ID = "multiauth:session";

    /** 消息类型：玩家登录成功（Velocity → Spigot） */
    public static final String ACTION_LOGIN = "login";

    /** 消息类型：玩家断开连接（Velocity → Spigot） */
    public static final String ACTION_LOGOUT = "logout";

    private SessionSyncProtocol() {}

    /** 构造登录同步消息 */
    public static byte[] buildLoginMessage(String username, UUID uuid, String ip, boolean isPremium, long loginTime) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF(ACTION_LOGIN);
            out.writeUTF(username);
            out.writeUTF(uuid.toString());
            out.writeUTF(ip != null ? ip : "?");
            out.writeBoolean(isPremium);
            out.writeLong(loginTime);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build login message", e);
        }
    }

    /** 构造登出同步消息 */
    public static byte[] buildLogoutMessage(String username, UUID uuid) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeUTF(ACTION_LOGOUT);
            out.writeUTF(username);
            out.writeUTF(uuid.toString());
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to build logout message", e);
        }
    }

    /** 解析消息 */
    public static SessionSyncMessage parse(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             DataInputStream in = new DataInputStream(bais)) {
            String action = in.readUTF();
            String username = in.readUTF();
            String uuidStr = in.readUTF();
            UUID uuid = UUID.fromString(uuidStr);
            if (ACTION_LOGOUT.equals(action)) {
                return new SessionSyncMessage(action, username, uuid, null, false, 0);
            }
            String ip = in.readUTF();
            boolean isPremium = in.readBoolean();
            long loginTime = in.readLong();
            return new SessionSyncMessage(action, username, uuid, ip, isPremium, loginTime);
        } catch (IOException e) {
            throw new RuntimeException("Failed to parse session sync message", e);
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
