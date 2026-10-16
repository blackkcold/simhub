package com.blackkcold.simhub;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * Minimal HTTPS server for local TLS tests. It exists so the pinning behavior of the
 * real client can be exercised against a real handshake without weakening production
 * trust: the test anchor is injected only here.
 */
final class HttpsTestServer implements AutoCloseable {
    private final SSLServerSocket server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile String location;

    HttpsTestServer(Path certPem, Path keyPem) throws Exception {
        server = (SSLServerSocket) contextFor(certPem, keyPem).getServerSocketFactory().createServerSocket(0, 8);
        server.setReuseAddress(true);
        executor.execute(this::acceptLoop);
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                executor.execute(() -> {
                    try (Socket open = socket) {
                        open.setSoTimeout(5000);
                        handle(open);
                    } catch (Exception ignored) {
                        // A refused handshake never reaches handle(); that is the point.
                    }
                });
            } catch (Exception ignored) {
                return;
            }
        }
    }

    private void handle(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int first = -1, second = -1, third = -1;
        while (true) {
            int value = in.read();
            if (value < 0) return;
            head.write(value);
            if (first == '\r' && second == '\n' && third == '\r' && value == '\n') break;
            first = second;
            second = third;
            third = value;
        }
        String header = head.toString(StandardCharsets.ISO_8859_1);
        requests.add(header.split("\r\n", 2)[0]);
        int contentLength = 0;
        for (String line : header.split("\r\n"))
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))
                contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
        if (contentLength > 0) in.readNBytes(contentLength);
        String requestLine = requests.get(requests.size() - 1);
        paths.add(requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "");
        byte[] reply = body.getBytes(StandardCharsets.UTF_8);
        String headText = "HTTP/1.1 " + status + " Status\r\n"
                + "Content-Type: application/json\r\n"
                + (location == null ? "" : "Location: " + location + "\r\n")
                + "Content-Length: " + reply.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headText.getBytes(StandardCharsets.ISO_8859_1));
        out.write(reply);
        out.flush();
    }

    String origin() {
        return "https://127.0.0.1:" + server.getLocalPort();
    }

    void respond(int status, String body) {
        this.status = status;
        this.body = body;
    }

    void redirectTo(String location) {
        this.status = 302;
        this.location = location;
        this.body = "";
    }

    List<String> receivedRequests() {
        return List.copyOf(requests);
    }

    List<String> receivedPaths() {
        return List.copyOf(paths);
    }

    @Override
    public void close() throws Exception {
        server.close();
        executor.shutdownNow();
    }

    /** Test-only trust anchor: the platform store is never widened in production. */
    static javax.net.ssl.TrustManager[] trustFor(Path certPem) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(certPem));
        store.setCertificateEntry("center-test", certificate);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return factory.getTrustManagers();
    }

    private static SSLContext contextFor(Path certPem, Path keyPem) throws Exception {
        Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(Files.newInputStream(certPem));
        PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(
                new PKCS8EncodedKeySpec(pemBytes(Files.readString(keyPem))));
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setKeyEntry("center", key, new char[0], new Certificate[]{certificate});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, new char[0]);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, null);
        return context;
    }

    static byte[] pemBytes(String pem) {
        StringBuilder builder = new StringBuilder();
        for (String line : pem.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-----")) continue;
            builder.append(trimmed);
        }
        return Base64.getDecoder().decode(builder.toString());
    }
}
