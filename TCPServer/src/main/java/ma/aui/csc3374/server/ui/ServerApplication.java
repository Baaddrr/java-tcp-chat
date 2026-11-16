package ma.aui.csc3374.server.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import ma.aui.csc3374.server.ChatServer;
import ma.aui.csc3374.server.ServerConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ServerApplication extends Application {
    private static final System.Logger LOG = System.getLogger(ServerApplication.class.getName());

    private final AtomicReference<ChatServer> active = new AtomicReference<>();
    private volatile boolean closing;
    private ServerView view;

    @Override
    public void start(Stage stage) {
        try {
            open(stage, ServerConfig.load(Path.of(System.getProperty(
                    "chat.server.config", "config/server.properties"))));
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.ERROR, "Cannot load server configuration", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("RELAY Server");
            alert.setHeaderText("Cannot load server configuration");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
            Platform.exit();
        }
    }

    void open(Stage stage, ServerConfig config) {
        view = new ServerView(config, this::requestStop);
        Scene scene = new Scene(view, 1040, 690);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("server.css")).toExternalForm());
        stage.setTitle("RELAY Server");
        stage.setScene(scene);
        stage.setMinWidth(760);
        stage.setMinHeight(540);
        stage.setOnCloseRequest(event -> stop());
        stage.show();
        Thread.ofPlatform().name("relay-server").start(() -> runServer(config));
    }

    private void runServer(ServerConfig config) {
        try (ChatServer server = new ChatServer(config.host(), config.port(), this::recordActivity)) {
            active.set(server);
            // Window closure can race with bind, before stop() has a server to close.
            if (closing) {
                return;
            }
            Platform.runLater(() -> {
                if (!closing) {
                    view.setStatus("Online", true);
                }
            });
            server.run();
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(System.Logger.Level.ERROR, "Server failed", e);
            recordActivity(new ChatServer.Activity(Instant.now(), "Server failed: " + e.getMessage()));
        } finally {
            active.set(null);
            Platform.runLater(() -> {
                if (!closing) {
                    view.setUsers(List.of());
                    view.setStatus("Offline", false);
                }
            });
        }
    }

    private void recordActivity(ChatServer.Activity activity) {
        Platform.runLater(() -> {
            if (!closing) {
                view.append(activity);
                ChatServer server = active.get();
                view.setUsers(server == null ? List.of() : server.activeUsers());
            }
        });
    }

    private void requestStop() {
        view.setStatus("Stopping…", false);
        closeAsync(active.get());
    }

    @Override
    public void stop() {
        if (!closing) {
            closing = true;
            closeAsync(active.get());
        }
    }

    private void closeAsync(ChatServer server) {
        if (server != null) {
            Thread.ofPlatform().name("relay-server-stop").start(() -> {
                try {
                    server.close();
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.ERROR, "Cannot close all server sockets", e);
                    recordActivity(new ChatServer.Activity(Instant.now(), "Shutdown error: " + e.getMessage()));
                }
            });
        }
    }
}
