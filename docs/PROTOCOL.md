# TCP chat protocol (Phase 2)

The two Maven applications use TCP streams with Java `DataInputStream` and `DataOutputStream`. Each frame starts with a type string, followed by the fields below in order. There is no Java object serialization, JSON dependency, delimiter splitting, or line-based framing.

## Encoding

- Every string, including the type, uses `writeUTF` / `readUTF`: a two-byte unsigned encoded-byte length followed by Java **modified UTF-8**. This is not ordinary UTF-8; NUL and surrogate pairs have different encodings. The maximum encoded string length is 65,535 bytes.
- Booleans use `writeBoolean` / `readBoolean` (one byte).
- User counts use `writeInt` / `readInt` (four-byte signed, big-endian).
- A frame is delimited by its type and fixed field schema; `USERS` replies additionally contain a count. TCP packets and individual `read()` calls are not message boundaries.
- Writers lock the whole frame, including type, all payload fields, and flush. Readers consume each field in the documented order.

## Client to server

| Type | Following fields | Meaning |
| --- | --- | --- |
| `JOIN` | username: string | Required first frame; blank/whitespace requests guest access. |
| `CHAT` | text: string | Request a group message. Identity and time are not client-supplied fields. |
| `USERS` | none | Request the active-client list. |
| `LEAVE` | none | End this connection. No acknowledgement frame. |

## Server to client

| Type | Following fields | Meaning |
| --- | --- | --- |
| `WELCOME` | assigned username: string; read-only: boolean | Successful registration. Always the first reply on a successful join. |
| `MESSAGE` | sender: string; timestamp: string; text: string | Server-owned identity and UTC ISO-8601 `Instant`, plus the original chat text. |
| `USERS` | count: int; that many username strings | A sorted snapshot sent only to the requester. |
| `ERROR` | reason: string | Explicit rejection or protocol error. |

The client consumes `WELCOME` synchronously in `join()`. Its single receive loop then decodes `MESSAGE`, `USERS`, and `ERROR` into immutable event values.

## State, validation, and errors

1. A newly accepted socket must supply a complete `JOIN`. The server applies a five-second read timeout during identification. A connected socket without a completed join is included in the transport connection count but not the active-user list or broadcasts.
2. Names are stripped of surrounding whitespace. Blank names become `Guest-1`, `Guest-2`, etc., using a server-owned sequence. Guests are read-only. Named users cannot use the reserved `Guest-` prefix, ignoring case.
3. Named usernames contain at most 32 Java UTF-16 code units and no ISO control characters. Duplicate comparison uses `toLowerCase(Locale.ROOT)`; the first atomic registration wins. The original display spelling is retained. Rejection sends `ERROR`, then closes the duplicate connection. Cleanup checks session identity so a rejected or old session cannot remove another user.
4. Registration and `WELCOME` share the recipient's output lock. A concurrent broadcast cannot get ahead of its welcome frame.
5. After registration, guests may request `USERS` and `LEAVE`. A guest `CHAT` gets `ERROR` and stays connected; it is never broadcast. A second `JOIN` or an unknown command is a protocol violation: `ERROR`, then disconnect.
6. Chat text must be nonblank and at most 4,096 Java UTF-16 code units. This fits safely within a `writeUTF` field even at three encoded bytes per code unit. The server checks this independently; the supplied client also checks the length before writing any frame bytes to avoid a partially written request.
7. Valid chat is broadcast to all registered connections, including the sender and guests. One server timestamp is shared across all copies. Usernames and timestamps cannot be injected as extra chat fields. A client pretending to send a server `MESSAGE` frame is rejected.
8. `USERS` includes named clients and guests, sorted without regard to case. It is a snapshot at request handling time; disconnects can occur immediately afterward. Guests are identifiable by their reserved `Guest-` labels.
9. `LEAVE`, EOF, an I/O failure, or server shutdown closes the socket and removes the session. `ChatClient.leave()` writes and flushes `LEAVE` and then closes locally; server-side removal is asynchronous. `close()` remains an immediate cancellation path that does not need the output lock and can release blocked reads or writes.
10. A malformed/incomplete payload causes an I/O failure and cleanup. EOF while waiting for the next type is treated as a transport disconnect. Join rejection and unknown commands terminate the connection; guest/invalid-chat errors do not.

## Text commands and content

The console and JavaFX composer use `ChatClient.submit(text)`:

- Stripped text exactly equal to `allUsers` sends `USERS`.
- Stripped text exactly equal to `end` or `bye` sends `LEAVE` and closes; `submit` returns `false`.
- Other text is sent as `CHAT`. Commands are case-sensitive.

`sendMessage(text)` explicitly sends chat content, so literal `allUsers`, `end`, or `bye` are ordinary messages through that API. Frames can carry newlines, tabs, NUL, and Unicode. The console accepts one input line per message and the JavaFX composer supports multiline text.

## Ambiguities resolved for this milestone

The server feature list specifies all active connections. The server echoes the authoritative message to its sender, and the JavaFX view displays the received frame.

The PDF does not define duplicate names, guest display labels, validation limits, ordering, or a wire encoding. The rules above make those behaviors explicit. They are implementation choices, not additional professor requirements.

TCP preserves each connection's order. Two simultaneous senders need not appear in an identical global order at every recipient. Frames remain intact and each accepted message is attempted once per recipient snapshot; failed recipients are logged, removed, and closed without preventing attempts to other recipients. This is a live chat, with no delivery acknowledgement, replay, persistence, heartbeat, or encryption.
