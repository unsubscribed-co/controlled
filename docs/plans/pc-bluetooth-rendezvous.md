# Smooth mode: meet over Bluetooth, the PC dials the phone

Status: built 2026-11-10, not released. It replaces how [pc-wifi.md](pc-wifi.md) has the phone find the PC: mDNS
plus an inbound port. Pairing, crypto and the input protocol stay the same.

## Why
On 2026-11-10 smooth mode silently failed on a second laptop. Measured causes:
- **Another network with the same Wi-Fi name.**
  - The laptop was on a second router (Movistar) broadcasting "JuanETB-5G_PLUS" at 192.168.1.x.
  - The phone was on the main router at 192.168.0.x.
  - mDNS doesn't cross routers, and the laptop couldn't reach the main network at all.
- **Public network type.**
  - The laptop's Wi-Fi was Public. The manifest's firewall rules covered Private only, so even on the same network the phone would have been blocked.
  - Windows makes new networks Public by default.
- Neither case said anything, on the phone or the PC.

Other silent failures of the old design:
- **Name match.** The phone looked for the PC by its Bluetooth name. A PC renamed after pairing, or whose Bluetooth name differs from its host name, was never found.
- **Routers that drop multicast.** Mesh networks, separate 2.4 and 5 GHz bands, and guest networks block mDNS.
- **A declined firewall prompt.** It leaves Block rules that win over the manifest's Allow rules.

## Design
The phone and PC are already paired over Bluetooth: that pairing is the trust and the address. Use it to meet.

1. The phone opens Controlled's Bluetooth channel (RFCOMM, service `6c0a3b2e-7e1d-4f6a-9b1e-2f0c0a7e5d11`) on the PC it's controlling.
   - No search, no name.
   - Windows only lets paired devices in.
2. Over that channel:
   - the hello;
   - the first time, the 6-digit code;
   - Allow or Deny.
3. The phone says where it waits: a TCP port, a UDP port and its reachable IPv4 addresses.
4. The PC dials the phone over TCP and proves it holds the session keys.
   - It sends its first UDP packet from its own socket.
   - The phone sends moves back to wherever that packet came from, which works even through a router.
   - A UDP keepalive goes with every 2 s heartbeat.
5. If the PC can't dial in 3 s, it tells the phone over Bluetooth which address it tried from. The phone then says one of:
   - "the PC is on a different network (PC x, this phone y): connect both to the same Wi-Fi";
   - "your network keeps devices apart (guest network, AP isolation)".

The phone also says when its own Wi-Fi is off, and offers to install or open the program when the PC doesn't answer over Bluetooth. Bluetooth input keeps working throughout.

Results:
- **No inbound port on the PC**, so no firewall rules and no Public/Private question.
- Nothing is exposed on café Wi-Fi.
- The manifest drops its firewall rules and `privateNetworkClientServer`.

## Hardening (review of 2026-11-10)
Each fix answers a way the first build could fail or hang silently:
- **Every Bluetooth wait has a limit.** A Bluetooth read has no timeout, so a timer closes the socket: 10 s per step, 95 s while the person decides on the PC. A hung PC program can't leave smooth mode stuck trying.
- **Paused, or another phone in use, is said.** The PC answers the hello with 'Z' (paused) or 'B' (another phone has the session), and the phone says so.
  - Before, paused looked like "not installed".
  - Before, two phones on one PC kicked each other out in a loop.
  - The same phone coming back still replaces its own stale session.
- **The phone has a heartbeat too.** It sends an empty frame every 2 s when idle, and the PC ends the session after 6 s without one, letting go of anything held. Before, a phone that vanished mid-drag left the button held until TCP gave up (tens of seconds).
- **The PC ends a session as soon as its heartbeat can't be sent.**
- **Every reachable phone address.** The phone sends up to 8 IPv4 addresses (Wi-Fi first, then its hotspot or Ethernet; not mobile data or VPN), and the PC dials them all at once. A laptop on the phone's own hotspot works.
- **The phone's UDP replies match.** The PC sends its UDP to the address that answered over TCP, so the phone's replies match what Windows lets in.
- **Only paired devices may open the PC's channel.** `SO_BTH_AUTHENTICATE` is set.
- **Retries are capped at 10 s, not 20 s.** Reopening the PC program brought smooth mode back after 21 s.
- **"Another network" also names a VPN as a cause.**

