import ma.aui.csc3374.server.ChatServer;
import ma.aui.csc3374.server.ServerConfig;
import ma.aui.csc3374.server.ui.ServerApplication;

import javafx.application.Application;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

public final class TCPServer {
    public static void main(String[] args) {
        if (args.length == 0) {
            Application.launch(ServerApplication.class);
        } else if (args.length == 1 && args[0].equals("--console")) {
            runConsole();
        } else {
            System.err.println("Usage: TCPServer [--console]");
            System.exit(1);
        }
    }

    private static void runConsole() {
        try {
            Path path = Path.of(System.getProperty("chat.server.config", "config/server.properties"));
            ServerConfig config = ServerConfig.load(path);
            try (ChatServer server = new ChatServer(config.host(), config.port())) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(server), "server-shutdown"));
                System.out.println("Console chat server. Press Enter to stop.");
                Thread.startVirtualThread(() -> {
                    try {
                        new BufferedReader(new InputStreamReader(System.in)).readLine();
                    } catch (IOException e) {
                        System.err.println("Server console input failed: " + e.getMessage());
                    } finally {
                        stop(server);
                    }
                });
                server.run();
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Server failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void stop(ChatServer server) {
        try {
            server.close();
        } catch (IOException e) {
            System.err.println("Server cleanup failed: " + e.getMessage());
        }
    }
}
