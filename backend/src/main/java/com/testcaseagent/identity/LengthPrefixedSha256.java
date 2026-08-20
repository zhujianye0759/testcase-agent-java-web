package com.testcaseagent.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** Computes an unambiguous SHA-256 identity by prefixing every UTF-8 field with its byte length. */
public final class LengthPrefixedSha256 {
    private LengthPrefixedSha256() { }

    /** Hashes ordered fields without relying on a sentinel character that may also occur inside a field. */
    public static byte[] digest(String... fields) {
        Objects.requireNonNull(fields, "fields must not be null");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = Objects.requireNonNull(field, "identity field must not be null")
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by Java", exception);
        }
    }
}
