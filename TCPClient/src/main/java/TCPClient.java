import ma.aui.csc3374.client.ChatClient;
import ma.aui.csc3374.client.ClientConfig;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Arrays;

public final class TCPClient {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--console")) {
            runConsole(Arrays.copyOfRange(args, 1, args.length));
        } else {
            RelayClient.main(args);
        }
    }

    private static void runConsole(String[] args) {
        try {
            Path path = Path.of(System.getProperty("chat.client.config", "config/client.properties"));
            ClientConfig config = ClientConfig.load(path, args);
            BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
            System.out.println("Username (blank for read-only):");
            String username = console.readLine();
            if (username == null) {
                return;
            }
            try (ChatClient client = new ChatClient(config.host(), config.port())) {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> disconnect(client), "client-shutdown"));
                ChatClient.Identity identity = client.join(username);
                System.out.printf("Connected as %s%s to %s:%d.%n", identity.username(),
                        identity.readOnly() ? " (READ-ONLY MODE)" : "", config.host(), config.port());
                System.out.println("Type a message, allUsers, end, or bye.");
                Thread.startVirtualThread(() -> {
                    try {
                        String line;
                        while ((line = console.readLine()) != null) {
                            try {
                                if (!client.submit(line)) {
                                    break;
                                }
                            } catch (IllegalArgumentException e) {
                                System.err.println(e.getMessage());
                            }
                        }
                    } catch (IOException e) {
                        System.err.println("Client input/send failed: " + e.getMessage());
                    } finally {
                        disconnect(client);
                    }
                });
                client.receive(event -> {
                    switch (event) {
                        case ChatClient.ChatMessage message -> System.out.printf("[%s] %s: %s%n",
                                message.timestamp(), message.sender(), message.text());
                        case ChatClient.UserList users -> System.out.println("Active users: " + String.join(", ", users.usernames()));
                        case ChatClient.ServerError error -> System.err.println("Server: " + error.text());
                    }
                });
                System.out.println("Disconnected.");
            }
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Client failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void disconnect(ChatClient client) {
        try {
            client.close();
        } catch (IOException e) {
            System.err.println("Client cleanup failed: " + e.getMessage());
        }
    }
}
