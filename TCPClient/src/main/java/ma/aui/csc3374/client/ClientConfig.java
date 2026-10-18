package ma.aui.csc3374.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ClientConfig(String host, int port) {
    public ClientConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("server.host must not be blank");
        }
        host = host.strip();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 1 and 65535");
        }
    }

    public static ClientConfig load(Path path, String... args) throws IOException {
        if (args.length != 0 && args.length != 2) {
            throw new IllegalArgumentException("Usage: TCPClient [<ServerIPAddress> <PortNumber>]");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        String host = args.length == 2 ? args[0] : properties.getProperty("server.host", "");
        String portText = args.length == 2 ? args[1] : properties.getProperty("server.port", "");
        int port;
        try {
            port = Integer.parseInt(portText.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("server.port must be an integer (configuration or arguments)", e);
        }
        return new ClientConfig(host, port);
    }
}
