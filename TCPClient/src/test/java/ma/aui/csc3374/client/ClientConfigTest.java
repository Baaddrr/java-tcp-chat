package ma.aui.csc3374.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ClientConfigTest {
    @TempDir
    Path directory;

    @Test
    void usesPropertiesUnlessBothArgumentsAreProvided() throws IOException {
        Path file = defaults();
        assertEquals(new ClientConfig("127.0.0.1", 3000), ClientConfig.load(file));
        assertEquals(new ClientConfig("localhost", 4000), ClientConfig.load(file, "localhost", "4000"));
    }

    @Test
    void overridesBeforeValidatingPropertyValues() throws IOException {
        Path file = directory.resolve("client.properties");
        Files.writeString(file, "server.host=\nserver.port=invalid\n");
        assertEquals(new ClientConfig("localhost", 3000), ClientConfig.load(file, "localhost", "3000"));
    }

    @Test
    void rejectsIncompleteOrExtraArguments() throws IOException {
        Path file = defaults();
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.load(file, "localhost"));
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.load(file, "localhost", "3000", "extra"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "65536", "abc", ""})
    void rejectsInvalidArgumentPort(String port) throws IOException {
        Path file = defaults();
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.load(file, "localhost", port));
    }

    @Test
    void rejectsBlankHost() throws IOException {
        Path file = defaults();
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.load(file, " ", "3000"));
    }

    @Test
    void rejectsInvalidPropertiesWithoutOverrides() throws IOException {
        Path file = directory.resolve("client.properties");
        Files.writeString(file, "server.port=3000\n");
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.load(file));
        Files.writeString(file, "server.host=localhost\nserver.port=bad\n");
        assertThrows(IllegalArgumentException.class, () -> ClientConfig.load(file));
    }

    @Test
    void reportsMissingFileEvenWithArguments() {
        assertThrows(IOException.class,
                () -> ClientConfig.load(directory.resolve("missing.properties"), "localhost", "3000"));
    }

    private Path defaults() throws IOException {
        return Files.writeString(directory.resolve("client.properties"), "server.host=127.0.0.1\nserver.port=3000\n");
    }
}
