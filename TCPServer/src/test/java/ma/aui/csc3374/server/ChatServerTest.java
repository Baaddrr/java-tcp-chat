package ma.aui.csc3374.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ChatServerTest {
    @Test
    void acceptsTwoSimultaneousClientsAndRemovesEachOnEof() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var server = new ChatServer("127.0.0.1", 0)) {
            Future<?> running = executor.submit(() -> { server.run(); return null; });
            try (Socket first = new Socket("127.0.0.1", server.port());
                 Socket second = new Socket("127.0.0.1", server.port())) {
                awaitCount(server, 2);
                first.close();
                awaitCount(server, 1);
                second.close();
                awaitCount(server, 0);
            }
            server.close();
            running.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownReleasesAcceptAndBothBlockedHandlers() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var server = new ChatServer("127.0.0.1", 0)) {
            Future<?> running = executor.submit(() -> { server.run(); return null; });
            try (Socket first = new Socket("127.0.0.1", server.port());
                 Socket second = new Socket("127.0.0.1", server.port())) {
                first.setSoTimeout(5000);
                second.setSoTimeout(5000);
                awaitCount(server, 2);
                server.close();
                server.close();
                running.get(5, TimeUnit.SECONDS);
                assertEquals(-1, first.getInputStream().read());
                assertEquals(-1, second.getInputStream().read());
                assertEquals(0, server.connectionCount());
            }
        }
    }

    @Test
    void shutdownWithNoClientsReleasesAccept() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var server = new ChatServer("127.0.0.1", 0)) {
            Future<?> running = executor.submit(() -> { server.run(); return null; });
            server.close();
            running.get(5, TimeUnit.SECONDS);
            assertEquals(0, server.connectionCount());
        }
    }

    @Test
    void resetClientDoesNotPreventLaterConnections() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var server = new ChatServer("127.0.0.1", 0)) {
            Future<?> running = executor.submit(() -> { server.run(); return null; });
            try (Socket reset = new Socket("127.0.0.1", server.port())) {
                awaitCount(server, 1);
                reset.setSoLinger(true, 0);
            }
            awaitCount(server, 0);
            try (Socket replacement = new Socket("127.0.0.1", server.port())) {
                awaitCount(server, 1);
            }
            awaitCount(server, 0);
            server.close();
            running.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void occupiedAddressFailsVisibly() throws Exception {
        try (var server = new ChatServer("127.0.0.1", 0)) {
            assertThrows(java.net.BindException.class, () -> new ChatServer("127.0.0.1", server.port()));
        }
    }

    private static void awaitCount(ChatServer server, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (server.connectionCount() != expected && System.nanoTime() < deadline) {
            // Poll an observed condition with a deadline, rather than assuming a scheduling delay.
            Thread.sleep(10);
        }
        assertEquals(expected, server.connectionCount());
    }
}
