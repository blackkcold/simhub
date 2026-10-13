package com.blackkcold.simhub;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class BoundedHttpReaderTest {
    @Test public void acceptsExactByteLimitAndUnicode() throws Exception {
        byte[] bytes = "{\"body\":\"中文\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(new String(bytes, StandardCharsets.UTF_8),
            BoundedHttpReader.readUtf8(new ByteArrayInputStream(bytes), bytes.length));
    }

    @Test public void rejectsResponseOverLimitBeforeFullAllocation() throws Exception {
        byte[] bytes = new byte[8193];
        try {
            BoundedHttpReader.readUtf8(new ByteArrayInputStream(bytes), 8192);
            fail("Must reject an oversized Relay response");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("exceeds"));
        }
    }

    @Test public void rejectsNegativeLimitAndAcceptsEmptyBody() throws Exception {
        assertEquals("", BoundedHttpReader.readUtf8(null, 0));
        assertEquals("", BoundedHttpReader.readUtf8(new ByteArrayInputStream(new byte[0]), 0));
        try {
            BoundedHttpReader.readUtf8(new ByteArrayInputStream(new byte[0]), -1);
            fail("Negative limit was accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
