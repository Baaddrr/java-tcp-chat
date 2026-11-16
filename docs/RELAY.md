# RELAY architecture and verification

## Client lifecycle

`RelayApplication` owns the stage and the current connection. `WelcomeView` collects a username or requests guest access. A virtual thread opens the socket and waits for `WELCOME`. Only after acceptance does the application switch to `ConnectedView`. It hands that view the server-issued identity and keeps the same `ChatClient` socket, so frames already waiting in the socket remain available.

One virtual thread reads server frames. Command writes run on a separate virtual thread, with one write in flight per view. Responses are marshalled through `Platform.runLater`, and callbacks verify that their socket is still active. This prevents a callback from an old connection from changing the current screen. `end`, `bye`, and LEAVE take the `LEAVE` protocol path. Closing the window or cancelling a join closes the socket to release blocked I/O.

The chat uses two columns. The left column shows the endpoint and the participant snapshot. The conversation includes the session identity, Online status, transcript, and composer. The welcome screen and both new views use the same midnight-purple palette, pink actions, and lavender text. Guest mode disables the composer in JavaFX and is enforced independently by the server. The timestamp display uses the local time zone and the timestamp tooltip shows the original server instant.

## Server lifecycle

`ServerApplication` opens the dashboard and starts the blocking accept loop on a worker thread. `ChatServer` hands each accepted socket to a virtual-thread executor and publishes immutable activity records using a JDK `Consumer`. The JavaFX controller appends them to the activity log and reads the current sorted username snapshot. Each connected username gets a randomly chosen background color held for the duration of its appearance in the list. The dashboard keeps the latest 500 log entries.

A lifecycle lock protects the socket registry, joined usernames, and guest counter. The server snapshots the recipients under the lifecycle lock and releases it before writing frames. Each session has a separate output lock so concurrent senders cannot interleave the fields of a frame. During registration, the same session lock makes `WELCOME` precede any broadcast to the new client. Shutdown closes the listener and every accepted socket, including sockets still joining, then waits for the handlers to exit off the JavaFX thread.

## Verification

| Check | Observed result |
| --- | --- |
| JDK 24.0.2, Maven `-B -ntp -o clean verify` | 25 server tests and 41 client tests passed. The 51 original socket/configuration cases are intact. |
| Multi-client JavaFX integration | Named and guest JavaFX sessions received the same server broadcast. Requester-only participants, guest-disabled controls, SEND and Enter, `end`, `bye`, LEAVE, duplicate rejection, unavailable server, and unexpected EOF were exercised. |
| Server JavaFX integration | Live usernames, distinct colored rows, startup/welcome/disconnect logs, occupied-port feedback, and socket closure on Stop Server were exercised. |
| Executable JavaFX JAR startup | Both JavaFX applications started from `java -jar`. The packaged server bound the configured loopback port, entered its accept loop, and shut down. The interactive desktop click-through ended early, so native keyboard navigation and manual resizing remain unverified. |
| Packaged console integration | One server and three client JVMs exchanged a broadcast with the same timestamp. Guest rejection, private `allUsers`, `bye`, `end`, and server shutdown passed. All four processes exited with code zero. |
| JavaFX visual review | Application scenes were rendered offscreen. The screenshots in this repository use demo messages and roster data. |

The packaged classpath launch emits the JavaFX unnamed-module warning. The [JAR manifest](https://docs.oracle.com/en/java/javase/24/docs/specs/jar/jar.html) enables native access for that launch. The README provides a named-module launch command as well. JavaFX and Maven dependencies can still emit JDK 24 Unsafe deprecation warnings.

Verification used Windows and localhost. LAN reachability and other operating systems were outside this run. TCP delivery can slow when a recipient stops reading, and concurrent senders can be observed in different orders by different clients. Commands and message payloads follow the [protocol reference](PROTOCOL.md).

## Explain it in the walkthrough

- Show where `ChatServer` accepts a socket and launches its handler. Explain how closing that socket unblocks a waiting read.
- Show the per-session output lock around the type and payload fields. Explain what could happen to two simultaneous writes without it.
- Trace the welcome handshake into the JavaFX chat and point out which work runs on a virtual thread and which work runs on the JavaFX Application Thread.
- Show where the server rejects `CHAT` from a guest and returns a `USERS` reply to a single session.
