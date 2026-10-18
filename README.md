# CSC 3374 Mini Project 1: TCP Chat

Phase 2 implements the TCP chat functionality of **Multi-Client Chat Application Using Java Sockets**: usernames, server-enforced read-only guests, group messages, active-user requests, and clean disconnection. The professor's requirements PDF is the assignment specification. JavaFX, UML, and final presentation deliverables remain for later phases.

## Prerequisites and IntelliJ

- JDK 24; no preview features.
- Maven 3.9.x, installed or bundled with IntelliJ IDEA.
- Open/reload the root `pom.xml` as a Maven project. It aggregates `TCPServer` and `TCPClient`.
- Set Project SDK, Maven importer JDK, and Maven runner JRE to Java 24.
- For application run configurations, select the `TCPServer` or `TCPClient` main class and the matching module classpath. Set the working directory to the repository root; allow multiple client instances.

On the current machine, the terminal defaults to Java 8 and Maven is not on PATH. In each PowerShell terminal:

```powershell
Set-Location 'C:\Users\asus\IdeaProjects\tcp-chat-project'
$env:JAVA_HOME = 'C:\Users\asus\Downloads\jdk-24_windows-x64_bin\jdk-24.0.2'
$env:Path = "$env:JAVA_HOME\bin;C:\Program Files\JetBrains\IntelliJ IDEA 2026.1.2\plugins\maven\lib\maven3\bin;$env:Path"
java -version
mvn -version
```

Use your own JDK/Maven installation paths on another machine. The POMs contain no machine-specific paths. Generated `target/`, `out/`, class files, and IntelliJ `.idea/` settings are excluded from Git. The root is only a Maven parent and has no `src/` directory.

## Build and verification

```powershell
mvn clean verify
```

This runs tests and produces:

```text
TCPServer/target/TCPServer.jar
TCPClient/target/TCPClient.jar
```

The Phase 2 build passed with Java 24: **51 tests, zero failures/errors/skips**. All 28 Phase 1 tests remain unchanged. The 23 new test cases cover group messaging to three clients, server-owned identity/time, Unicode/multiline framing, guest restrictions through raw TCP, private user inquiries, duplicate names including simultaneous joins, EOF/reset cleanup, name reuse, shutdown, invalid requests, text commands, malformed server frames, and concurrent writes.

The concurrency tests send 120 messages from two clients and verify all 120 unique complete messages at each of three recipients. A separate test mixes 120 chat frames with 60 user inquiries from three threads sharing one `ChatClient`. Waits use futures/conditions with deadlines. Negative no-delivery checks use a bounded socket read timeout.

Run individual modules with `mvn -pl TCPServer test` or `mvn -pl TCPClient test`. Full results are under each module's `target/surefire-reports`.

## Demonstration: server plus three client processes

Run from the repository root, using the environment setup above in each terminal.

Terminal 1:

```powershell
java -jar TCPServer/target/TCPServer.jar
```

Terminals 2, 3, and 4 (each starts its own client JVM):

```powershell
java -jar TCPClient/target/TCPClient.jar localhost 3000
```

At the username prompt, enter `Alice` in terminal 2, `Bob` in terminal 3, and a blank line in terminal 4. The guest receives a label such as `Guest-1` and `READ-ONLY MODE`.

1. Alice types `Hello Bob and guest`. Alice, Bob, and the guest receive the same server-assigned username/timestamp/text.
2. The guest types a chat message. Only that guest receives the server's read-only rejection; other clients receive no chat from it. The connection stays open.
3. Bob types `allUsers`. Only Bob sees `Active users: Alice, Bob, Guest-1`.
4. Bob types `bye`. Alice's subsequent `allUsers` returns `Alice, Guest-1`.
5. Alice types `end`. The guest can still request `allUsers` and sees only `Guest-1`.
6. Press Enter in the server terminal. Its listener and clients close, handlers finish, and the guest exits.

This sequence was executed with the packaged applications and three simultaneously connected client JVMs. The server and all clients exited with code `0`. The generated transcript is `TCPServer/target/phase2-demo-results.txt` when present; a later `clean` removes it.

Observed chat output from that run:

```text
[Alice] [2026-10-17T20:15:36.391133Z] Alice: Hello Bob and guest
[Bob]   [2026-10-17T20:15:36.391133Z] Alice: Hello Bob and guest
[guest] [2026-10-17T20:15:36.391133Z] Alice: Hello Bob and guest
[guest] Server: READ-ONLY MODE: guests cannot send chat messages
[Bob]   Active users: Alice, Bob, Guest-1
```

Timestamps vary. The PDF's class names also work with explicit classpaths:

```powershell
java -cp TCPServer/target/TCPServer.jar TCPServer
java -cp TCPClient/target/TCPClient.jar TCPClient localhost 3000
```

Client console EOF closes its connection. Server Enter/EOF requests shutdown. JVM shutdown hooks close sockets on normal termination; force-killing a process cannot execute Java cleanup.

## Configuration and protocol

The existing configuration mechanism is unchanged. Edit `config/server.properties` or `config/client.properties` without rebuilding:

```properties
server.host=127.0.0.1
server.port=3000
```

On the server, the host is a local bind address. On the client, it is the destination address. Client IP/port arguments override properties; either zero or exactly two arguments are supported. A readable configuration file remains required even with arguments. Invalid startup settings fail visibly with exit code `1`.

Override the default configuration path with a JVM property before `-jar`:

```powershell
java -Dchat.server.config=C:/path/server.properties -jar TCPServer/target/TCPServer.jar
java -Dchat.client.config=C:/path/client.properties -jar TCPClient/target/TCPClient.jar localhost 4000
```

