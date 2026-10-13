package com.blackkcold.simhub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Read untrusted Relay JSON with a strict byte cap before allocating the whole body. */
final class BoundedHttpReader {
    static final int MAX_JSON_BYTES = 2_000_000;

    private BoundedHttpReader() {}

    static String readUtf8(InputStream input, int maxBytes) throws IOException {
        if (input == null) return "";
        if (maxBytes < 0) throw new IllegalArgumentException("Negative response limit");
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > maxBytes - out.size())
                    throw new IOException("Relay response exceeds " + maxBytes + " bytes");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
