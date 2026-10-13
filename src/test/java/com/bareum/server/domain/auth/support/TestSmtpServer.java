package com.bareum.server.domain.auth.support;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** A bounded SMTP protocol fixture. It does not deliver to an external inbox. */
public final class TestSmtpServer implements AutoCloseable {
    private final ServerSocket listener;
    private volatile Socket connection;
    private final Thread worker;
    private final CompletableFuture<String> message = new CompletableFuture<>();
    private final CompletableFuture<String> recipient = new CompletableFuture<>();

    public TestSmtpServer(boolean rejectRecipient, boolean stallGreeting) throws IOException {
        listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        listener.setSoTimeout(5000);
        worker = new Thread(() -> serve(rejectRecipient, stallGreeting), "smtp-test-fixture");
        worker.setDaemon(true);
        worker.start();
    }

    public int port() { return listener.getLocalPort(); }
    public String message() throws Exception { return message.get(5, TimeUnit.SECONDS); }
    public String recipient() throws Exception { return recipient.get(5, TimeUnit.SECONDS); }

    private void serve(boolean rejectRecipient, boolean stallGreeting) {
        try (Socket socket = listener.accept()) {
            connection = socket;
            socket.setSoTimeout(3000);
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.US_ASCII));
            if (stallGreeting) { in.readLine(); return; }
            reply(out, "220 test SMTP ready");
            String line;
            while ((line = in.readLine()) != null) {
                if (line.startsWith("EHLO") || line.startsWith("HELO")) {
                    reply(out, "250 test");
                } else if (line.startsWith("MAIL FROM:")) {
                    reply(out, "250 sender accepted");
                } else if (line.startsWith("RCPT TO:")) {
                    recipient.complete(line);
                    reply(out, rejectRecipient ? "550 recipient rejected" : "250 recipient accepted");
                } else if (line.equals("DATA")) {
                    reply(out, "354 end with dot");
                    StringBuilder body = new StringBuilder();
                    while ((line = in.readLine()) != null && !line.equals(".")) {
                        body.append(line).append("\r\n");
                    }
                    message.complete(body.toString());
                    reply(out, "250 queued");
                } else if (line.equals("QUIT")) {
                    reply(out, "221 bye");
                    return;
                } else { reply(out, "250 OK"); }
            }
        } catch (IOException exception) {
            message.completeExceptionally(exception);
            recipient.completeExceptionally(exception);
        }
    }

    private static void reply(BufferedWriter out, String value) throws IOException {
        out.write(value + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws Exception {
        listener.close();
        if (connection != null) { connection.close(); }
        worker.join(4000);
        if (worker.isAlive()) { throw new IllegalStateException("SMTP fixture did not stop"); }
    }
}
