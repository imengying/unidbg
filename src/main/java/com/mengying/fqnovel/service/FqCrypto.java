package com.mengying.fqnovel.service;

import com.mengying.fqnovel.utils.GzipUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FQNovel加密解密工具类
 * 基于 AES-128-CBC 加密算法
 */
public class FqCrypto {

    private static final Logger log = LoggerFactory.getLogger(FqCrypto.class);
    
    /**
     * 注册密钥的固定key
     */
    public static final String REG_KEY = "ac25c67ddd8f38c1b37a2348828e222e";

    private static final ConcurrentHashMap<String, FqCrypto> INSTANCE_CACHE = new ConcurrentHashMap<>();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    
    private final SecretKeySpec secretKey;
    
    public FqCrypto(String hexKey) throws Exception {
        if (hexKey == null || hexKey.length() != 32) {
            throw new IllegalArgumentException("Key length mismatch! Expected 32 hex chars, got: " + 
                (hexKey != null ? hexKey.length() : "null"));
        }
        
        byte[] keyBytes = hexStringToByteArray(hexKey);
        if (keyBytes.length != 16) {
            throw new IllegalArgumentException("Key must be 16 bytes after hex decode");
        }
        
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }
    
    /**
     * AES-CBC加密
     * 
     * @param data 待加密数据
     * @param iv 初始化向量
     * @return 加密后的数据
     */
    public byte[] encrypt(byte[] data, byte[] iv) throws Exception {
        if (iv.length != 16) {
            throw new IllegalArgumentException("IV must be 16 bytes");
        }
        
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
        
        return cipher.doFinal(data);
    }
    
    /**
     * AES-CBC解密
     * 
     * @param encodedData Base64编码的加密数据
     * @return 解密后的数据
     */
    public byte[] decrypt(String encodedData) throws Exception {
        byte[] decodedData = Base64.getDecoder().decode(encodedData);
        
        if (decodedData.length < 16) {
            throw new IllegalArgumentException("Encrypted data too short");
        }
        
        // 前16字节是IV
        byte[] iv = new byte[16];
        System.arraycopy(decodedData, 0, iv, 0, 16);
        
        // 剩余部分是加密数据
        byte[] encryptedData = new byte[decodedData.length - 16];
        System.arraycopy(decodedData, 16, encryptedData, 0, encryptedData.length);
        
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
        
        return cipher.doFinal(encryptedData);
    }
    
    /**
     * 生成注册密钥内容
     * 
     * @param serverDeviceId 服务端设备ID
     * @param strVal 字符串值(通常为"0")
     * @return Base64编码的加密内容
     */
    public String newRegisterKeyContent(String serverDeviceId, String strVal) throws Exception {
        long deviceId = Long.parseLong(serverDeviceId);
        long val = Long.parseLong(strVal);
        
        // 将两个long值按小端序转换为字节数组并连接
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(deviceId);
        buffer.putLong(val);
        byte[] combinedBytes = buffer.array();
        
        // 生成随机IV
        byte[] iv = new byte[16];
        SECURE_RANDOM.nextBytes(iv);
        
        // 加密数据
        byte[] encryptedData = encrypt(combinedBytes, iv);
        
        // 组合IV和加密数据
        byte[] finalData = new byte[iv.length + encryptedData.length];
        System.arraycopy(iv, 0, finalData, 0, iv.length);
        System.arraycopy(encryptedData, 0, finalData, iv.length, encryptedData.length);
        
        return Base64.getEncoder().encodeToString(finalData);
    }
    
    /**
     * 解密注册密钥响应
     * 对应 Python 中的 decrypt_registerkey 函数
     * 
     * @param registerkeyResponseKey 注册密钥响应中的加密key
     * @param aesKeyHex AES解密密钥 (十六进制字符串)
     * @return 解密后的真实密钥 (十六进制字符串)
     */
    public static String decryptRegisterKey(String registerkeyResponseKey, String aesKeyHex) throws Exception {
        // Base64解码
        byte[] raw = Base64.getDecoder().decode(registerkeyResponseKey);
        
        if (raw.length < 16) {
            throw new IllegalArgumentException("Encrypted data too short");
        }
        
        // 前16字节是IV
        byte[] iv = new byte[16];
        System.arraycopy(raw, 0, iv, 0, 16);
        
        // 剩余部分是密文
        byte[] cipherText = new byte[raw.length - 16];
        System.arraycopy(raw, 16, cipherText, 0, cipherText.length);
        
        // 关键修复：从hex字符串解码密钥
        byte[] keyBytes = hexStringToByteArray(aesKeyHex);
        
        // 创建AES解密器
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
        IvParameterSpec ivSpec = new IvParameterSpec(iv);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
        
        // 解密
        byte[] decrypted = cipher.doFinal(cipherText);
        
        // 转换为大写十六进制字符串
        String keyHex = byteArrayToHexString(decrypted);
        
        if (log.isDebugEnabled()) {
            log.debug("解密后原始内容 hex: {}", keyHex);
            if (decrypted.length >= 16) {
                log.debug("前16字节 hex: {}", byteArrayToHexString(java.util.Arrays.copyOfRange(decrypted, 0, 16)));
            }
            if (decrypted.length >= 32) {
                log.debug("后16字节 hex: {}", byteArrayToHexString(java.util.Arrays.copyOfRange(decrypted, 16, 32)));
            }
        }
        
        return keyHex;
    }
    
    /**
     * 获取注册密钥的前16字节作为真实密钥
     * 
     * @param registerkeyResponseKey 注册密钥响应中的加密key
     * @return 前16字节的十六进制字符串
     */
    public static String getRealKey(String registerkeyResponseKey) throws Exception {
        String fullKeyHex = decryptRegisterKey(registerkeyResponseKey, REG_KEY);
        
        // 取前16字节 (32个十六进制字符)
        if (fullKeyHex.length() >= 32) {
            return fullKeyHex.substring(0, 32);
        } else {
            throw new IllegalArgumentException("解密后的密钥长度不足");
        }
    }
    
    public static byte[] hexStringToByteArray(String hexString) {
        int len = hexString.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hexString.charAt(i), 16) << 4)
                                 + Character.digit(hexString.charAt(i+1), 16));
        }
        return data;
    }
    
    /**
     * 字节数组转十六进制字符串 (大写)
     * 对应 Python 中的 bytes_to_hex_upper 函数
     */
    public static String byteArrayToHexString(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
    
    /**
     * 解密并解压缩内容
     * 对应 Python 中的内容解密和 gzip 解压缩功能
     * 
     * @param encryptedContent 加密的内容 (Base64编码)
     * @param keyHex 解密密钥 (十六进制字符串)
     * @return 解密并解压后的文本内容
     */
    public static String decryptAndDecompressContent(String encryptedContent, String keyHex) throws Exception {
        FqCrypto crypto = getOrCreate(keyHex);
        byte[] decryptedBytes = crypto.decrypt(encryptedContent);
        return GzipUtils.decompressGzipResponse(decryptedBytes);
    }

    /**
     * 获取或创建 FqCrypto 实例（按 hexKey 缓存，避免重复创建 SecretKeySpec）。
     */
    public static FqCrypto getOrCreate(String hexKey) {
        return INSTANCE_CACHE.computeIfAbsent(hexKey, k -> {
            try {
                return new FqCrypto(k);
            } catch (Exception e) {
                throw new IllegalArgumentException("无法创建 FqCrypto 实例: " + e.getMessage(), e);
            }
        });
    }
}
