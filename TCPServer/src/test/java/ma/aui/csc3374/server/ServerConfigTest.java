package ma.aui.csc3374.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {
    @TempDir
    Path directory;

    @Test
    void readsExternalPropertiesAgainAfterTheyChange() throws IOException {
        Path file = directory.resolve("server.properties");
        Files.writeString(file, "server.host=127.0.0.1\nserver.port=3000\n");
        assertEquals(new ServerConfig("127.0.0.1", 3000), ServerConfig.load(file));
        Files.writeString(file, "server.host=0.0.0.0\nserver.port=4000\n");
        assertEquals(new ServerConfig("0.0.0.0", 4000), ServerConfig.load(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "65536", "abc", ""})
    void rejectsInvalidOrMissingPort(String port) throws IOException {
        Path file = directory.resolve("server.properties");
        Files.writeString(file, "server.host=127.0.0.1\nserver.port=" + port);
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
    }

    @Test
    void rejectsMissingHost() throws IOException {
        Path file = directory.resolve("server.properties");
        Files.writeString(file, "server.port=3000\n");
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(file));
    }

    @Test
    void reportsMissingFile() {
        assertThrows(IOException.class, () -> ServerConfig.load(directory.resolve("missing.properties")));
    }
}
