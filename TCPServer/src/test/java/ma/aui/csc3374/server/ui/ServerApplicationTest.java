package ma.aui.csc3374.server.ui;

import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.stage.Stage;
import ma.aui.csc3374.server.ChatServer;
import ma.aui.csc3374.server.ServerConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ServerApplicationTest {
    @BeforeAll
    static void initializeJavaFx() throws Exception {
        var ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
    }

    @Test
    void dashboardTracksJoinedUsersColorsAndLogsThenStopsOpenSocket() throws Exception {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 50, loopback)) {
            port = reservation.getLocalPort();
        }
        var application = new ServerApplication();
        Stage stage = open(application, port);
        try {
            await(() -> messages(stage).contains("Waiting for Client"));
            assertEquals("Online", fx(() -> status(stage)));
            assertEquals("127.0.0.1:" + port,
                    fx(() -> ((Label) stage.getScene().lookup("#server-endpoint")).getText()));
            try (Socket alice = join(loopback, port, "Alice"); Socket bob = join(loopback, port, "Bob")) {
                await(() -> users(stage).equals(List.of("Alice", "Bob"))
                        && messages(stage).containsAll(List.of("Welcome Alice", "Welcome Bob")));
                assertTrue(fx(() -> {
                    var root = stage.getScene().getRoot();
                    root.applyCss();
                    root.layout();
                    List<String> styles = root.lookupAll("#server-users .list-cell").stream()
                            .map(node -> (ListCell<?>) node)
                            .filter(cell -> !cell.isEmpty())
                            .map(ListCell::getStyle)
                            .distinct().toList();
                    return styles.size() == 2 && styles.stream().allMatch(style -> style.contains("-fx-background-color"));
                }));
                var output = new DataOutputStream(bob.getOutputStream());
                output.writeUTF("LEAVE");
                output.flush();
                await(() -> users(stage).equals(List.of("Alice"))
                        && messages(stage).contains("Client disconnected: Bob"));
                fx(() -> {
                    ((Button) stage.getScene().lookup("#stop-server")).fire();
                    return null;
                });
                assertEquals(-1, alice.getInputStream().read());
                await(() -> status(stage).equals("Offline") && users(stage).isEmpty()
                        && messages(stage).contains("Server Stopped"));
            }
        } finally {
            close(application, stage);
        }
    }

    @Test
    void occupiedPortShowsFailureAndRemainsOffline() throws Exception {
        try (ServerSocket owner = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            var application = new ServerApplication();
            Stage stage = open(application, owner.getLocalPort());
            try {
                await(() -> status(stage).equals("Offline")
                        && messages(stage).stream().anyMatch(message -> message.startsWith("Server failed:")));
                assertTrue(fx(() -> stage.getScene().lookup("#stop-server").isDisabled()));
            } finally {
                close(application, stage);
            }
        }
    }

    private static Socket join(InetAddress host, int port, String username) throws Exception {
        Socket socket = new Socket(host, port);
        try {
            socket.setSoTimeout(3000);
            var output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF("JOIN");
            output.writeUTF(username);
            output.flush();
            var input = new DataInputStream(socket.getInputStream());
            assertEquals("WELCOME", input.readUTF());
            assertEquals(username, input.readUTF());
            assertFalse(input.readBoolean());
            return socket;
        } catch (Exception | AssertionError failure) {
            try {
                socket.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static Stage open(ServerApplication application, int port) throws Exception {
        return fx(() -> {
            var stage = new Stage();
            application.open(stage, new ServerConfig("127.0.0.1", port));
            return stage;
        });
    }

    private static void close(ServerApplication application, Stage stage) throws Exception {
        fx(() -> {
            application.stop();
            stage.close();
            return null;
        });
    }

    private static String status(Stage stage) {
        return ((Label) stage.getScene().lookup("#server-status")).getText();
    }

    private static List<?> users(Stage stage) {
        return ((ListView<?>) stage.getScene().lookup("#server-users")).getItems();
    }

    private static List<String> messages(Stage stage) {
        return ((ListView<?>) stage.getScene().lookup("#server-activity")).getItems().stream()
                .map(item -> ((ChatServer.Activity) item).message()).toList();
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        var result = new FutureTask<>(action);
        Platform.runLater(result);
        return result.get(5, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(condition)) {
            if (System.nanoTime() >= deadline) {
                fail("Dashboard did not reach the expected state");
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }
}
