package com.lonleaf.multiauth.auth;

import javax.crypto.*;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public class AuthCrypto {

    private final KeyPair rsaKeyPair;
    private final byte[] verifyToken;

    public AuthCrypto() {
        this.rsaKeyPair = generateRsaKeyPair();
        this.verifyToken = generateVerifyToken();
    }

    /**
     * 复用已生成的 RSA 密钥对，仅重新生成随机 verifyToken。
     */
    public AuthCrypto(KeyPair keyPair) {
        this.rsaKeyPair = keyPair;
        this.verifyToken = generateVerifyToken();
    }

    public AuthCrypto(KeyPair keyPair, byte[] verifyToken) {
        this.rsaKeyPair = keyPair;
        this.verifyToken = verifyToken;
    }

    private KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator keyPairGen = KeyPairGenerator.getInstance("RSA");
            keyPairGen.initialize(2048);
            return keyPairGen.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to generate RSA key pair", e);
        }
    }

    private byte[] generateVerifyToken() {
        byte[] token = new byte[4];
        new SecureRandom().nextBytes(token);
        return token;
    }

    public PublicKey getPublicKey() {
        return rsaKeyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return rsaKeyPair.getPrivate();
    }

    public KeyPair getRsaKeyPair() {
        return rsaKeyPair;
    }

    public byte[] getVerifyToken() {
        return verifyToken.clone();
    }

    public boolean verifyToken(byte[] token) {
        return Arrays.equals(this.verifyToken, token);
    }

    public byte[] getPublicKeyBytes() {
        return rsaKeyPair.getPublic().getEncoded();
    }

    public String getPublicKeyHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(getPublicKeyBytes());
            return new BigInteger(1, hash).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to hash public key", e);
        }
    }

    public byte[] decryptSharedSecret(byte[] encryptedSharedSecret, byte[] encryptedVerifyToken) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, rsaKeyPair.getPrivate());

            byte[] decryptedSecret = cipher.doFinal(encryptedSharedSecret);
            byte[] decryptedToken = cipher.doFinal(encryptedVerifyToken);

            if (!verifyToken(decryptedToken)) {
                throw new SecurityException("Verify token mismatch");
            }

            return decryptedSecret;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt shared secret", e);
        }
    }

    public String computeServerId(byte[] sharedSecret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(sharedSecret);
            digest.update(getPublicKeyBytes());
            byte[] hash = digest.digest();
            // 必须使用 BigInteger(hash) 而非 BigInteger(1, hash)
            // Minecraft 客户端使用无 signum 构造（允许负值，toString(16) 带 "-" 前缀），
            // signum=1 会强制正数导致 serverId 不匹配，Mojang hasJoined 返回 204
            return new BigInteger(hash).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to compute serverId", e);
        }
    }

    public Cipher createAesCipher(byte[] sharedSecret, boolean encrypt) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(sharedSecret, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(sharedSecret);
            Cipher cipher = Cipher.getInstance("AES/CFB8/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create AES cipher", e);
        }
    }

    public static PublicKey decodePublicKey(byte[] encodedKey) {
        try {
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encodedKey);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode public key", e);
        }
    }
}
