package ma.aui.csc3374.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ChatClientProtocolTest {
    @Test
    void joinsAndDecodesTypedMessagesUsersAndErrorsUntilEof() throws Exception {
        try (var wire = new WireServer()) {
            assertEquals(new ChatClient.Identity("Alice", false), wire.join(" Alice ", "Alice", false));
            List<ChatClient.Event> events = new ArrayList<>();
            Future<?> receiving = wire.executor.submit(() -> { wire.client.receive(events::add); return null; });
            Instant time = Instant.parse("2026-10-17T20:00:00Z");
            String text = "hello\n界🚀 | \0";
            wire.output.writeUTF("MESSAGE");
            wire.output.writeUTF("Bob");
            wire.output.writeUTF(time.toString());
            wire.output.writeUTF(text);
            wire.output.writeUTF("USERS");
            wire.output.writeInt(2);
            wire.output.writeUTF("Alice");
            wire.output.writeUTF("Bob");
            wire.output.writeUTF("ERROR");
            wire.output.writeUTF("A visible server error");
            wire.output.flush();
            wire.peer.shutdownOutput();
            receiving.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(new ChatClient.ChatMessage("Bob", time, text),
                    new ChatClient.UserList(List.of("Alice", "Bob")),
                    new ChatClient.ServerError("A visible server error")), events);
            assertEquals(-1, wire.input.read());
        }
    }

    @Test
    void rejectedJoinReportsReasonAndClosesConnection() throws Exception {
        try (var wire = new WireServer()) {
            Future<?> joining = wire.executor.submit(() -> wire.client.join("Alice"));
            assertEquals("JOIN", wire.input.readUTF());
            assertEquals("Alice", wire.input.readUTF());
            wire.output.writeUTF("ERROR");
            wire.output.writeUTF("Username already in use: Alice");
            wire.output.flush();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> joining.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("already in use"));
            assertEquals(-1, wire.input.read());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"end", "bye"})
    void textCommandsRequestUsersThenLeave(String command) throws Exception {
        try (var wire = new WireServer()) {
            wire.join("", "Guest-1", true);
            assertTrue(wire.client.submit("allUsers"));
            assertEquals("USERS", wire.input.readUTF());
            assertFalse(wire.client.submit(command));
            assertEquals("LEAVE", wire.input.readUTF());
            assertEquals(-1, wire.input.read());
        }
    }

    @Test
    void leaveApiWorksWithoutTextCommandAndIsIdempotent() throws Exception {
        try (var wire = new WireServer()) {
            wire.join("Alice", "Alice", false);
            wire.client.leave();
            wire.client.leave();
            assertEquals("LEAVE", wire.input.readUTF());
            assertEquals(-1, wire.input.read());
        }
    }

    @Test
    void guestAttemptIsTransmittedForServerAuthorizationAndLiteralCommandIsChat() throws Exception {
        try (var wire = new WireServer()) {
            assertTrue(wire.join("", "Guest-1", true).readOnly());
            wire.client.sendMessage("end");
            assertEquals("CHAT", wire.input.readUTF());
            assertEquals("end", wire.input.readUTF());
        }
    }

    @Test
    void oversizedMessageIsRejectedBeforeWritingAndValidSendStillWorks() throws Exception {
        try (var wire = new WireServer()) {
            wire.join("Alice", "Alice", false);
            assertThrows(IllegalArgumentException.class, () -> wire.client.sendMessage("界".repeat(4097)));
            String maximum = "界".repeat(4096);
            wire.client.sendMessage(maximum);
            assertEquals("CHAT", wire.input.readUTF());
            assertEquals(maximum, wire.input.readUTF());
        }
    }

    @Test
    void concurrentWritersOnOneClientCannotInterleaveFrames() throws Exception {
        try (var wire = new WireServer()) {
            wire.join("Alice", "Alice", false);
            int each = 60;
            Future<Set<String>> reading = wire.executor.submit(() -> {
                Set<String> messages = new HashSet<>();
                int inquiries = 0;
                for (int i = 0; i < each * 3; i++) {
                    String type = wire.input.readUTF();
                    switch (type) {
                        case "CHAT" -> assertTrue(messages.add(wire.input.readUTF()), "Duplicate frame");
                        case "USERS" -> inquiries++;
                        default -> fail("Corrupted command frame: " + type);
                    }
                }
                assertEquals(each, inquiries);
                return messages;
            });
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> writers = new ArrayList<>();
            for (String sender : List.of("first", "second")) {
                writers.add(wire.executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < each; i++) wire.client.sendMessage(sender + i + "\n界🚀");
                    return null;
                }));
            }
            writers.add(wire.executor.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) wire.client.requestUsers();
                return null;
            }));
            start.countDown();
            for (Future<?> writer : writers) writer.get(5, TimeUnit.SECONDS);
            Set<String> expected = new HashSet<>();
            for (String sender : List.of("first", "second")) {
                for (int i = 0; i < each; i++) expected.add(sender + i + "\n界🚀");
            }
            assertEquals(expected, reading.get(5, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "negative-count", "bad-time", "truncated-message"})
    void malformedServerFramesAreReportedAndCloseConnection(String scenario) throws Exception {
        try (var wire = new WireServer()) {
            wire.join("Alice", "Alice", false);
            switch (scenario) {
                case "unknown" -> wire.output.writeUTF("NOT_A_FRAME");
                case "negative-count" -> {
                    wire.output.writeUTF("USERS");
                    wire.output.writeInt(-1);
                }
                case "bad-time" -> {
                    wire.output.writeUTF("MESSAGE");
                    wire.output.writeUTF("Bob");
                    wire.output.writeUTF("invalid timestamp");
                    wire.output.writeUTF("hello");
                }
                case "truncated-message" -> {
                    wire.output.writeUTF("MESSAGE");
                    wire.output.writeUTF("Bob");
                }
                default -> fail("Unknown test scenario");
            }
            wire.output.flush();
            wire.peer.shutdownOutput();
            assertThrows(IOException.class, () -> wire.client.receive(event -> fail("Unexpected event: " + event)));
            assertEquals(-1, wire.input.read());
        }
    }

    @Test
    void closingDuringJoinReleasesBlockedHandshake() throws Exception {
        try (var wire = new WireServer()) {
            Future<?> joining = wire.executor.submit(() -> wire.client.join("Alice"));
            assertEquals("JOIN", wire.input.readUTF());
            assertEquals("Alice", wire.input.readUTF());
            wire.client.close();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> joining.get(5, TimeUnit.SECONDS));
            assertInstanceOf(IOException.class, failure.getCause());
        }
    }

    private static final class WireServer implements AutoCloseable {
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final ServerSocket listener;
        private final ChatClient client;
        private final Socket peer;
        private final DataInputStream input;
        private final DataOutputStream output;

        private WireServer() throws IOException {
            listener = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(3000);
            client = new ChatClient("127.0.0.1", listener.getLocalPort());
            peer = listener.accept();
            peer.setSoTimeout(3000);
            input = new DataInputStream(peer.getInputStream());
            output = new DataOutputStream(peer.getOutputStream());
        }

        private ChatClient.Identity join(String requested, String assigned, boolean readOnly) throws Exception {
            Future<ChatClient.Identity> joining = executor.submit(() -> client.join(requested));
            assertEquals("JOIN", input.readUTF());
            assertEquals(requested.strip(), input.readUTF());
            output.writeUTF("WELCOME");
            output.writeUTF(assigned);
            output.writeBoolean(readOnly);
            output.flush();
            return joining.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            try {
                client.close();
            } finally {
                try {
                    peer.close();
                } finally {
                    listener.close();
                    executor.close();
                }
            }
        }
    }
}
