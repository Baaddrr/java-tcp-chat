package ma.aui.csc3374.client.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import ma.aui.csc3374.client.ChatClient;
import ma.aui.csc3374.client.ClientConfig;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class RelayApplication extends Application {
    private static final System.Logger LOG = System.getLogger(RelayApplication.class.getName());
    // Only pending crosses threads. All other lifecycle state belongs to the FX thread.
    private final AtomicReference<ChatClient> pending = new AtomicReference<>();
    private ClientConfig config;
    private Scene scene;
    private WelcomeView welcome;
    private Thread connecting;
    private ChatClient active;
    private ConnectedView connected;
    private boolean sending;
    private boolean leaving;
    private boolean stopped;

    @Override
    public void start(Stage stage) {
        try {
            ClientConfig settings = ClientConfig.load(
                    Path.of(System.getProperty("chat.client.config", "config/client.properties")),
                    getParameters().getRaw().toArray(String[]::new));
            open(stage, settings);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot load client configuration", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("RELAY — Configuration error");
            alert.setHeaderText("Cannot load client configuration");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
            Platform.exit();
        }
    }

    void open(Stage stage, ClientConfig settings) {
        config = settings;
        welcome = new WelcomeView(this::connect);
        scene = new Scene(welcome, 1200, 750);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("relay.css")).toExternalForm());
        stage.setTitle("RELAY");
        stage.setScene(scene);
        stage.setMinWidth(1040);
        stage.setMinHeight(680);
        stage.setOnCloseRequest(event -> stop());
        stage.show();
        Platform.runLater(welcome::focusUsername);
    }

    private void connect(String name) {
        if (stopped || connecting != null || active != null) {
            return;
        }
        welcome.setConnecting(true);
        connecting = Thread.ofVirtual().name("relay-connect").unstarted(() -> {
            Thread attempt = Thread.currentThread();
            ChatClient candidate = null;
            try {
                candidate = new ChatClient(config.host(), config.port());
                pending.set(candidate);
                if (attempt.isInterrupted()) {
                    close(candidate);
                    pending.compareAndSet(candidate, null);
                    return;
                }
                ChatClient.Identity identity = candidate.join(name);
                ChatClient joined = candidate;
                Platform.runLater(() -> accept(attempt, joined, identity));
            } catch (IOException | IllegalArgumentException e) {
                close(candidate);
                pending.compareAndSet(candidate, null);
                Platform.runLater(() -> {
                    if (!stopped && connecting == attempt) {
                        connecting = null;
                        welcome.setConnecting(false);
                        welcome.showError(connectionError(e));
                        welcome.focusUsername();
                        LOG.log(System.Logger.Level.WARNING, "Connection attempt failed", e);
                    }
                });
            }
        });
        connecting.start();
    }

    private void accept(Thread attempt, ChatClient client, ChatClient.Identity identity) {
        pending.compareAndSet(client, null);
        if (stopped || connecting != attempt) {
            closeAsync(client);
            return;
        }
        connecting = null;
        active = client;
        showConnected(client, identity);
    }

    /** Handoff point: the receiver has not started; this is the same accepted socket and identity. */
    private void showConnected(ChatClient client, ChatClient.Identity identity) {
        connected = new ConnectedView(config, identity, text -> send(text, true),
                () -> send("allUsers", false), () -> send("bye", false));
        scene.setRoot(connected);
        connected.focusComposer();
        Thread.ofVirtual().name("relay-receive").start(() -> {
            String status = "Server disconnected.";
            try {
                client.receive(event -> Platform.runLater(() -> {
                    if (active == client && !stopped) {
                        connected.receive(event);
                    }
                }));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Connection lost", e);
                status = "Connection lost. Please reconnect.";
            }
            String message = status;
            Platform.runLater(() -> {
                if (active == client && !stopped && !leaving) {
                    showOffline(message);
                }
            });
        });
    }

    private void send(String text, boolean fromComposer) {
        ChatClient client = active;
        if (client == null || sending) {
            return;
        }
        sending = true;
        leaving = ChatClient.isLeaveCommand(text);
        connected.showNotice("");
        connected.setSending(true);
        Thread.ofVirtual().name("relay-send").start(() -> {
            try {
                boolean stillConnected = client.submit(text);
                Platform.runLater(() -> {
                    if (active == client && !stopped) {
                        if (stillConnected) {
                            sending = false;
                            connected.finishSending(fromComposer, "");
                        } else {
                            showOffline("");
                        }
                    }
                });
            } catch (IllegalArgumentException e) {
                Platform.runLater(() -> {
                    if (active == client && !stopped) {
                        sending = false;
                        connected.finishSending(false, e.getMessage());
                    }
                });
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Command could not be sent", e);
                Platform.runLater(() -> {
                    if (active == client && !stopped) {
                        showOffline("Connection lost while sending.");
                    }
                });
            }
        });
    }

    private void showOffline(String message) {
        active = null;
        connected = null;
        sending = false;
        leaving = false;
        welcome.setConnecting(false);
        welcome.showOffline(message);
        scene.setRoot(welcome);
        welcome.focusUsername();
    }

    private static String connectionError(Exception failure) {
        String detail = failure.getMessage();
        if (detail != null && detail.startsWith("Username already in use:")) {
            return "Username already taken. Please choose another.";
        }
        if (failure instanceof IllegalArgumentException || (detail != null && detail.startsWith("Username must"))) {
            return "Use 1–32 characters, no control characters or Guest- prefix.";
        }
        if (failure instanceof SocketTimeoutException) {
            return "Server did not respond. Please try again.";
        }
        if (failure instanceof ConnectException || failure instanceof UnknownHostException) {
            return "Cannot connect to server. Please check the server and settings.";
        }
        return "Cannot join the server. Please try again.";
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        if (connecting != null) {
            connecting.interrupt(); // Interruptible virtual-thread socket I/O closes a pending connect/join.
        }
        closeAsync(pending.getAndSet(null));
        closeAsync(active);
        active = null;
    }

    private static void closeAsync(ChatClient client) {
        if (client != null) {
            // Brief non-daemon cleanup also finishes when the last JavaFX window closes.
            Thread.ofPlatform().name("relay-close").start(() -> close(client));
        }
    }

    private static void close(ChatClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot close client socket", e);
            }
        }
    }
}
