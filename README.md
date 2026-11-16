# RELAY · CSC 3374 Mini Project 1

RELAY is a single-room group chat built with Java 24, TCP sockets, virtual threads, and JavaFX 24. The Maven project has two executables. **TCPServer** accepts connections, broadcasts messages, and displays live server activity. **TCPClient** joins as a named participant or a read-only guest and displays the shared conversation.

## Windows setup

Install JDK 24 and Maven 3.9.x. IntelliJ IDEA’s bundled Maven works as well. Open a PowerShell terminal at the repository root and verify that Maven is using Java 24.

```powershell
java -version
mvn -version
mvn clean verify
```

Set `JAVA_HOME` to your JDK 24 installation and add its `bin` directory and Maven's `bin` directory to `PATH`. IntelliJ's Maven runner can also execute the build.

## Project layout

| Path | Contents |
| --- | --- |
| `pom.xml` | Parent Maven build for both modules |
| `TCPServer/` | Server launcher, TCP model, JavaFX dashboard, and tests |
| `TCPClient/` | Client launcher, TCP model, JavaFX views, and tests |
| `config/` | External server and client address/port settings |
| `docs/` | Protocol, UML, screenshots, and verification notes |

## Launch the packaged applications

Start the server in one terminal. Open another terminal in the same working directory for each client. The submission ZIP contains a ready-to-run Windows x64 build. Run these commands from the extracted `submission/` directory.

```powershell
java -jar run/TCPServer.jar
java -jar run/TCPClient.jar localhost 3000
```

The ZIP's two JARs share `run/lib`. Keep that directory beside them and keep `config/` in the working directory. A source build from the repository root produces the following launch paths.

```powershell
java -jar TCPServer/target/TCPServer.jar
java -jar TCPClient/target/TCPClient.jar localhost 3000
```

Each Maven-built executable uses its adjacent `target/lib` directory. Maven copies JavaFX natives for the build platform. Rebuild from source for another OS or architecture.

The assignment’s class-based entry points are `TCPServer` and `TCPClient <ServerIPAddress> <PortNumber>`. These commands launch the same packaged classes with JavaFX on the module path.

```powershell
java --enable-native-access=javafx.graphics --module-path TCPServer/target/lib --add-modules javafx.controls -cp TCPServer/target/TCPServer.jar TCPServer
java --enable-native-access=javafx.graphics --module-path TCPClient/target/lib --add-modules javafx.controls -cp TCPClient/target/TCPClient.jar TCPClient localhost 3000
```

For the original console workflow, add `--console` after the JAR or main class and before any client host/port arguments. Press Enter in the server terminal to stop it.

### IntelliJ IDEA

1. Open the root `pom.xml` as a Maven project. Set the Project SDK, Maven importer JDK, and Maven runner JRE to Java 24. Reload Maven.
2. Create Application configurations for `TCPServer` and `TCPClient` using their respective module classpaths. Set the working directory to the repository root.
3. In the client configuration, set program arguments to `localhost 3000` or leave them empty for the properties file. Enable **Allow multiple instances** to launch several clients.
4. Build and test through the Maven `verify` lifecycle. Use the packaged module-path commands above when you want to launch with JavaFX as named modules.

## Configuration

| File | Purpose | Default |
| --- | --- | --- |
| `config/server.properties` | Address the server binds to and its listening port | `127.0.0.1:3000` |
| `config/client.properties` | Address and port clients connect to | `127.0.0.1:3000` |

Both files use `server.host` and `server.port`. Passing both client arguments overrides those values. The client still loads its properties file at startup. Use `-Dchat.server.config=path` or `-Dchat.client.config=path` before `-jar` or the main class to choose a different file. For a LAN deployment, set the server bind address to the appropriate interface and point clients at that host’s LAN IP. The checked configuration and tests use localhost.

## Use RELAY

- Join with an available username, or choose **Continue as Guest**. Guests receive the conversation, request the participant list, and leave.
- Use **SEND** or Enter to send. The server attaches the username and timestamp and broadcasts once to every active session, including the sender.
- Enter `allUsers` or click the sidebar’s **allUsers** button to request a participant snapshot. Only the requesting client receives that reply.
- Enter `end` or `bye`, or click **LEAVE**. The client returns to the welcome screen with Offline status. A server disconnect also returns there and displays a reason.
- The server dashboard shows connected users, their assigned colors, and the activity log. **Stop Server** closes the listener and client sockets.

## Architecture and verification

`ChatServer`, `ChatClient`, and both configuration records use the JDK alone. The JavaFX applications coordinate their lifecycles, and the views render state. The server accepts one TCP socket per client and runs each handler on a virtual thread. The client moves connect, receive, and command writes off the JavaFX Application Thread.

TCP frames start with a type written using `DataOutputStream.writeUTF`, followed by that type's fields. `JOIN` and `WELCOME` establish identity, `CHAT` becomes a server-authored `MESSAGE`, `USERS` returns a private roster snapshot, and `LEAVE` closes the session. See the [protocol reference](docs/PROTOCOL.md) for field layouts, ordering, and validation.

`mvn clean verify` runs networking and JavaFX tests. Use a graphical session with localhost TCP permitted. The verified Windows build with JDK 24.0.2 passed **66 tests** with zero failures, errors, or skips. Reports are in each module’s `target/surefire-reports`. See [architecture and verification details](docs/RELAY.md).

- UML class diagram ([PNG](docs/uml/classes.png), [Graphviz source](docs/uml/classes.dot))
- UML deployment diagram ([PNG](docs/uml/deployment.png), [Graphviz source](docs/uml/deployment.dot))
- [Remote GitHub repository](https://github.com/Baaddrr/java-tcp-chat)

### JavaFX screens

[Welcome](docs/screenshots/welcome.png) · [Group chat](docs/screenshots/chat.png) · [Server dashboard](docs/screenshots/server.png)

The screenshots are application renders. The chat and dashboard use sample conversation and activity data.
