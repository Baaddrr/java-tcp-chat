package ma.aui.csc3374.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ChatProtocolTest {
    @Test
    void broadcastsAuthoritativeSenderAndTimeToSenderPeerAndGuest() throws Exception {
        try (var chat = new RunningServer()) {
            Peer alice = chat.join(" Alice ");
            Peer bob = chat.join("Bob");
            Peer guest = chat.join("");
            assertEquals("Alice", alice.name);
            assertFalse(alice.readOnly);
            assertTrue(guest.readOnly);
            String text = "Hello Bob! | tab\t null\0\nSecond line: café 🚀";
            Instant before = Instant.now();
            alice.chat(text);
            Message sent = alice.message();
            assertEquals("Alice", sent.sender());
            assertEquals(text, sent.text());
            assertFalse(sent.time().isBefore(before));
            assertFalse(sent.time().isAfter(Instant.now()));
            assertEquals(sent, bob.message());
            assertEquals(sent, guest.message());
        }
    }

    @Test
    void serverRejectsGuestChatButGuestCanInquireAndReceive() throws Exception {
        try (var chat = new RunningServer()) {
            Peer alice = chat.join("Alice");
            Peer guest = chat.join("   ");
            Peer otherGuest = chat.join("");
            assertNotEquals(guest.name, otherGuest.name);
            guest.chat("I bypassed the interface");
            assertTrue(guest.error().contains("READ-ONLY MODE"));
            alice.noFrame();
            otherGuest.noFrame();
            guest.command("USERS");
            assertEquals(List.of("Alice", guest.name, otherGuest.name), guest.users());
            alice.chat("Guests can hear this");
            assertEquals("Guests can hear this", guest.message().text());
        }
    }

    @Test
    void userListIsSortedIncludesGuestsAndIsSentOnlyToRequester() throws Exception {
        try (var chat = new RunningServer()) {
            Peer zoe = chat.join("Zoe");
            Peer alice = chat.join("Alice");
            Peer guest = chat.join("");
            alice.command("USERS");
            assertEquals(List.of("Alice", guest.name, "Zoe"), alice.users());
            zoe.noFrame();
            guest.noFrame();
        }
    }

    @Test
    void duplicateNameIsRejectedWithoutRemovingOriginalAndCanBeReusedAfterLeave() throws Exception {
        try (var chat = new RunningServer()) {
            Peer original = chat.join("Alice");
            Peer duplicate = chat.connect();
            duplicate.sendJoin("alice");
            assertTrue(duplicate.error().contains("already in use"));
            assertEquals(-1, duplicate.input.read());
            Peer bob = chat.join("Bob");
            bob.chat("Original still receives");
            assertEquals("Original still receives", original.message().text());
            original.command("LEAVE");
            assertEquals(-1, original.input.read());
            await(() -> !chat.server.activeUsers().contains("Alice"));
            assertEquals("ALICE", chat.join("ALICE").name);
        }
    }

    @Test
    void simultaneousDuplicateJoinsHaveExactlyOneWinner() throws Exception {
        try (var chat = new RunningServer()) {
            Peer first = chat.connect();
            Peer second = chat.connect();
            CountDownLatch start = new CountDownLatch(1);
            Future<String> a = chat.executor.submit(() -> { start.await(); return first.joinOutcome("Alice"); });
            Future<String> b = chat.executor.submit(() -> { start.await(); return second.joinOutcome("alice"); });
            start.countDown();
            assertEquals(Set.of("WELCOME", "ERROR"), Set.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)));
            assertEquals(1, chat.server.activeUsers().size());
        }
    }

    @Test
    void resetRemovesIdentifiedClientAndAllowsNameReuse() throws Exception {
        try (var chat = new RunningServer()) {
            Peer alice = chat.join("Alice");
            Peer bob = chat.join("Bob");
            alice.socket.setSoLinger(true, 0);
            alice.close();
            await(() -> chat.server.activeUsers().equals(List.of("Bob")));
            Peer replacement = chat.join("Alice");
            bob.chat("After reset");
            assertEquals("After reset", replacement.message().text());
        }
    }

    @Test
    void validatesChatAtServerAndAcceptsMaximumUnicodeMessage() throws Exception {
        try (var chat = new RunningServer()) {
            Peer alice = chat.join("Alice");
            Peer bob = chat.join("Bob");
            for (String invalid : List.of(" ", "x".repeat(4097))) {
                alice.chat(invalid);
                assertTrue(alice.error().contains("4096"));
                bob.noFrame();
            }
            String maximum = "界".repeat(4096);
            alice.chat(maximum);
            assertEquals(maximum, alice.message().text());
            assertEquals(maximum, bob.message().text());
            alice.chat("allUsers");
            assertEquals("allUsers", bob.message().text());
        }
    }

    @Test
    void rejectsInvalidNamesAndOutOfOrderOrUnknownCommands() throws Exception {
        try (var chat = new RunningServer()) {
            for (String name : List.of("Guest-99", "guest-spoof", "x".repeat(33), "Ali\nce")) {
                Peer invalid = chat.connect();
                invalid.sendJoin(name);
                assertTrue(invalid.error().contains("Username"));
                assertEquals(-1, invalid.input.read());
            }
            Peer unjoined = chat.connect();
            unjoined.command("USERS");
            assertTrue(unjoined.error().contains("JOIN"));
            assertEquals(-1, unjoined.input.read());
            Peer forged = chat.join("Alice");
            forged.command("MESSAGE");
            assertEquals("Unknown command", forged.error());
            assertEquals(-1, forged.input.read());
            await(() -> chat.server.activeUsers().isEmpty());
        }
    }

    @Test
    void shutdownClosesRegisteredClientsAndClearsUsers() throws Exception {
        try (var chat = new RunningServer()) {
            Peer alice = chat.join("Alice");
            Peer bob = chat.join("Bob");
            Peer guest = chat.join("");
            chat.server.close();
            chat.running.get(5, TimeUnit.SECONDS);
            for (Peer peer : List.of(alice, bob, guest)) {
                assertEquals(-1, peer.input.read());
            }
            assertTrue(chat.server.activeUsers().isEmpty());
            assertEquals(0, chat.server.connectionCount());
        }
    }

    @Test
    void concurrentSendersDeliverCompleteUniqueFramesToAllThreeClients() throws Exception {
        try (var chat = new RunningServer()) {
            Peer alice = chat.join("Alice");
            Peer bob = chat.join("Bob");
            Peer guest = chat.join("");
            int each = 60;
            Set<String> expected = new HashSet<>();
            for (int i = 0; i < each; i++) {
                expected.add("Alice:A-" + i + "\n界🚀");
                expected.add("Bob:B-" + i + "\n界🚀");
            }
            List<Future<Set<String>>> received = new ArrayList<>();
            for (Peer peer : List.of(alice, bob, guest)) {
                received.add(chat.executor.submit(() -> {
                    Set<String> messages = new HashSet<>();
                    for (int i = 0; i < each * 2; i++) {
                        Message message = peer.message();
                        assertTrue(messages.add(message.sender() + ":" + message.text()), "Duplicate message");
                    }
                    return messages;
                }));
            }
            CountDownLatch start = new CountDownLatch(1);
            Future<?> a = chat.executor.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) alice.chat("A-" + i + "\n界🚀");
                return null;
            });
            Future<?> b = chat.executor.submit(() -> {
                start.await();
                for (int i = 0; i < each; i++) bob.chat("B-" + i + "\n界🚀");
                return null;
            });
            start.countDown();
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            for (Future<Set<String>> result : received) {
                assertEquals(expected, result.get(5, TimeUnit.SECONDS));
            }
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Server state did not converge before the deadline");
    }

    private record Message(String sender, Instant time, String text) { }

    private static final class Peer implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private String name;
        private boolean readOnly;

        private Peer(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(3000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        }

        private void sendJoin(String username) throws IOException {
            output.writeUTF("JOIN");
            output.writeUTF(username);
            output.flush();
        }

        private String joinOutcome(String username) throws IOException {
            sendJoin(username);
            String type = input.readUTF();
            if (type.equals("WELCOME")) {
                name = input.readUTF();
                readOnly = input.readBoolean();
            } else {
                assertEquals("ERROR", type);
                assertTrue(input.readUTF().contains("already in use"));
            }
            return type;
        }

        private void command(String type) throws IOException {
            output.writeUTF(type);
            output.flush();
        }

        private void chat(String text) throws IOException {
            output.writeUTF("CHAT");
            output.writeUTF(text);
            output.flush();
        }

        private Message message() throws IOException {
            assertEquals("MESSAGE", input.readUTF());
            return new Message(input.readUTF(), Instant.parse(input.readUTF()), input.readUTF());
        }

        private List<String> users() throws IOException {
            assertEquals("USERS", input.readUTF());
            int count = input.readInt();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < count; i++) names.add(input.readUTF());
            return names;
        }

        private String error() throws IOException {
            assertEquals("ERROR", input.readUTF());
            return input.readUTF();
        }

        private void noFrame() throws IOException {
            socket.setSoTimeout(200);
            try {
                assertThrows(SocketTimeoutException.class, input::readUTF);
            } finally {
                socket.setSoTimeout(3000);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private static final class RunningServer implements AutoCloseable {
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final ChatServer server = new ChatServer("127.0.0.1", 0);
        private final Future<?> running = executor.submit(() -> { server.run(); return null; });
        private final List<Peer> peers = new ArrayList<>();

        private RunningServer() throws IOException { }

        private Peer connect() throws IOException {
            Peer peer = new Peer(server.port());
            peers.add(peer);
            return peer;
        }

        private Peer join(String name) throws IOException {
            Peer peer = connect();
            assertEquals("WELCOME", peer.joinOutcome(name));
            return peer;
        }

        @Override
        public void close() throws Exception {
            try {
                for (Peer peer : peers) peer.close();
            } finally {
                server.close();
                try {
                    running.get(5, TimeUnit.SECONDS);
                } finally {
                    executor.close();
                }
            }
        }
    }
}
