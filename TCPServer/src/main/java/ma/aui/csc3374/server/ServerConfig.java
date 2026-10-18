package ma.aui.csc3374.server;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public record ServerConfig(String host, int port) {
    public ServerConfig {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("server.host must not be blank");
        }
        host = host.strip();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("server.port must be between 1 and 65535");
        }
    }

    public static ServerConfig load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        int port;
        try {
            port = Integer.parseInt(properties.getProperty("server.port", "").strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("server.port must be an integer in " + path, e);
        }
        return new ServerConfig(properties.getProperty("server.host", ""), port);
    }
}
