package com.example.gateway.traffic;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * A local port whose accept queue is full, so a new connection neither opens
 * nor fails: it hangs until the client gives up, like a backend behind a
 * firewall that drops packets. Linux drops the SYN that finds the queue full;
 * where the system refuses or completes it instead, {@link #hangs()} is false.
 */
final class HangingPort implements AutoCloseable {

    private static final int MAX_QUEUED = 16;

    private final ServerSocket server;
    private final List<Socket> queued = new ArrayList<>();
    private final boolean hangs;

    HangingPort() {
        try {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        hangs = fillAcceptQueue();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    boolean hangs() {
        return hangs;
    }

    @Override
    public void close() {
        queued.forEach(HangingPort::closeQuietly);
        closeQuietly(server);
    }

    private boolean fillAcceptQueue() {
        for (int i = 0; i < MAX_QUEUED; i++) {
            Socket socket = new Socket();
            try {
                socket.connect(server.getLocalSocketAddress(), 300);
                queued.add(socket);
            } catch (SocketTimeoutException full) {
                closeQuietly(socket);
                return true;
            } catch (IOException refused) {
                closeQuietly(socket);
                return false;
            }
        }
        return false;
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
