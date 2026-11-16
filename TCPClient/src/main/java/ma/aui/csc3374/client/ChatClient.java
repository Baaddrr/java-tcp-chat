package ma.aui.csc3374.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ChatClient implements AutoCloseable {
    public record Identity(String username, boolean readOnly) { }

    public sealed interface Event permits ChatMessage, UserList, ServerError { }
    public record ChatMessage(String sender, Instant timestamp, String text) implements Event { }
    public record UserList(List<String> usernames) implements Event {
        public UserList {
            usernames = List.copyOf(usernames);
        }
    }
    public record ServerError(String text) implements Event { }

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Object outputLock = new Object();
    private volatile boolean joined;

    public ChatClient(String host, int port) throws IOException {
        socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), 5000);
            input = new DataInputStream(socket.getInputStream());
            output = new DataOutputStream(socket.getOutputStream());
        } catch (IOException | RuntimeException e) {
            closeAfterFailure(e);
            throw e;
        }
    }

    /** Call once, before starting receive(). An empty username requests read-only access. */
    public Identity join(String username) throws IOException {
        String requested = Objects.requireNonNull(username).strip();
        if (requested.length() > 32) {
            throw new IllegalArgumentException("Username must not exceed 32 characters");
        }
        synchronized (outputLock) {
            if (joined) {
                throw new IllegalStateException("Already joined");
            }
            try {
                socket.setSoTimeout(5000);
                output.writeUTF("JOIN");
                output.writeUTF(requested);
                output.flush();
                String reply = input.readUTF();
                if (reply.equals("ERROR")) {
                    throw new IOException(input.readUTF());
                }
                if (!reply.equals("WELCOME")) {
                    throw new IOException("Expected WELCOME, received " + reply);
                }
                Identity identity = new Identity(input.readUTF(), input.readBoolean());
                socket.setSoTimeout(0);
                joined = true;
                return identity;
            } catch (IOException | RuntimeException e) {
                closeAfterFailure(e);
                throw e;
            }
        }
    }

    public void sendMessage(String text) throws IOException {
        Objects.requireNonNull(text);
        // Validate before writing the command so an oversized writeUTF cannot leave a partial frame.
        if (text.length() > 4096) {
            throw new IllegalArgumentException("Chat messages must not exceed 4096 characters");
        }
        writeCommand("CHAT", text);
    }

    public void requestUsers() throws IOException {
        writeCommand("USERS", null);
    }

    /** Interprets the assignment's text commands; false means the user requested disconnection. */
    public boolean submit(String text) throws IOException {
        if (isLeaveCommand(text)) {
            leave();
            return false;
        }
        if (text.strip().equals("allUsers")) {
            requestUsers();
        } else {
            sendMessage(text);
        }
        return true;
    }

    public static boolean isLeaveCommand(String text) {
        return switch (text.strip()) {
            case "end", "bye" -> true;
            default -> false;
        };
    }

    public void leave() throws IOException {
        try {
            if (joined && !socket.isClosed()) {
                writeCommand("LEAVE", null);
            }
        } finally {
            close();
        }
    }

    private void writeCommand(String command, String argument) throws IOException {
        synchronized (outputLock) {
            if (!joined) {
                throw new IllegalStateException("Join before sending commands");
            }
            try {
                output.writeUTF(command);
                if (argument != null) {
                    output.writeUTF(argument);
                }
                output.flush();
            } catch (IOException e) {
                closeAfterFailure(e);
                throw e;
            }
        }
    }

    /** Blocking receive loop. Use exactly one reader; callbacks run on the calling thread. */
    public void receive(Consumer<Event> listener) throws IOException {
        Objects.requireNonNull(listener);
        try {
            while (!socket.isClosed()) {
                String type;
                try {
                    type = input.readUTF();
                } catch (EOFException e) {
                    break;
                }
                Event event = switch (type) {
                    case "MESSAGE" -> readMessage();
                    case "USERS" -> readUsers();
                    case "ERROR" -> new ServerError(input.readUTF());
                    default -> throw new IOException("Unknown server frame: " + type);
                };
                listener.accept(event);
            }
        } catch (IOException e) {
            if (!socket.isClosed()) {
                throw e;
            }
            // Local close intentionally releases the blocking read.
        } finally {
            close();
        }
    }

    private ChatMessage readMessage() throws IOException {
        String sender = input.readUTF();
        String timestamp = input.readUTF();
        String text = input.readUTF();
        try {
            return new ChatMessage(sender, Instant.parse(timestamp), text);
        } catch (DateTimeParseException e) {
            throw new IOException("Invalid server timestamp: " + timestamp, e);
        }
    }

    private UserList readUsers() throws IOException {
        int count = input.readInt();
        if (count < 0) {
            throw new IOException("Invalid user count: " + count);
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            names.add(input.readUTF());
        }
        return new UserList(names);
    }

    /** Transport lifecycle helper retained from Phase 1; discards received events. */
    public void awaitDisconnect() throws IOException {
        receive(event -> { });
    }

    private void closeAfterFailure(Exception failure) {
        try {
            close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
