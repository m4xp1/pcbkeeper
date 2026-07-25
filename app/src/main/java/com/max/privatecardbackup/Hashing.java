package com.max.privatecardbackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class Hashing {
    private static final int BUFFER_SIZE = 64 * 1024;

    private Hashing() {
    }

    static Result copyAndHash(InputStream input, OutputStream output) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            digest.update(buffer, 0, read);
            size += read;
        }
        output.flush();
        return new Result(size, toHex(digest.digest()));
    }

    static Result hash(InputStream input) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_SIZE];
        long size = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
            size += read;
        }
        return new Result(size, toHex(digest.digest()));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    static final class Result {
        final long size;
        final String sha256;

        Result(long size, String sha256) {
            this.size = size;
            this.sha256 = sha256;
        }

        boolean matches(long expectedSize, String expectedSha256) {
            return size == expectedSize && sha256.equalsIgnoreCase(expectedSha256);
        }
    }
}
