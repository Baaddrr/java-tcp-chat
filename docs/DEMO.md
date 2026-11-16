# Three-minute RELAY demo

Rehearse once with the exact packaged JARs listed in the README. Use two named client windows and one guest window. Keep IntelliJ and the server dashboard within easy reach using Alt+Tab.

| Time | Show | Say |
| --- | --- | --- |
| 0:00–0:30 | IntelliJ’s Maven tree, then `ChatServer.run()` and the virtual-thread handler executor. | “RELAY uses two Maven modules and a single TCP listener. Each connection gets a virtual-thread handler. A session locks its output stream while it writes a complete frame.” |
| 0:30–0:50 | Open `ChatClient.join()` and `RelayApplication.showConnected()`. | “The server supplies the username and guest permission in WELCOME. The JavaFX client keeps that same socket, reads in the background, and updates the view on the JavaFX thread.” |
| 0:50–1:15 | Show the packaged server Online, then join as Alice, Bob, and a guest. Bring the dashboard forward to show all three names and Welcome messages. | “The server loads its address and port from properties. Each accepted name appears in the live server list.” |
| 1:15–1:45 | Send from Alice with Enter and from Bob with SEND. Switch to the guest to show the same sender names and timestamps and the disabled composer. | “The server attaches the sender and time. Guests receive the same broadcast and the server independently rejects guest CHAT.” |
| 1:45–2:10 | Type `allUsers` in Alice’s composer. Switch to Bob to show that his list did not update. Click the guest’s allUsers button. | “The participant list refreshes on request. The server replies only on the requesting socket.” |
| 2:10–2:35 | Type `bye` in Bob’s composer and click LEAVE in Alice’s client. Switch to the dashboard to show both removal events. | “The text command and the button send the same LEAVE frame. Typing end uses that path too.” |
| 2:35–3:00 | Click **Stop Server**. Show the final dashboard log and the guest’s Offline welcome screen. | “Shutdown closes the listener and client sockets. The guest sees the disconnect and can connect again when the server is available.” |

## Recording checklist

- [ ] Build with `mvn clean verify`. Launch the JARs from the root using the README commands.
- [ ] Open the source methods before recording. Close unrelated windows and clear previous test sessions.
- [ ] Capture the region that includes IntelliJ and all RELAY windows at a readable resolution. Check the microphone level if narrating.
- [ ] Verify that the recording shows both message submission paths, server timestamps, the guest restriction, the private user list, and the shutdown results.
- [ ] Trim the recording to roughly three minutes and play it back to check text legibility and audio.
- [ ] Submit the recording with the runnable artifacts and their `lib` directories, the UML diagrams, and the updated GitHub repository link.

The script is ready for recording.
