package com.max.privatecardbackup;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class HashingTest {
    @Test
    public void copiesEveryByteAndReturnsKnownSha256() throws Exception {
        byte[] source = "PrivateCard backup test".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream destination = new ByteArrayOutputStream();

        Hashing.Result result = Hashing.copyAndHash(new ByteArrayInputStream(source), destination);

        assertArrayEquals(source, destination.toByteArray());
        assertEquals(source.length, result.size);
        assertEquals("05faa8b1a256e1869f7c522c5f01cfb481c75bc425498e3341fa82e8552ce93e", result.sha256);
        assertTrue(result.matches(source.length, result.sha256));
    }
}
