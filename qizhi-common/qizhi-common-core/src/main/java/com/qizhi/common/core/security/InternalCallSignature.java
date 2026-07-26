package com.qizhi.common.core.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 内部调用 HMAC-SHA256 签名工具。
 */
public final class InternalCallSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private InternalCallSignature() {
    }

    public static String sign(String secret, String caller, String timestamp,
                              String nonce, String method, String path) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("internal call secret is required");
        }
        String canonical = String.join("\n",
                safe(caller), safe(timestamp), safe(nonce),
                safe(method).toUpperCase(), normalizePath(path));
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot calculate internal signature", e);
        }
    }

    public static boolean verify(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String value = path.startsWith("/") ? path : "/" + path;
        return value.length() > 1 && value.endsWith("/")
                ? value.substring(0, value.length() - 1) : value;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
