package com.mengying.fqnovel.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * GZIP 压缩/解压缩工具类
 * 统一处理上游响应的 GZIP 解压逻辑
 */
public final class GzipUtils {

    private GzipUtils() {}

    private static boolean hasGzipMagic(byte[] data) {
        return data != null
            && data.length >= 2
            && data[0] == (byte) 0x1f
            && data[1] == (byte) 0x8b;
    }

    private static String utf8(byte[] data) {
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    private static String decodeRawBody(byte[] data) {
        return utf8(data);
    }

    private static String ungzip(byte[] gzipData) throws IOException {
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(gzipData))) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = gzipInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, length);
            }
            return utf8(byteArrayOutputStream.toByteArray());
        }
    }

    /**
     * 解压缩 GZIP 响应体（基础方法）
     *
     * @param gzipData 应为 GZIP 压缩的字节数组
     * @return 解压后的字符串
     * @throws IOException 数据不是有效 GZIP 或解压失败
     */
    public static String decompressGzipResponse(byte[] gzipData) throws IOException {
        if (gzipData == null || gzipData.length == 0) {
            return "";
        }

        if (!hasGzipMagic(gzipData)) {
            throw new ZipException("Not in GZIP format");
        }
        return ungzip(gzipData);
    }

    /**
     * 统一解码上游响应（自动处理 GZIP 压缩）
     * 根据 GZIP 魔数判断是否需要解压。HTTP 客户端已解压或上游误标时按原始文本处理。
     *
     * @param body HTTP 响应体
     * @return 解码后的字符串
     */
    public static String decodeUpstreamResponse(byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return "";
        }

        if (!hasGzipMagic(body)) {
            return decodeRawBody(body);
        }
        return ungzip(body);
    }
}
