package ma.aui.csc3374.client.ui;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import ma.aui.csc3374.client.ClientConfig;
import ma.aui.csc3374.client.ChatClient;
import ma.aui.csc3374.server.ChatServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Requires a graphical desktop, like the application itself; no headless success is assumed. */
@Timeout(15)
class RelayApplicationTest {
    private RelayApplication application;
    private Stage stage;

    @BeforeAll
    static void startJavaFx() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
    }

    @AfterEach
    void closeWindow() throws Exception {
        fx(() -> {
            if (application != null) {
                application.stop();
                stage.close();
            }
            return null;
        });
    }

    @Test
    void blankNameStaysOnWelcomeAndDoesNotOpenSocket() throws Exception {
        try (ServerSocket server = server()) {
            open(server.getLocalPort());
            enterName("   ");
            assertTrue(fx(() -> error().contains("Enter a username")));
            server.setSoTimeout(150);
            assertThrows(java.net.SocketTimeoutException.class, server::accept);
            assertFalse(fx(() -> button("join").isDisabled()));
        }
    }

    @Test
    void namedJoinWaitsForAcceptanceAndHandsOffBufferedMessageOnSameSocket() throws Exception {
        try (ServerSocket server = server()) {
            open(server.getLocalPort());
            enterName("  Alice  ");
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                DataInputStream input = new DataInputStream(peer.getInputStream());
                assertEquals("JOIN", input.readUTF());
                assertEquals("Alice", input.readUTF());
                assertTrue(fx(() -> button("join").isDisabled() && button("guest").isDisabled()
                        && field().isDisabled()));
                assertTrue(fx(() -> stage.getScene().getRoot() instanceof WelcomeView));
                DataOutputStream output = welcome(peer, "Alice", false);
                output.writeUTF("MESSAGE");
                output.writeUTF("Bob");
                output.writeUTF(Instant.now().toString());
                output.writeUTF("Hello before the view has switched");
                output.flush();
                await(() -> hasMessage(stage, "Bob", "Hello before the view has switched"));
                assertEquals("Named session · connected", fx(() -> label("connection-mode").getText()));
                fx(() -> { button("leave").fire(); return null; });
                assertEquals("LEAVE", input.readUTF());
                assertEquals(-1, input.read());
                await(() -> stage.getScene().getRoot() instanceof WelcomeView);
            }
        }
    }

    @Test
    void guestIgnoresTypedNameAndUsesServerReadOnlyIdentity() throws Exception {
        try (ServerSocket server = server()) {
            open(server.getLocalPort());
            fx(() -> { field().setText("Ignored"); button("guest").fire(); return null; });
            try (Socket peer = server.accept()) {
                DataInputStream input = new DataInputStream(peer.getInputStream());
                assertEquals("JOIN", input.readUTF());
                assertEquals("", input.readUTF());
                welcome(peer, "Visitor assigned by server", true);
                await(() -> stage.getScene().getRoot() instanceof ConnectedView);
                assertEquals("Connected as Visitor assigned by server", fx(() -> label("connected-identity").getText()));
                assertEquals("READ-ONLY MODE · guest access", fx(() -> label("connection-mode").getText()));
            }
        }
    }

    @Test
    void duplicateNameClosesRejectedSocketRetainsInputAndCanRetry() throws Exception {
        try (ServerSocket server = server()) {
            open(server.getLocalPort());
            enterName("Alice");
            try (Socket rejected = server.accept()) {
                rejected.setSoTimeout(3000);
                DataInputStream input = new DataInputStream(rejected.getInputStream());
                assertEquals("JOIN", input.readUTF());
                assertEquals("Alice", input.readUTF());
                DataOutputStream output = new DataOutputStream(rejected.getOutputStream());
                output.writeUTF("ERROR");
                output.writeUTF("Username already in use: Alice");
                output.flush();
                assertEquals(-1, input.read());
                await(() -> error().contains("Username already taken"));
                assertEquals("Alice", fx(() -> field().getText()));
                assertFalse(fx(() -> button("guest").isDisabled()));
            }
            enterName("Bob");
            try (Socket accepted = server.accept()) {
                DataInputStream input = new DataInputStream(accepted.getInputStream());
                assertEquals("JOIN", input.readUTF());
                assertEquals("Bob", input.readUTF());
                welcome(accepted, "Bob", false);
                await(() -> stage.getScene().getRoot() instanceof ConnectedView);
            }
        }
    }

    @Test
    void refusedConnectionRestoresControlsWithFriendlyError() throws Exception {
        int port;
        try (ServerSocket server = server()) {
            port = server.getLocalPort();
        }
        open(port);
        enterName("Alice");
        await(() -> error().contains("Cannot connect to server"));
        assertFalse(fx(() -> field().isDisabled() || button("join").isDisabled() || button("guest").isDisabled()));
        assertEquals("Alice", fx(() -> field().getText()));
    }

    @Test
    void closingDuringHandshakeClosesSocketAndNeverTransitions() throws Exception {
        try (ServerSocket server = server()) {
            open(server.getLocalPort());
            enterName("Alice");
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                DataInputStream input = new DataInputStream(peer.getInputStream());
                assertEquals("JOIN", input.readUTF());
                assertEquals("Alice", input.readUTF());
                fx(() -> { application.stop(); stage.close(); return null; });
                assertEquals(-1, input.read());
                assertTrue(fx(() -> stage.getScene().getRoot() instanceof WelcomeView));
            }
        }
    }

    @Test
    void closingConnectedWindowClosesSocket() throws Exception {
        try (ServerSocket server = server()) {
            open(server.getLocalPort());
            enterName("Alice");
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                DataInputStream input = new DataInputStream(peer.getInputStream());
                assertEquals("JOIN", input.readUTF());
                assertEquals("Alice", input.readUTF());
                welcome(peer, "Alice", false);
                await(() -> stage.getScene().getRoot() instanceof ConnectedView);
                fx(() -> { application.stop(); stage.close(); return null; });
                assertEquals(-1, input.read());
            }
        }
    }

    @Test
    void serverDisconnectReturnsToWelcomeWithExplanation() throws Exception {
        try (ServerSocket server = server()) {
            open(server.getLocalPort());
            enterName("Alice");
            try (Socket peer = server.accept()) {
                DataInputStream input = new DataInputStream(peer.getInputStream());
                assertEquals("JOIN", input.readUTF());
                assertEquals("Alice", input.readUTF());
                welcome(peer, "Alice", false);
                await(() -> stage.getScene().getRoot() instanceof ConnectedView);
            }
            await(() -> stage.getScene().getRoot() instanceof WelcomeView && error().contains("Server disconnected"));
            assertNotNull(fx(() -> label("welcome-error").getGraphic()));
            assertTrue(fx(() -> error().startsWith("Offline")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"end", "bye"})
    void composerSendsByButtonAndEnterAndLeavesOnTextCommand(String command) throws Exception {
        try (ServerSocket server = server()) {
            open(server.getLocalPort());
            enterName("Alice");
            try (Socket peer = server.accept()) {
                peer.setSoTimeout(3000);
                DataInputStream input = new DataInputStream(peer.getInputStream());
                assertEquals("JOIN", input.readUTF());
                assertEquals("Alice", input.readUTF());
                welcome(peer, "Alice", false);
                await(() -> stage.getScene().getRoot() instanceof ConnectedView);
                assertEquals("Online", fx(() -> label("connection-status").getText()));
                assertNotNull(fx(() -> label("connection-status").getGraphic()));

                fx(() -> {
                    composer(stage).setText("Message by button");
                    button("send").fire();
                    return null;
                });
                assertEquals("CHAT", input.readUTF());
                assertEquals("Message by button", input.readUTF());
                await(() -> composer(stage).getText().isEmpty());
                sendByEnter("Message by Enter");
                assertEquals("CHAT", input.readUTF());
                assertEquals("Message by Enter", input.readUTF());
                await(() -> composer(stage).getText().isEmpty());
                sendByEnter("allUsers");
                assertEquals("USERS", input.readUTF());
                await(() -> composer(stage).getText().isEmpty());
                sendByEnter(command);
                assertEquals("LEAVE", input.readUTF());
                assertEquals(-1, input.read());
                await(() -> stage.getScene().getRoot() instanceof WelcomeView && error().equals("Offline"));
            }
        }
    }

    @Test
    void namedAndGuestViewsReceiveFromRealServerAndCleanUpTheirSessions() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor();
             var server = new ChatServer("127.0.0.1", 0)) {
            var running = executor.submit(() -> { server.run(); return null; });
            open(server.port());
            enterName("Alice");
            await(() -> stage.getScene().getRoot() instanceof ConnectedView);
            RelayApplication guestApplication = new RelayApplication();
            Stage guestStage = fx(() -> {
                Stage window = new Stage();
                guestApplication.open(window, new ClientConfig("127.0.0.1", server.port()));
                window.hide();
                ((TextField) window.getScene().lookup("#username")).setText("This must be ignored");
                ((Button) window.getScene().lookup("#guest")).fire();
                return window;
            });
            try {
                await(() -> guestStage.getScene().getRoot() instanceof ConnectedView);
                try (ChatClient bob = new ChatClient("127.0.0.1", server.port())) {
                    bob.join("Bob");
                    assertEquals(java.util.List.of("Alice", "Bob", "Guest-1"), server.activeUsers());
                    bob.sendMessage("Real server broadcast to named and guest views");
                    await(() -> hasMessage(stage, "Bob", "Real server broadcast to named and guest views"));
                    await(() -> hasMessage(guestStage, "Bob", "Real server broadcast to named and guest views"));
                    assertEquals("READ-ONLY MODE · guest access", fx(() ->
                            ((Label) guestStage.getScene().lookup("#connection-mode")).getText()));
                    assertTrue(fx(() -> composer(guestStage).isDisabled()
                            && guestStage.getScene().lookup("#send").isDisabled()));
                    fx(() -> { ((Button) guestStage.getScene().lookup("#refresh-users")).fire(); return null; });
                    await(() -> ((ListView<?>) guestStage.getScene().lookup("#participants"))
                            .getItems().equals(java.util.List.of("Alice", "Bob", "Guest-1")));
                    assertTrue(fx(() -> ((ListView<?>) stage.getScene().lookup("#participants")).getItems().isEmpty()));
                    fx(() -> { button("leave").fire(); return null; });
                    await(() -> !server.activeUsers().contains("Alice"));
                    fx(() -> { guestApplication.stop(); guestStage.close(); return null; });
                    await(() -> server.activeUsers().equals(java.util.List.of("Bob")));
                    bob.leave();
                }
                await(() -> server.activeUsers().isEmpty() && server.connectionCount() == 0);
            } finally {
                fx(() -> { guestApplication.stop(); guestStage.close(); return null; });
                server.close();
            }
            running.get(5, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1024, 1200})
    void welcomeControlsFitAtMinimumAndReferenceSizes(int width) throws Exception {
            fx(() -> {
                var root = new WelcomeView(name -> fail("Blank input must not connect"));
                var renderedScene = new Scene(root, width, width == 1200 ? 750 : 640);
                renderedScene.getStylesheets().add(getClass().getResource("relay.css").toExternalForm());
                root.resize(width, width == 1200 ? 750 : 640);
                root.applyCss();
                root.layout();
                assertEquals(62, ((Label) root.lookup(".brand")).getFont().getSize());
                var card = root.lookup(".welcome-card");
                var cardBounds = card.localToScene(card.getBoundsInLocal());
                for (var control : root.lookupAll(".label")) {
                    Label label = (Label) control;
                    assertTrue(label.getHeight() >= label.prefHeight(-1) - 1,
                            "Clipped text: " + label.getText());
                    assertTrue(cardBounds.contains(label.localToScene(label.getLayoutBounds())),
                            "Text outside card: " + label.getText());
                }
                Button join = (Button) root.lookup("#join");
                double joinY = join.localToScene(0, 0).getY();
                join.fire();
                root.layout();
                assertEquals(joinY, join.localToScene(0, 0).getY(),
                        "Inline validation must not move the form");
                ((WelcomeView) root).showError("");
                var snapshot = root.snapshot(null, null);
                BufferedImage png = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < png.getHeight(); y++) {
                    for (int x = 0; x < png.getWidth(); x++) {
                        png.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                    }
                }
                ImageIO.write(png, "png", Path.of("target", "relay-welcome-" + width + ".png").toFile());
                return null;
            });
    }

    private void open(int port) throws Exception {
        fx(() -> {
            application = new RelayApplication();
            stage = new Stage();
            application.open(stage, new ClientConfig("127.0.0.1", port));
            stage.hide();
            return null;
        });
    }

    private void enterName(String name) throws Exception {
        fx(() -> {
            field().setText(name);
            field().fireEvent(new ActionEvent());
            return null;
        });
    }

    private void sendByEnter(String text) throws Exception {
        fx(() -> {
            TextArea input = composer(stage);
            input.setText(text);
            input.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER,
                    false, false, false, false));
            return null;
        });
    }

    private static TextArea composer(Stage window) {
        return (TextArea) window.getScene().lookup("#composer");
    }

    private static boolean hasMessage(Stage window, String sender, String text) {
        return window.getScene().lookup("#received-messages") instanceof ListView<?> messages
                && messages.getItems().stream().anyMatch(item -> item instanceof ChatClient.ChatMessage message
                && message.sender().equals(sender) && message.text().equals(text) && message.timestamp() != null);
    }

    private TextField field() { return (TextField) stage.getScene().lookup("#username"); }
    private Button button(String id) { return (Button) stage.getScene().lookup("#" + id); }
    private Label label(String id) { return (Label) stage.getScene().lookup("#" + id); }
    private String error() { return label("welcome-error").getText(); }

    private static ServerSocket server() throws Exception {
        ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        server.setSoTimeout(3000);
        return server;
    }

    private static DataOutputStream welcome(Socket peer, String name, boolean readOnly) throws Exception {
        DataOutputStream output = new DataOutputStream(peer.getOutputStream());
        output.writeUTF("WELCOME");
        output.writeUTF(name);
        output.writeBoolean(readOnly);
        output.flush();
        return output;
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action);
        Platform.runLater(result);
        return result.get(5, TimeUnit.SECONDS);
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(condition)) {
            if (System.nanoTime() >= deadline) {
                fail("JavaFX state did not reach the expected condition");
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }
}
