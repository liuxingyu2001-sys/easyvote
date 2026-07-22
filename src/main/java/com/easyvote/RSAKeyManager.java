package com.easyvote;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public class RSAKeyManager {

    private static final int KEY_SIZE = 2048;
    private KeyPair keyPair;

    public RSAKeyManager() {
    }

    public RSAKeyManager(String publicKey, String privateKey) throws Exception {
        this.keyPair = loadKeyPair(publicKey, privateKey);
    }

    public void generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(KEY_SIZE);
        this.keyPair = keyGen.generateKeyPair();
    }

    public String getPublicKeyString() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    public String getPrivateKeyString() {
        return Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
    }

    public String getPublicKeyPEM() {
        String base64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        StringBuilder pem = new StringBuilder();
        pem.append("-----BEGIN PUBLIC KEY-----\n");
        int offset = 0;
        while (offset < base64.length()) {
            int endIndex = Math.min(offset + 64, base64.length());
            pem.append(base64, offset, endIndex).append("\n");
            offset = endIndex;
        }
        pem.append("-----END PUBLIC KEY-----\n");
        return pem.toString();
    }

    public KeyPair getKeyPair() {
        return keyPair;
    }

    public String decrypt(String encryptedData) throws Exception {
        byte[] encryptedBytes = Base64.getDecoder().decode(encryptedData);
        return decryptBytes(encryptedBytes);
    }

    public String decryptBytes(byte[] encryptedData) throws Exception {
        try {
            return decryptWithCipher(encryptedData, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        } catch (Exception e) {
            return decryptWithCipher(encryptedData, "RSA/ECB/PKCS1Padding");
        }
    }

    private String decryptWithCipher(byte[] encryptedData, String transformation) throws Exception {
        Cipher cipher = Cipher.getInstance(transformation);
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
        byte[] decrypted = cipher.doFinal(encryptedData);
        return new String(decrypted, StandardCharsets.UTF_8).trim();
    }

    private KeyPair loadKeyPair(String publicKey, String privateKey) throws Exception {
        String cleanPublicKey = publicKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");

        String cleanPrivateKey = privateKey
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");

        byte[] publicKeyBytes = Base64.getDecoder().decode(cleanPublicKey);
        byte[] privateKeyBytes = Base64.getDecoder().decode(cleanPrivateKey);

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKeyBytes);
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKeyBytes);

        PublicKey pubKey = keyFactory.generatePublic(publicKeySpec);
        PrivateKey privKey = keyFactory.generatePrivate(privateKeySpec);

        return new KeyPair(pubKey, privKey);
    }
}
