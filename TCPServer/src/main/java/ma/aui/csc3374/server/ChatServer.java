package ma.aui.csc3374.server;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ChatServer implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ChatServer.class.getName());
    public record Activity(Instant timestamp, String message) { }

    private final ServerSocket listener;
    private final Consumer<Activity> observer;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final Object lifecycle = new Object();
    // Registration and shutdown share this lock so no accepted socket escapes shutdown.
    private final Set<Socket> clients = new HashSet<>();
    private final Map<String, Session> users = new HashMap<>();
    private long nextGuest = 1;
    private boolean closed;

    public ChatServer(String host, int port) throws IOException {
        this(host, port, activity -> { });
    }

    /** The observer is called by network threads, outside the lifecycle lock. */
    public ChatServer(String host, int port, Consumer<Activity> observer) throws IOException {
        this.observer = Objects.requireNonNull(observer);
        listener = new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(host, port));
        } catch (IOException | RuntimeException e) {
            try {
                listener.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            handlers.shutdown();
            throw e;
        }
    }

    public int port() {
        return listener.getLocalPort();
    }

    public int connectionCount() {
        synchronized (lifecycle) {
            return clients.size();
        }
    }

    public List<String> activeUsers() {
        synchronized (lifecycle) {
            return users.values().stream().map(session -> session.username)
                    .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }

    /** Runs the accept loop once; returns after close() has stopped all handlers. */
    public void run() throws IOException {
        logActivity("Server Started on " + listener.getLocalSocketAddress());
        logActivity("Waiting for Client");
        try {
            while (!listener.isClosed()) {
                Socket client;
                try {
                    client = listener.accept();
                } catch (SocketException e) {
                    if (listener.isClosed()) {
                        break;
                    }
                    throw e;
                }
                synchronized (lifecycle) {
                    if (closed) {
                        client.close();
                    } else {
                        clients.add(client);
                        handlers.execute(() -> handle(client));
                    }
                }
            }
        } finally {
            try {
                close();
            } finally {
                handlers.close();
                logActivity("Server Stopped");
            }
        }
    }

    private void handle(Socket client) {
        Session session = null;
        logActivity("Client connected: " + client.getRemoteSocketAddress());
        try (client) {
            session = new Session(client);
            DataInputStream input = new DataInputStream(client.getInputStream());
            client.setSoTimeout(5000);
            String first = readCommand(input);
            if (first == null) {
                return;
            }
            if (!first.equals("JOIN")) {
                session.error("JOIN must be the first command");
                return;
            }
            if (!register(session, input.readUTF())) {
                return;
            }
            client.setSoTimeout(0);
            logActivity("Welcome " + session.username + (session.readOnly ? " (read-only)" : ""));
            String command;
            while ((command = readCommand(input)) != null) {
                switch (command) {
                    case "CHAT" -> {
                        String text = input.readUTF();
                        if (session.readOnly) {
                            session.error("READ-ONLY MODE: guests cannot send chat messages");
                        } else if (text.isBlank() || text.length() > 4096) {
                            session.error("Chat messages must contain 1..4096 characters and not be blank");
                        } else {
                            broadcast(session.username, text);
                        }
                    }
                    case "USERS" -> session.userList(activeUsers());
                    case "LEAVE" -> { return; }
                    default -> {
                        session.error("Unknown command");
                        return;
                    }
                }
            }
        } catch (IOException e) {
            boolean stopping;
            synchronized (lifecycle) {
                stopping = closed;
            }
            if (!stopping) {
                String message = "Client connection failed: " + client.getRemoteSocketAddress();
                LOG.log(System.Logger.Level.WARNING, message, e);
                notifyObserver(message);
            }
        } finally {
            synchronized (lifecycle) {
                clients.remove(client);
                if (session != null && session.username != null) {
                    users.remove(key(session.username), session);
                }
            }
            String name = session == null ? null : session.username;
            logActivity("Client disconnected: " + (name == null ? client.getRemoteSocketAddress() : name));
        }
    }

    private static String readCommand(DataInputStream input) throws IOException {
        try {
            return input.readUTF();
        } catch (EOFException e) {
            return null;
        }
    }

    private boolean register(Session session, String requestedName) throws IOException {
        String name = requestedName.strip();
        boolean guest = name.isEmpty();
        if (!guest && (name.length() > 32 || name.codePoints().anyMatch(Character::isISOControl)
                || name.regionMatches(true, 0, "Guest-", 0, 6))) {
            session.error("Username must be 1..32 characters, contain no control characters, and not start with Guest-");
            return false;
        }
        // A broadcast may discover this session after registration. Keep WELCOME first on its stream.
        synchronized (session) {
            boolean duplicate;
            synchronized (lifecycle) {
                if (closed) {
                    return false;
                }
                if (guest) {
                    name = "Guest-" + nextGuest++;
                }
                duplicate = users.containsKey(key(name));
                if (!duplicate) {
                    session.username = name;
                    session.readOnly = guest;
                    users.put(key(name), session);
                }
            }
            if (duplicate) {
                session.error("Username already in use: " + name);
                return false;
            }
            session.welcome();
        }
        return true;
    }

    private static String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private void broadcast(String sender, String text) {
        List<Session> recipients;
        synchronized (lifecycle) {
            recipients = new ArrayList<>(users.values());
        }
        String timestamp = Instant.now().toString();
        for (Session recipient : recipients) {
            try {
                recipient.message(sender, timestamp, text);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "Cannot deliver to " + recipient.username, e);
                synchronized (lifecycle) {
                    users.remove(key(recipient.username), recipient);
                }
                try {
                    recipient.socket.close();
                } catch (IOException closeFailure) {
                    LOG.log(System.Logger.Level.WARNING, "Cannot close failed recipient " + recipient.username, closeFailure);
                }
                notifyObserver("Cannot deliver to " + recipient.username);
            }
        }
    }

    private void logActivity(String message) {
        LOG.log(System.Logger.Level.INFO, message);
        notifyObserver(message);
    }

    private void notifyObserver(String message) {
        try {
            observer.accept(new Activity(Instant.now(), message));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Activity observer failed", e);
        }
    }

    private static final class Session {
        private final Socket socket;
        private final DataOutputStream output;
        private String username;
        private boolean readOnly;

        private Session(Socket socket) throws IOException {
            this.socket = socket;
            output = new DataOutputStream(socket.getOutputStream());
        }

        private synchronized void welcome() throws IOException {
            output.writeUTF("WELCOME");
            output.writeUTF(username);
            output.writeBoolean(readOnly);
            output.flush();
        }

        private synchronized void message(String sender, String timestamp, String text) throws IOException {
            output.writeUTF("MESSAGE");
            output.writeUTF(sender);
            output.writeUTF(timestamp);
            output.writeUTF(text);
            output.flush();
        }

        private synchronized void userList(List<String> names) throws IOException {
            output.writeUTF("USERS");
            output.writeInt(names.size());
            for (String name : names) {
                output.writeUTF(name);
            }
            output.flush();
        }

        private synchronized void error(String text) throws IOException {
            output.writeUTF("ERROR");
            output.writeUTF(text);
            output.flush();
        }
    }

    /** Requests shutdown. Closing sockets releases accept() and the handlers' reads. */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                listener.close();
            } catch (IOException e) {
                failure = e;
            }
            for (Socket client : clients) {
                try {
                    client.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            handlers.shutdown();
        }
        if (failure != null) {
            throw failure;
        }
    }
}