Default paths are relative to the working directory. TCP connect and identification reads have five-second timeouts; hostname resolution follows JVM/OS behavior. After identification, socket reads block until data, EOF, cancellation, or a transport error.

See [the wire protocol](docs/PROTOCOL.md) for field schemas, command/content separation, limits, errors, and ambiguity decisions. In particular: broadcasts include the sender, duplicate names use a case-normalized first-registration-wins rule, guests receive reserved labels, and `allUsers` includes guests.

## Classes and methods to understand

| Class / method | Responsibility |
| --- | --- |
| `ChatServer.run()` | Accepts sockets and submits one virtual-thread handler per connection; waits for handlers during shutdown. |
| `ChatServer.handle()` | Reads a required join, then commands. Enforces guest restrictions and message limits, routes private replies, and removes closed connections in `finally`. |
| `ChatServer.register()` | Atomically reserves a unique identity or assigns a guest label. Keeps `WELCOME` ahead of any concurrent broadcast. |
| `ChatServer.activeUsers()` | Returns an immutable, sorted snapshot of registered names under the lifecycle lock. |
| `ChatServer.broadcast()` | Creates one authoritative timestamp and sends the message to a recipient snapshot. Removes/closes failed recipients and continues other delivery attempts. |
| `ChatServer.Session` | Private per-connection username, read-only state, and output stream. Synchronized frame methods prevent multiple writers from interleaving bytes. |
| `ChatServer.close()` | Closes the listening and accepted sockets and stops task submission. `run()` waits for handlers after releasing the lifecycle lock. |
| `ChatClient.join()` | Sends the username, consumes the registration reply, and returns the server's assigned `Identity`. Rejects unsuccessful/malformed handshakes. Call before starting the receive loop. |
| `ChatClient.sendMessage()` / `requestUsers()` | Send explicitly typed chat or inquiry frames. Guest chat is still checked by the server, even if a caller bypasses view restrictions. |
| `ChatClient.submit()` | Maps exact text commands `allUsers`, `end`, and `bye`; otherwise sends chat. Returns false on a requested disconnect. |
| `ChatClient.leave()` / `close()` | `leave()` sends the protocol disconnect and closes, ready for a future LEAVE button. `close()` immediately cancels socket I/O, including a blocked join/receive. |
| `ChatClient.receive()` | Single blocking decoder loop invoking a callback with immutable `ChatMessage`, `UserList`, or `ServerError` values. Closes on EOF/failure. |
| `ChatClient.awaitDisconnect()` | Retained Phase 1 lifecycle API; uses the receive loop while discarding events. Do not run it alongside another reader. |
| `TCPClient.main()` | Temporary presentation: prompts for a name, prints events, and reads text on a virtual thread while the main thread receives. |
| `TCPServer.main()` | Existing console lifecycle control; only its Phase 1-specific startup wording changed. |
| `ClientConfig` / `ServerConfig` | Existing property loading and validation; unchanged in Phase 2. |

No new production top-level classes, modules, frameworks, runtime libraries, or configuration options were added. The two new test classes are `ChatProtocolTest` in the server module and `ChatClientProtocolTest` in the client module. The Maven POMs, properties files, and existing tests are unchanged.

## Alice to Bob: message lifecycle

1. Alice's console calls `submit(text)`, which calls `sendMessage(text)` for ordinary chat.
2. Alice's client locks its output stream and writes a complete `CHAT` frame. It supplies only the text.
3. Alice's server virtual thread reads that frame. It uses the identity registered for Alice's socket, checks read-only status and text limits, then calls `broadcast`.
4. The server takes a recipient snapshot under the lifecycle lock and releases it before network writes. It creates one UTC `Instant` and locks each recipient's output while writing `MESSAGE`, sender, timestamp, and text.
5. Bob's single receive loop decodes a `ChatMessage`. The console callback displays it. Alice and guests receive the same event fields.

The future JavaFX view can reuse this API. Connect/join/send/receive must run off the JavaFX application thread, and UI updates must be marshalled onto it. This phase contains no JavaFX code.

## Virtual threads, synchronization, and ownership

Virtual threads allow each client handler to block on its own socket without blocking the accept loop. They do not protect shared collections or make multi-field writes atomic.

The lifecycle lock protects the accepted-socket set, user registry, guest counter, and shutdown boundary. The set tracks all accepted sockets so shutdown also cancels clients that have not identified themselves; the registry tracks identified recipients. Snapshots allow network writes outside that global lock. Each session monitor locks complete outgoing frames. The supplied client likewise serializes its writers with an output lock, while exactly one thread reads its input.

A session may acquire its output monitor and then the lifecycle lock during registration. No code holds the lifecycle lock while acquiring a session output monitor. Shutdown closes sockets directly without waiting for output locks, which also cancels blocked writes. Cleanup removes a user only if the registry still points to that exact session.

The server owns the listener, accepted sockets, and handler executor; each client owns its socket. Close the sockets before waiting for handlers, otherwise the handlers can remain blocked in reads. Closing, EOF, reset, rejection, and failed output all lead to cleanup.

## Limits and review

Verification is on Windows, Java 24, and localhost. LAN/firewall deployment and load capacity remain unverified. This is username identification, with no passwords or encryption. Silent network failures depend on TCP/OS detection. A slow receiver can apply backpressure to broadcasting threads; there is no outbound queue or write deadline. Concurrent senders have no promised global ordering across recipients. These limits are documented instead of adding unrelated infrastructure.

Useful defense questions: Why is a thread-safe user map alone insufficient to protect `writeUTF` frames? Why must a new session's `WELCOME` and publication share an output lock? Why does shutdown close sockets before awaiting tasks?

Phase 1 and Phase 2 are included together in the initial Git commit. The repository is hosted at https://github.com/Baaddrr/java-tcp-chat. JavaFX remains outside these completed phases.