From the independent review (all 8 fixed, then retested live):
- **Paused or removed is checked again under the lock** before a session starts. Pausing during the meeting (up to 85 s while allowing) used to let that session inject keys.
- **Bluetooth → Wi-Fi releases everything on the Bluetooth side.** This covers smooth mode coming on mid-drag, and a button released right after an administrator window closes. The Bluetooth resync only repeats held state while input really goes over Bluetooth.
- **One pairing question per phone.** Another phone asking meanwhile gets 'B'. The same phone asking again replaces its own question. Allow and Deny carry the code they answer, so a newer question is never approved by mistake. Leaving the PC's screen closes the attempt.
- **A late success is closed, not leaked.**
- **A LAN host can't hold the door.** Each knock on the phone's port gets 500 ms to prove itself. Stray UDP datagrams are skipped. When the PC dialed in but the meeting didn't finish, the phone says that rather than "another network".
- **A failed Bluetooth accept doesn't take the channel down.** Only five in a row (the radio off) do.
- **Pastes over 8,000 characters go in pieces.** A frame's length is 16 bits.
- **The allowed-phones file changes under one lock** and is replaced whole.
- **Not changed: the code has no commitment round.** That matters only if someone can sit in the middle of the Bluetooth link. Windows requires the pairing's authentication on the channel and Android uses the encrypted (secure) RFCOMM socket, so they would have to break the pairing first.

A second independent review, of the fixes themselves, found five more. All are fixed:
- **Smooth mode coming on mid-drag or mid window switch** finishes that over Bluetooth, and moves to Wi-Fi at the release. Releasing at once dropped the dragged item.
- **The refusal check and taking the pairing question share one lock.** Two new phones arriving together could leave one marked declined.
- **While it waits for Allow, the PC watches the Bluetooth channel** (select plus peek, no reliance on read timeouts). A phone that leaves takes its question with it. Measured: the phone closes its attempt when the 30 s app grace ends, and the PC drops the question 2 s later.
- **A close() before the socket exists still stops the attempt.** Starting for another PC closes the previous attempt.
- **Saving the phones file retries the rename,** then writes in place rather than lose a change.

Found while retesting: ending a session (pause, remove) now clears "Connected to …" in the window at once. Before, it stayed.

Retested on 2026-11-10 with PC test package 1.0.13 and phone dev.207:
- connect: 2 s;
- removed phone pairs again;
- a wrong code is ignored;
- leaving mid-pairing and coming back gives a new matching code;
- pause and resume;
- the phone vanishing: the PC ends the session at 6 s and smooth mode is back about 6 s after Wi-Fi returns.

## Measured before building (2026-11-10, POCO X7 Pro and Juan's desktop)
All with the phone connected as the PC's Bluetooth keyboard and mouse the whole time.

- **The PC opens RFCOMM to the phone:** 2.1 s cold, 0.22 s while HID was connected.
- **The phone opens RFCOMM to the PC:** 194 ms. This is the direction built.
- **The PC dials the phone over TCP** right after: 0.13 s.
- **Phone → PC UDP after the PC sent first:** all 1250 arrived.
  - The probe program had no firewall rule, the inbound policy was BlockInbound, and no prompt appeared.
  - Gap p90 8.7 ms; 27 gaps over 25 ms in 10 s.
- **The whole session over Bluetooth was rejected:**

  | Link | 1250 packets, one every 8 ms | p50 | p90 | p99 | Gaps over 25 ms |
  |---|---|---|---|---|---|
  | RFCOMM | arrive in bursts | 2.6 ms | 18.8 ms | 35 ms | 85 to 89 |
  | Wi-Fi TCP | steady | 7.9 ms | 8.9 ms | 35 ms | 25 to 30 |

  RFCOMM has the same stutter as Bluetooth HID. Bluetooth is for meeting; Wi-Fi carries input.

## Compatibility
The hello is now version 2. Phone 1.20 with PC 1.0.4 and earlier speak only the old way, so phone and PC must update together. Until both are updated, smooth mode stays off and Bluetooth works as always.

## Not checked yet
- The RFCOMM service from the Store-installed (MSIX) program. Measured only unpackaged.
- A PC behind a second router, where the PC can reach the phone but not the other way round. Expected to work because everything starts from the PC; not measured.
- Real guest or client-isolation networks.
