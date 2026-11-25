package com.example.volctos.tos;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TOS4-HMAC-SHA256 签名（结构与火山引擎对象存储 TOS 的 V4 签名一致，仅去掉 region 之外的派生项精简）。
 *
 * 规范串：
 *   HTTPMethod \n
 *   CanonicalURI \n
 *   CanonicalQueryString \n
 *   host:{host} \n
 *   [x-tos-date:{date} \n]         // 预签名 URL 时该字段在 query 里，不再重复
 *   \n
 *   SignedHeaders \n
 *   HashedPayload
 *
 * 签名密钥：HMAC(HMAC(HMAC(HMAC("TOS4"+SK, yyyymmdd), region), service), "request")
 */
public final class TosSigner {

    public static final String REGION = "cn-beijing";
    public static final String SERVICE = "tos";
    public static final String ALGO = "TOS4-HMAC-SHA256";
    public static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.US);

    private TosSigner() {}

    public static String nowDate() {
        return ZonedDateTime.now(ZoneOffset.UTC).format(AMZ_DATE);
    }

    /** 把 query 参数按 key 排序拼成规范串（URL 不编码，本项目全是 ASCII 安全字符）。 */
    public static String canonicalQuery(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) sb.append('&');
            sb.append(k).append('=').append(params.getOrDefault(k, ""));
        }
        return sb.toString();
    }

    /** 解析 raw query，重复 key 保留最后一个（够用）。 */
    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> m = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return m;
        for (String kv : rawQuery.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            if (i < 0) m.put(kv, "");
            else m.put(kv.substring(0, i), kv.substring(i + 1));
        }
        return m;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] signingKey(String secretKey, String date) {
        String day = date.substring(0, 8);
        byte[] k = hmac(("TOS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        k = hmac(k, REGION);
        k = hmac(k, SERVICE);
        return hmac(k, "request");
    }

    /**
     * @param presigned true 表示把 x-tos-date 放进 query，规范头只剩 host
     */
    public static String signature(String method, String path, Map<String, String> params,
                                   String host, String date, String secretKey, boolean presigned) {
        String canonicalHeaders = presigned
                ? "host:" + host + "\n"
                : "host:" + host + "\n" + "x-tos-date:" + date + "\n";
        String signedHeaders = presigned ? "host" : "host;x-tos-date";
        String canonical = method + "\n"
                + path + "\n"
                + canonicalQuery(params) + "\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + sha256Hex("");
        return hex(hmac(signingKey(secretKey, date), canonical));
    }

    /** 生成 Authorization 头（普通请求，走 header 鉴权）。 */
    public static String authorization(String accessKey, String secretKey, String method,
                                       String path, Map<String, String> params,
                                       String host, String date) {
        String sig = signature(method, path, params, host, date, secretKey, false);
        String scope = date.substring(0, 8) + "/" + REGION + "/" + SERVICE + "/request";
        return ALGO + " Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=host;x-tos-date, Signature=" + sig;
    }

    /** 验签：header 鉴权分支。 */
    public static boolean verifyHeader(String accessKey, String secretKey, String method, String path,
                                       Map<String, String> params, String host, String date,
                                       String authorization) {
        if (authorization == null || !authorization.startsWith(ALGO)) return false;
        if (!authorization.contains("Credential=" + accessKey + "/")) return false;
        String expect = signature(method, path, params, host, date, secretKey, false);
        return authorization.endsWith("Signature=" + expect);
    }

    /** 验签：预签名 URL 分支（X-Tos-Signature 放在 query 里）。 */
    public static boolean verifyPresigned(String secretKey, String method, String path,
                                          Map<String, String> params, String host) {
        Map<String, String> copy = new LinkedHashMap<>(params);
        String sig = copy.remove("X-Tos-Signature");
        String date = copy.get("X-Tos-Date");
        if (sig == null || date == null) return false;
        String expect = signature(method, path, copy, host, date, secretKey, true);
        return expect.equals(sig);
    }
}
