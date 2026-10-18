package ma.aui.csc3374.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ChatClientTest {
    @Test
    void closeNotifiesPeerAndReleasesLocalRead() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            try (var client = new ChatClient("127.0.0.1", listener.getLocalPort());
                 Socket peer = listener.accept()) {
                peer.setSoTimeout(5000);
                Future<?> waiting = executor.submit(() -> { client.awaitDisconnect(); return null; });
                client.close();
                client.close();
                waiting.get(5, TimeUnit.SECONDS);
                assertEquals(-1, peer.getInputStream().read());
            }
        }
    }

    @Test
    void remoteCloseReleasesClientRead() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            try (var client = new ChatClient("127.0.0.1", listener.getLocalPort());
                 Socket peer = listener.accept()) {
                Future<?> waiting = executor.submit(() -> { client.awaitDisconnect(); return null; });
                peer.close();
                waiting.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void remoteResetIsReportedAsAnIoFailure() throws Exception {
        try (var listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            listener.setSoTimeout(5000);
            try (var client = new ChatClient("127.0.0.1", listener.getLocalPort());
                 Socket peer = listener.accept()) {
                peer.setSoLinger(true, 0);
                peer.close();
                assertThrows(java.io.IOException.class, client::awaitDisconnect);
            }
        }
    }

    @Test
    void refusedConnectionIsReported() throws Exception {
        int unusedPort;
        try (var listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = listener.getLocalPort();
        }
        assertThrows(java.net.ConnectException.class, () -> new ChatClient("127.0.0.1", unusedPort));
    }
}
