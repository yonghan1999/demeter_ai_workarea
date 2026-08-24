package com.demeter.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class TokenDigests {

    private TokenDigests() {
    }

    public static String sha256(String value) {
        return sha256Bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Bytes(byte[] value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
