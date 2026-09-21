# Changelog

All notable changes to TiredVPN Android are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.11.0] - 2026-09-21

### Compatibility

- **Install this build before the servers move to core 1.11.x. Older builds
  will not connect after they do.** Core 1.11.0 changed what goes on the wire
  and says so plainly: an audit of the DPI strategies found an open relay that
  handed any host on the internet a tunnel for five bytes of ASCII, payloads
  travelling in clear, and markers that named the protocol to anyone watching.
  None of those could be closed without changing the wire, and there is no mode
  where a server accepts both formats - for the authentication holes that mode
  would keep the hole open, and for the markers it would keep the fingerprint
  live. The break is not in one strategy: REALITY moved its dispatch byte inside
  the encrypted layer, the stego framing replaced a literal marker with a keyed
  one, ALPN stopped advertising `tiredvpn`, and the rest followed. A build older
  than this one has nothing left to fall back to. This app carries the core it
  was built with, so installing this release is what makes a phone ready.

### Removed

- **Port hopping is gone from the app.** What was left after 1.10.0 removed the
  lying code path was a settings screen, a generator and a set of config fields
  that no longer reached anything: the core has never implemented the command
  the client used to send. Configs that still carry the old fields import fine;
  the fields are ignored.

### Fixed

- **Connecting now fails when it says it will.** Each step of a connection
  attempt had its own timeout and none of them knew about the others, so the
  worst case was around a minute and forty in "Connecting" before reporting a
  failure that was decided much earlier. There is one deadline per attempt now,
  and every step gets what is left of it.

- **A reboot no longer switches the VPN back on against the user's wishes.**
  Auto-connect on boot was the entire decision, so someone who turned the tunnel
  off and then restarted their phone got it back. The persistent flags that say
  how the last session ended were read, logged, and ignored; they now decide.
  The same rule covers an app update, which never asks the boot preference at
  all - an update quietly connecting a VPN for someone who had it off is the
  same defect from the other side.

- **The network change path looks at the network actually carrying traffic**
  rather than at whichever one was announced last, so a handover is noticed when
  it happens instead of up to two seconds later.

- **A server that fails to save says so.** Writes to the encrypted store report
  their outcome now, and the screen that asked for the write shows the failure
  instead of looking like it worked.

- **A server config sent as JSON keeps its endpoint selection policy.** Only the
  link format carried it, so the same server arrived with a different policy
  depending on how it was sent.

### Changed

- Bundled core updated to 1.11.4, which also brings: an endpoint the client
  knows is unreachable is skipped rather than waited on, the connection pool
  honours its own limits and its idle timeout, and the cover-domain order no
  longer repeats per client. 1.11.4 itself is two Android-only fixes: the
  authenticated REALITY data layer is now required here as it already was on
  every other client - the Android config is built as a literal that bypassed
  the flag defaults, so the phone was the one platform still accepting the
  malleable older layer - and a dead native command channel stopped answering
  "ok" to anything asked of it.

## [1.10.0] - 2026-09-21

This release is the result of an audit: eleven independent reviews of the
codebase, then a fix pass over everything they found that was real. Two defects
in it explain most of the "it dropped and never came back" reports.

### Fixed

- **The recovery path no longer switches itself off.** Stopping the VPN wrote a
  persistent "the user does not want a VPN" flag and cancelled the 15-minute
  watchdog. That would be correct if only the user could stop it - but the same
  function ran when Android destroyed the service, when the VPN permission was
  revoked, when the reconnect budget ran out after thirty attempts, and when a
  reconnect hit an invalid config. After any of those the watchdog, the boot
  receiver and the airplane-mode receiver were all gated off, and nothing
  brought the tunnel back until the app was opened and connect was tapped. A
  stop now carries what caused it, and only a user-initiated stop touches the
  flag. A related hole: Android restarts a sticky service with a null intent,
  which the service ignored entirely - the process could die (including at the
  hands of its own connect watchdog), come back, and sit there doing nothing.

- **"Is the core alive" is now answered by the core.** The check was a boolean
  set when the client started and cleared only by our own stop or by the core
  saying it had disconnected. The core runs inside this process: a wedged
  goroutine, a looping handshake or a dead relay never moved that boolean. So
  the ten-second health check reported a healthy tunnel over a dead one, and the
  thirty-second process watchdog never fired once. Liveness now comes from the
  keepalive stream the core already sends, with the boolean kept only for the
  question it can actually answer - did we start the client at all.

- **The control socket has one writer and one reader.** Five places wrote to it
  with no lock, and the TUN descriptor is attached to "the next write on this
  socket" - so a routine status poll could slip in and carry the descriptor
  away, leaving the command that needed it without one. The attachment was also
  never cleared, so every later write carried another copy, and after the
  interface was rebuilt the descriptor number was reused and the core received
  something else entirely. Three separate buffered readers over the same stream
  meant one of them swallowed answers meant for another: a `set_fd` reply lost
  this way turned into a fifty-second wait, a false "Failed to activate tunnel"
  and a needless interface rebuild.

- **A cancelled reconnect can no longer tear down what a new connect has just
  built.** Its cleanup ran in a non-cancellable block, so a fresh connect could
  already own the TUN descriptor, the core, the control channel and the protect
  socket by the time the old one reached for them. Every destructive step is now
  gated on the connection generation that started it, and a resource is only
  released by the code that still owns it. In the same area: a forced reset
  released a mutex it did not own, which could unlock a reconnect that had just
  taken it and let two run at once.

- **Reconnect progress no longer looks like failure.** "Reconnecting", "Checking
  network" and "Waiting for network" were published as error states. The main
  screen painted them red as "Disconnected", the watchdog read them as "not
  connected" and restarted the service, and a tap on the button at that moment
  killed the reconnect in progress.

- **The core's IPv6 renegotiation is applied.** After an automatic reconnect the
  server can hand out a different pair of tunnel v6 addresses, and the only way
  it announces that is an `ipv6_changed` event. Nothing listened for it, so the
  interface kept an address that no longer routed until the next full connect.

- **Servers no longer disappear on import.** Two servers that differed only by
  their IPv6 endpoint, secret or name were treated as the same server, because
  the identity key was built from the IPv4 host and port alone - the second one
  was dropped as a duplicate, or silently overwrote the first. Entries sharing
  an id but pointing at different endpoints overwrote each other as well.

- **An imported link no longer takes over as the active server.** One tap on a
  `tired://` link from a stranger made that server active even when the user had
  their own, and the next connect went wherever the link pointed. Import now
  only chooses the active server when there is nothing to choose between.

- **The server store survives a bad record and a failed keystore.** A single
  malformed entry ended the parse of the whole list, so a user could open the
  app to an empty server list with no error shown; records are now read one by
  one. If the encrypted store failed to open, the app quietly fell back to
  plaintext preferences and kept writing secrets there, with nothing said in the
  log or the UI. Reads and writes are also serialised now: a wave of latency
  pings could overwrite a concurrent edit, and could bring a just-deleted server
  back from the dead.

- **Updates are checked against our own signing certificate.** The APK hash and
  the download URL arrived in the same JSON from the same host, so the hash
  proved only that the file matched what that host said - and nothing compared
  the downloaded package to the signature of the app asking to install it. The
  update flow also treated every failure as "no update available" and retried
  permanent ones forever, and could pull a full APK over a metered connection.

- **The main screen stops getting stuck on "Connecting".** Three paths refused
  the attempt without ever moving the state - no config, a declined VPN
  permission prompt, a dismissed "another VPN is active" dialog - and the button
  stayed mid-connect until something else changed it.

- **Opening the server list no longer fires dozens of geolocation requests.**
  Every row rebind called an external IP-geolocation service with no cache, and
  the list refreshed on every finished ping. The server address was leaving the
  device to a third party far more often than anyone intended.

### Changed

- **Port hopping is gone from the client.** The setting existed, the generator
  had tests, and the service sent the core a `port_hop` command - which the core
  has never implemented and answers with "unknown command". The reply was not
  read: the UI reported a successful hop to a new port that had not happened.
  The honest state is no feature, so the path is removed. If it comes back it
  starts in the core.

- **The dead second VPN service is deleted.** `TiredVpnServiceJNI`, 438 lines,
  was not in the manifest and not referenced anywhere, and had already drifted
  from the live one - a legacy flag alias, no server pool, no MTU or IPv6
  handling, split tunnelling commented out.

- **Logs and notifications say less.** The connected notification no longer
  shows the tunnel address on the lock screen, and killing a stray core process
  no longer prints its command line - which contained the server secret in
  clear text, in the log file the app offers to share.

- **The bundled core is rebuilt when it changes.** The build step that compiles
  the Go core skipped itself whenever a library file already existed, so a
  release built on a machine with a stale one shipped that stale core without a
  word in the build log. It now compares the core revision it built from.

- Bundled core updated to 1.10.0.

## [1.9.0] - 2026-09-02

### Fixed

- **The VPN no longer dies when the network changes.** On a Pixel dropped between
  Wi-Fi and LTE the service could abort with a native `fdsan` crash and never come
  back - the tunnel was simply gone until the app was reopened, and often not even
  then. The cause was not the logger that showed up in every stack trace: the
  teardown path closed TUN file descriptors by number, walking `/proc/self/fd`,
  and so reclaimed descriptors that the core (or a parallel connect) still owned.
  The next `open()` in the process inherited a number fdsan believed was free, and
  the kernel killed the process. Descriptors are now tracked by handle and only
  the ones we actually own are ever closed. Verified on device across eighteen
  network transitions with no crash and a single stable process.

- **Connecting no longer gives up in the middle of a scan.** The service capped a
  whole connection attempt at ~25 seconds, but a full strategy scan on the core
  can run to about 46 seconds in the worst case, so a healthy connect was cut off
  partway through and reported as a failure. The socket read and overall timeouts
  are now derived from the core's own limits (50 s read, 60 s overall) instead of
  a round number, with the socket giving up before the outer deadline.

- **IPv6-only network changes are now noticed.** The link-properties watcher only
  compared IPv4 addresses, so a network that changed only its IPv6 prefix - common
  behind routers that hand out short-lived prefixes - never triggered a reconnect.
  IPv6 is now compared by prefix (not by full address, which would storm on
  RFC 4941 temporary-address rotation), and link-local addresses are ignored for
  both families.

### Changed

- Bundled core updated to 1.10.0.

## [1.8.1] - 2026-08-29

### Fixed

- **Endpoint rotation now actually happens on a phone.** With several servers
  configured, a handset that lost its network would re-dial the first one
  forever and never reach the rest - reproduced on a Pixel 9 dropped onto LTE:
  sixty dials, one address, five servers untouched. Two core defects were behind
  it, both fixed in core 1.8.1 and 1.8.2 which this build carries: endpoint
  health was discarded every time the service rebuilt the client, and a
  successful pre-flight probe lifted the cooldown that a failed scan had just
  applied. An address that only times out is also given up on quickly now,
  instead of being walked through all 21 strategies twice.

## [1.8.0] - 2026-08-28

### Added

- **Import takes several servers at once, in whatever shape they arrive.** There
  was no way to add more than one server per gesture. Setting up a four-node pool
  meant opening "enter URL" four times and typing a `tired://` link into it four
  times, and every one of those links had to be a link: a pasted JSON config, an
  exported backup or a base64 subscription blob was rejected by the box that only
  accepted `tired://`. Now one input accepts all of it and works out which it is
  by itself - one link, several links separated by newlines or spaces, a link
  quoted inside a chat message or a JSON string, a server JSON object in either
  spelling of its field names, a JSON array of servers, a `{"servers":[...]}`
  export bundle, and base64 of any of those, in the standard or URL-safe alphabet
  with or without padding. The clipboard button, the paste box, the add button on
  the server list and Restore in settings all go through it.
- **A preview before anything is written, for a batch as well as for one link.**
  It names each server that would be added or replaced, counts them, and lists
  what was skipped with the reason: a link with no secret, an entry that repeats
  an endpoint already in the same paste. Nothing is stored until you tap Import.
- **`tired://` links can be shared into the app.** TiredVPN now appears in the
  share sheet for plain text, so a link in a messenger reaches the import without
  a copy-paste round trip.

### Fixed

- **Config import over adb works.** It never has. The receiver was declared with
  `android:permission="com.tiredvpn.android.permission.VPN_CONTROL"`, which is
  signature-level; `adb shell` runs as uid 2000, holds no signature permission
  and cannot be granted one, so Android dropped every one of those broadcasts
  before delivery - while `am broadcast` printed `Broadcast completed: result=0`
  and looked like success. The documented usage examples in the source described
  a command that had never once imported anything. Checking the caller inside the
  receiver instead is not possible either: a `BroadcastReceiver` cannot learn who
  sent a broadcast. Import now has an exported activity, `ImportActivity`, which
  `am start` can reach:

      adb shell am start -n com.tiredvpn.android/.ui.ImportActivity \
        -a com.tiredvpn.IMPORT_CONFIG --es payload 'tired://1.2.3.4:995?secret=xxx'
      adb shell am start -n com.tiredvpn.android/.ui.ImportActivity \
        -a com.tiredvpn.IMPORT_CONFIG --es file /sdcard/Download/pool.json

  The signature permission stays where it was, because it is what stops another
  installed app from replacing your credentials silently, and the receiver still
  serves same-key automation. The new activity is exported, but reaching it is
  not the same as changing anything: it can only put the confirmation dialog on
  screen, and that dialog names every server it would touch. A `file` path inside
  the app's own private storage is refused, so the activity cannot be aimed at
  our own stored configs.
- **Re-importing a server no longer creates a duplicate of it.** A `tired://`
  link carries no id, so each parse minted a fresh one and the store treated it
  as a new server; importing the same pool twice left eight entries. Servers are
  now matched on the address and port they dial, which is the thing that makes
  two configs the same server. A different secret on a known endpoint is a
  rotated credential and updates it - with core 1.8.0 every pool node has its own
  key, so this is the normal case, not an oddity. A backup still round-trips by
  id, so a server that was moved to a new address is recognised. An update keeps
  the id (split-tunnel rules stay attached), keeps the measured latency, and does
  not overwrite a name you chose with the hostname a bare link defaults to.
- **A `tired://` link and a JSON config now describe the same server.** They
  understood different field sets: the link parser knew about `serverV6`,
  `preferIpv6`, `fallbackV4`, `tunIpv6`, ECH and the traffic shaper, and the JSON
  importer did not; JSON knew about debug logging, connection mode and port
  hopping, and the link did not. The same server therefore imported differently
  depending on which way it was sent. Both now carry every field, under one
  vocabulary that accepts the snake_case spelling from the ADB docs, the
  camelCase spelling `toJson()` writes, and the query-parameter name `toUrl()`
  writes.
- **The clipboard button imported one server no matter how many were on the
  clipboard.** It pulled the first `tired://` link out of the text and discarded
  the rest.
- **The add button on the server list looked for the literal text
  `serverAddress` to decide whether the clipboard held a config.** A list of
  links, a base64 subscription and a snake_case JSON config all failed that test
  and dropped the user into manual entry.

### Changed

- `ServerConfigActivity` is no longer exported. `tired://` deep links now open
  `ImportActivity`, which is the single entry point for anything arriving from
  outside the app.

## [1.7.1] - 2026-08-28

### Fixed

- **A server imported over ADB can now carry its IPv6 endpoint.** The import
  receiver understood thirteen keys, all of them IPv4, and silently dropped the
  four that decide how the server is reached over v6: `server_v6`,
  `prefer_ipv6`, `fallback_v4` and `tun_ipv6`. The settings screen has always
  been able to set them, and only for the active server, which made a scripted
  setup impossible to complete. It mattered because an entry node whose IPv4
  address has been blocked is reachable over IPv6 only, so an imported server
  pointed at a dead address and looked correctly configured right up to the
  first connection attempt. Each key also accepts the camelCase spelling
  `VpnConfig.toJson()` writes (`serverAddressV6`, `preferIpv6`, `fallbackV4`,
  `tunnelIpv6`), so a config exported by the app can be fed back in unedited. An
  empty `server_v6` stays a legal value and means the server has no v6 endpoint;
  a config that omits the keys entirely is imported exactly as before.

## [1.7.0] - 2026-08-27

### Changed

- **The failover pool now spans every server you have, not just the ones sharing
  a key.** 1.6.0 grouped the pool by secret and said so in the server list: a
  server whose key nobody else used got a pool of one and no fallback. That was
  never a property of the protocol, only of the core, which fixed the secret when
  it built a transport and refused a config whose entries disagreed. Core 1.8.0
  makes the key a property of the dial - it travels with the connection and comes
  from whichever server is being reached - so the app now writes every configured
  server into the pool, active one first, each with its own key. Switching server
  switches the key with it.
  - The server list drops the per-row "in the failover pool" badge along with the
    rule that made it worth showing. Every server is in the pool now, so the
    badge would sit on every row; the count of servers the connection can move to
    stays on the active row, where it still says something.
  - A server saved without a secret is left out of the pool. It has no key to
    write, so it would go into the file borrowing another server's and fail to
    authenticate when the connection reached it.
  - Bundles core 1.8.0.

### Fixed

- **The server secret no longer reaches the exported log.** Eight places rendered
  the core's argument list into `FileLogger` with the key in plain text, and that
  log is what gets exported and attached to a bug report. The value after
  `-secret` is now replaced before the line is written. The shell-wrapper path
  needed its own handling: it joins every argument into a single string before
  launching, which leaves nothing for element-wise redaction to match, so its log
  line is now rendered from the arguments and joined afterwards.

## [1.6.0] - 2026-08-27

### Added

- **The app hands the core a pool of servers, so a dead exit no longer means a
  dead connection.** Until now it dialled one server and stayed there: if that
  exit went down, nothing switched and the tunnel stayed broken until someone
  picked another server by hand. The server list is now written out as a config
  the core reads, and the core does the dialling, the failing over and the
  cooldown on its own.
  - The pool is the active server plus every other server that shares its
    secret, active first. That is not a UI choice but a property of the core: a
    secret is fixed when a transport strategy is built, so it cannot change on a
    switch, and a pool with mixed secrets is refused outright. A server with a
    secret nobody else uses gives a pool of one and behaves exactly as before.
  - The server list now marks which entries travel together, and says so plainly
    when a server stands alone, rather than leaving a fallback to be assumed.
  - No background polling of the other servers. Probing N hosts on a timer is a
    periodic fan-out with nothing behind it, which is the kind of pattern worth
    not having on a censorship-resistant client; the core finds out a server is
    back by dialling it when it needs one.

## [1.5.0] - 2026-08-27

### Added

- **IPv6 inside the tunnel actually works now.** The dual-stack switch in
  settings has been sending `-tun-ipv6 dual` to the core, but the core it was
  talking to did not know the flag: the app shipped Go core 1.3.27 and
  `-tun-ipv6` only arrived in core 1.4.0. The core's argument parser logs an
  unknown token and carries on, so nothing broke - the option was simply inert,
  and every session stayed IPv4-only no matter how the switch was set. This
  release bundles core 1.7.1, where the flag exists. With the switch on and an
  exit configured with `-ip-pool-v6`, the tunnel negotiates an IPv6 address pair
  (handshake `0x04`) and IPv6-only destinations are reached through the VPN
  instead of around it.
  - **Your exit and any relay in front of it must be on core 1.4.0 or newer.**
    A relay older than that does not fall back to IPv4 - it forwards the
    dual-stack extension bytes downstream as tunnel traffic and corrupts the
    session. Against an up-to-date exit that simply lacks `-ip-pool-v6`, the
    negotiation declines cleanly and the session stays IPv4-only.
  - The switch stays off by default. Unlike desktop, where core 1.5.0 made
    `dual` the default, the Android path treats an absent flag as `off`, so
    leaving the switch alone keeps the previous behaviour exactly.
- **IPv6 can no longer leak around the tunnel.** The VPN interface now claims
  `::/0` even on an IPv4-only session, so an application with a working IPv6
  default route can no longer reach the internet outside the VPN and hand out
  the real address (core issue #55). On Android this is `VpnService`'s job
  rather than the core's - the core's nftables-based `block` policy is Linux
  only.

### Security

Both of these come from core 1.4.2 and affect every REALITY connection the app
makes, IPv6 or not.

- **Every REALITY connection made by one client reused a single ChaCha20
  keystream.** The data key and nonce were derived from inputs that were
  constant for the life of the process, so each connection started encrypting
  from counter zero with the same keystream. XORing two captured connections
  cancelled it and left the XOR of two plaintexts, under which sits smux with
  fixed header fields - recovering traffic did not require the key. The X25519
  key pair is now generated per connection.
- **REALITY data records are authenticated and forward-secret (data layer v2).**
  Records are sealed with ChaCha20-Poly1305 under a key from a per-connection
  X25519 exchange with an explicit record counter. Before this an active
  middlebox could flip bits inside the tunnel unnoticed, and a leaked password
  decrypted any recorded traffic. An unupgraded exit keeps working; version
  negotiation rides inside the existing padding block.

### Fixed

Client-visible fixes accumulated in the core between 1.3.27 and 1.7.1.

- **A client given both an IPv4 and an IPv6 endpoint could fail to connect at
  all.** The connectivity gate probed over one family while waiting on a blocked
  address of the other, so it never got past the pre-flight check (core 1.4.0).
- **A fragmented handshake response silently desynced the tunnel.** Only the
  9-byte prefix was guaranteed to be read, leaving the tail in the stream for
  the packet loop to parse as a frame header. Responses are now read to
  completion (core 1.4.0).
- **A profile with several servers waited on the first one instead of moving
  on.** An unreachable first server was indistinguishable from a dead network,
  so the client sat in the wait loop with healthy alternatives configured and
  untried (core 1.5.1).
- **Clients sharing one secret shared one tunnel address and evicted each other
  every thirty seconds**, each eviction landing on a connection that had just
  carried traffic, until the client gave up on REALITY and fell back to a slower
  transport (core 1.5.0).
- **The log claimed a local proxy that Android never runs.** `Listening on
  <addr> (SOCKS5/HTTP)` was printed at startup before any socket was opened and
  regardless of mode, so it appeared in every Android session, which uses the
  control socket instead (core 1.7.0).

### Changed

- **Bundles Go core 1.7.1** (was 1.3.27). Beyond the items above it brings a
  server pool with the IPv4/IPv6 fallback folded into it, and a transport family
  that is re-decided at runtime rather than settled once per process - an IPv6
  path that dies mid-session no longer keeps being dialled until a restart.
- **QUIC/Salamander changed its wire format and does not negotiate.** Core 1.6.0
  widened the UDP authenticity tag from 2 bytes to 8, closing a 1-in-65536 false
  accept that let a packet from a foreign secret through. If you have QUIC
  enabled in settings, the exit must be on core 1.6.0 or newer or no packet will
  match and the transport will not carry anything.
- `scripts/build-jni.sh` reads the core version from the core checkout instead
  of a hardcoded string, which had been reporting `1.3.0-android-jni` for builds
  of every version since.

## [1.4.8] - 2026-08-07

### Added

- **Split tunneling is now per server profile instead of one global setting.** The mode and the app set were stored under a single pair of keys, so a split configured for a home profile silently applied to every other profile too — the wrong apps ended up in or out of the tunnel after switching exits. Keys are now namespaced by profile id (`split_tunneling_mode_<id>` / `split_tunneling_apps_<id>`), the split screen edits the active profile and names it in the header, and `VpnService` applies that profile's split in both TUN and proxy modes. Config imported over broadcast writes its split into the profile it arrived with. Upgrades read the old global keys as defaults, so an existing configuration survives.

### Fixed

- **The app list in the split screen took seconds to open and stuttered while scrolling.** Building the list called `getLaunchIntentForPackage()` once per installed package — one IPC per app, hundreds of them — and loaded every icon on the UI thread. The launcher lookup is now a single `queryIntentActivities()` call, the unnecessary `GET_META_DATA` flag is gone, and icons load off the UI thread behind an `LruCache`.

### Changed

- Bundles Go core 1.3.27 (configurable `-redis-db` / `-redis-prefix` on the server side; no client-visible change).
- Dependency bumps: Android Gradle Plugin 9.3.1, `androidx.constraintlayout` 2.2.2, `actions/setup-java` 5.6.0, `actions/setup-go` 7.

## [1.4.7] - 2026-07-11

### Added

- **seqovl (TCP sequence overlap) strategy, selectable in settings.** Bundles Go core 1.3.23, which adds the seqovl desync strategy adapted from zapret2: the client prepends a secret-marked decoy TLS record before the REALITY ClientHello so a censor's stateful reassembler fingerprints the junk, while the server drops the decoy and reads the real first flight. Android runs the app-framing (level B) path — the packet-level variant is Linux-only. `seqovl` now appears in the strategy picker; in auto mode the core already uses it as a REALITY fallback.

- **Connect watchdog for the unkillable native-thread case.** When `connect()`'s coroutine body never runs - previously requiring `adb shell am force-stop` to recover - a dedicated watchdog thread (independent of `Dispatchers.IO`/`scope`, since that pool is the thing suspected of being wedged) now kills the process after 14s if `COROUTINE BODY ENTERED` hasn't fired. The Go core is getting its own deadline fix for the underlying blocking native call; this is the client-side backstop for whatever residual case still slips through.

## [1.4.6] - 2026-07-03

### Fixed

- **Native crash (SIGABRT/fdsan) on reconnect.** `forceResetCore()`'s cleanup spawned a background thread that kept force-closing any `/dev/tun` file descriptor for a full second after a reset, with no awareness of a new connection attempt establishing its own TUN interface in that same window. When a fresh `vpnInterface` landed inside that window, the sweep adopted its still-owned fd and Android's `fdsan` aborted the process (`failed to exchange ownership of file descriptor ... expected to be unowned`), killing the app - reliably reproducible on every reconnect, not just under flaky Wi-Fi. The sweep now stops as soon as a new `vpnInterface` is assigned, since the fd is no longer an orphan at that point.
- **Bundles core 1.3.19** (was 1.3.11-linked at the time of 1.4.4/1.4.5's JNI rebuild). The native library is rebuilt from the latest core, bringing the fix for the Android JNI entrypoint dropping `AndroidMode` when building its strategy config - which let already-dead QUIC/QUIC-Salamander attempts sort first on `-no-quic` servers and stall the connection for 15-20s before ever reaching a working strategy like REALITY - plus the HTTP/2-stego handshake now honoring the caller's timeout instead of a hardcoded 30s wait.

## [1.4.5] - 2026-06-29

### Fixed

- **Connect button no longer gets stuck in "Disconnected".** The button could stop toggling the core - it sat in Disconnected and only a "force stop" from Android settings recovered it. Root causes: a reconnect mutex that stayed locked forever when a reconnect hung inside a non-cancellable cleanup block, the process-global VPN state surviving a dead service instance, and `connect()` not clearing orphan Go goroutines / TUN fds before starting. Every connect now runs an authoritative clean reset first (kills orphans, frees the mutex, resets the coroutine scope), `onStartCommand` dedups duplicate start intents, a blocking `stopAndWait()` is now time-bounded, and a queued reconnect aborts if the user disconnected during its backoff. A new instance also reconciles stale global state on create.
- **Emergency reset.** Long-pressing the connect button now force-resets the core, so a wedged state no longer requires force-stopping the app from system settings.

### Added

- **Connecting screen shows the current phase.** Instead of a static "Connecting…", the status line cycles animated phases pulled from the connection pipeline - Resolving server, Starting core, Negotiating, Creating tunnel, Handshake - and shows "Reconnecting" when the tunnel is re-establishing.

## [1.4.4] - 2026-06-26

### Changed

- **Bundles core 1.3.11** (was 1.3.7). The native library is rebuilt from the latest core, bringing:
  - Idle connections no longer reconnect every ~30 seconds on the HTTP/2-stego strategy (the server now echoes the client keepalive), so a paused session no longer blips on the next request.
  - A pass of hot-path performance work across the transport strategies (REALITY encrypt/read, Morph, Geneva, WebSocket, stego, probes) and larger client TCP socket buffers (4 MB) for better throughput on high-latency links.
  - A shared TLS session cache so reconnects skip the full TLS handshake.

## [1.4.3] - 2026-06-26

### Changed

- **Bundles core 1.3.7.** The release build compiles the JNI native library from the core's latest tag, so this version ships the 1.3.6 -> 1.3.7 core fixes: TUN throughput (HTTP/2 receive window raised to 4 MB plus kTLS), client lifecycle (no longer flips to offline before a connection exists, and routes are installed only after the handshake completes), a stable sticky TUN address on REALITY-mux, QUIC reconnect backoff, IP-pool TTL handling, and admission control that bounds memory under DPI reconnect storms (OOM protection). No app-side code changed; the gains arrive through the rebuilt `.so`.

## [1.4.2] - 2026-06-23

### Changed

- **MTU setting now accepts 1280-9000.** The custom-MTU dialog validated 576-1500, which blocked the larger MTUs enabled by the new server-side `-tun-mtu` (core 1.3.3) and allowed values below the safe 1280 floor. The range is now 1280-9000 (empty = auto, kernel default 1280). The MTU value already flowed correctly to both the `-tun-mtu` argv and `VpnService.Builder.setMtu`, so no plumbing change was needed.

## [1.4.1] - 2026-06-23

### Fixed

- **Client arguments with spaces no longer get mangled.** The JNI bridge joined argv into one space-separated string, so values containing a space (notably morph strategy IDs like `Yandex Video`) were split apart inside the core. Argv is now passed as a string array end to end, and Settings uses the exact core strategy IDs instead of a space-free-prefix workaround. Requires core >= 1.3.2 (paired JNI signature change).
- **TUN download throughput.** The VpnService interface came up at MTU 1400 while the core clamped MSS against a 1500 TUN MTU, so outer packets exceeded the path MTU and fragmented / black-holed (worst on download). The interface now defaults to MTU 1280, matching the core, so framing and MSS clamping agree.

## [1.4.0] - 2026-06-22

### Added

- **Share a server.** Long-press a server in the list to Share or Copy link - it serializes the full config (including the new shaper/ECH/IPv6/MTU/DNS settings) into a `tired://` link and hands it to the system share sheet or the clipboard.
- **Backup and restore configs.** Settings has a "Backup configs" entry that exports every saved server to a `tiredvpn-backup.json` file and opens the share sheet (with a warning that the file holds server secrets), and a "Restore configs" entry that picks a backup file and imports all servers from it. Restore accepts the JSON array backup, a single JSON object, or a file of `tired://` links, and is idempotent (servers keep their id, so re-importing updates in place instead of duplicating).

### Fixed

- **Import from clipboard failed with "doesn't contain tired:// URL" even when a link was present.** The clipboard text was matched with a strict `startsWith("tired://")` against the raw, untrimmed first clip item, so a leading newline, surrounding share text, or a link in a second clip item all broke detection. Import now trims, coerces every clip item to text, finds a `tired://` link anywhere in the content (case-insensitive), and falls back to JSON config. Parsing moved to a single tolerant `VpnConfig.fromUrl`/`extractTiredUrl` used by every import path.
- **Auto Fallback toggle did not stick.** The switch in Settings had no change handler, so toggling it never wrote back to the active server - on leaving and returning to Settings it reverted to the saved value, and the runtime command kept the old fallback behavior. The toggle now persists to the active server immediately (matching the other per-server switches).

### Changed

- Raised `compileSdk` and `targetSdk` to 37 (Android 16 QPR).
- Bumped dependencies: androidx.core 1.19.0, material 1.14.0, lifecycle 2.11.0, and the Gradle wrapper to 9.6.0.

## [1.1.0] - 2026-06-16

### Added

- **Exposed core client settings that the app previously could not reach.** The embedded core supports far more than the app surfaced; these are now configurable per server:
  - **Traffic Shaper** preset (`youtube_streaming`, `chrome_browsing`, `random_per_session`) with optional seed.
  - **ECH (Encrypted Client Hello)** - toggle, config (base64), and public name to hide the SNI.
  - **IPv6 endpoint** - separate IPv6 server address, prefer-IPv6 and IPv4-fallback toggles.
  - **QUIC SNI fragmentation** toggle for GFW-style SNI filtering.
  - **Port hopping** UI - the config and Kotlin logic already existed but had no controls (enable, port range, interval, strategy, seed).
  - **Custom MTU** and **custom DNS** overrides for troubleshooting (helps WebRTC/Meet).
  - **Full, corrected strategy list** - replaces the stale/partial picker. Adds QUIC Salamander, SSH/IMAP camouflage, the four Traffic Morph profiles, all Protocol Confusion variants, State Exhaustion, and Geneva (Russia/China/Iran), with values verified against the core's strategy IDs.
  - **Complete RTT-masking profiles** (7) - adds `beijing-baidu` and `tehran-aparat`.

### Fixed

- **Non-arm64 devices could not connect.** Every TUN connect extracted a standalone binary from assets, but only the arm64 build was bundled, so on armeabi-v7a and x86_64 the extraction threw and the connection aborted. The binary was dead weight anyway - the core runs via JNI from the per-ABI `libtiredvpn.so` and the extracted path was discarded. Removed the extraction (and the 13 MB committed binary); all ABIs now connect.
- **Google Meet (and other Google apps) failed to start calls under split tunneling** - in include/allowlist mode only the selected app was tunneled, but Google apps offload signaling, push and STUN/auth to Google Play Services, which stayed off-tunnel. Media and signaling then left from different external IPs and WebRTC ICE never completed. When a Google app is allowlisted, the tunnel now also includes `com.google.android.gms`, `com.google.android.gsf`, and `com.android.vending`.
- **Client settings were silently dropped on the embedded path.** The core's JNI argument parser recognized only ~11 flags with no default case, so QUIC, RTT masking, cover host (a `-cover`/`-cover-host` name mismatch), and fallback toggles never reached the core; proxy mode was also forced into TUN and lost its listen address. Fixed in the embedded core (tiredvpn v1.2.1 / v1.2.2), which now honors the full flag contract and logs unknown flags.

### Changed

- **Embedded Go VPN core updated to v1.2.2** (was ~v1.0.x). The native `libtiredvpn.so` is rebuilt from the latest core for all three ABIs. Highlights relevant to the mobile client:
  - **REALITY throughput fix** - ChaCha20 TLS framing plus per-request TCP, removing the bottleneck on the default strategy.
  - **Traffic Morph reliability** - larger TLS ClientHello fragments (2 -> 64 bytes) cut the segment count from ~750 to ~24, fixing reliable timeouts on high-latency mobile paths; `youtube_streaming` shaper is now the morph default.
  - **Traffic Shaper** - behavioural masking layer that decouples the DPI traffic shape from the TLS transport.
  - **New bypass strategies** - SSH and IMAP protocol camouflage, plus a capabilities probe with anti-probe dispatch so the client negotiates a working strategy faster.
  - **Salamander keyed-tag framing** and mux carrier budget recycling.
  - **Cleartext TLS fingerprints removed** from all strategies (core phase 3).
  - **Machine-readable strategy benchmarking** (`-benchmark-json`) feeding automatic strategy selection.

- **Dependency updates**
  - com.google.android.material: 1.13.0 -> 1.14.0
  - OkHttp: 5.3.2 -> 5.4.0
  - Gradle wrapper: 9.5.0 -> 9.5.1

  - Core security and stability fixes from v1.2.x: SSRF in the proxy path, ICMP nonce reuse across sessions, a goroutine leak, and several DPI strategy correctness bugs.

### Compatibility

- The Traffic Morph wire protocol is now v2 (server-to-client early ack). Morph strategies require a server running **tiredvpn v1.2.0 or newer**; older servers will desync on morph. The other strategies (REALITY, port-hop, stego, ws, raw) remain interoperable.

## [1.0.6] - 2026-05-15

### Fixed

- **VPN icon stays in status bar after disconnect** — Go goroutines from parallel connection attempts held dup'd `/dev/tun` file descriptors after `vpnInterface.close()`. Android kept the VPN system binding alive while any fd pointed to `/dev/tun`. Fixed by enumerating `/proc/self/fd` and force-closing all TUN fds via `ParcelFileDescriptor.adoptFd()`, repeated in a background thread to catch fds closed asynchronously by Go's shutdown sequence.

### Changed

- **Dependency updates**
  - Android Gradle Plugin: 9.2.0 → 9.2.1
  - OWASP Dependency Check: 12.2.1 → 12.2.2

## [1.0.5] - 2026-05-05

### Changed

- **Dependency updates**
  - Android Gradle Plugin: 9.1.0 → 9.2.0
  - Gradle wrapper: 9.4.1 → 9.5.0
  - OWASP Dependency Check: 9.1.0 → 12.2.1
  - Robolectric: 4.14.1 → 4.16.1
  - aquasecurity/trivy-action: 0.35.0 → 0.36.0
  - softprops/action-gh-release: 2 → 3

### Docs

- Replaced em-dash double-hyphens with single hyphens in README prose for cleaner rendering.

## [1.0.4] - 2026-04-10

### Fixed

- **App crash on startup under R8 minification** — `androidx.startup.InitializationProvider` failed to instantiate `WorkDatabase` because R8 obfuscated the class name, breaking Room's `Class.forName(...)` lookup. Added keep rules for `androidx.work`, `androidx.room`, and `androidx.startup` so release builds survive minification.

## [1.0.3] - 2026-04-09

### Added

- **OWASP Dependency Check** — scans Gradle dependencies against NVD for known CVEs
- **30 new unit tests** — VpnConfig (JSON round-trip, validation), VpnState (sealed class), CountryDetector (emoji flags, country mapping), UpdateConfig (data class defaults)
- Total test suite: 37 tests (was 7)

### Fixed

- Fixed `PortHopper` sequential strategy starting outside port range
- Replaced deprecated `Build.CPU_ABI` with `Build.SUPPORTED_ABIS`
- Replaced deprecated `stopForeground(Boolean)` with `STOP_FOREGROUND_REMOVE`
- Pinned `trivy-action` from `@master` to `@v0.35.0` (supply chain hardening)
- Eliminated all Node.js 20 deprecation warnings in CI

## [1.0.2] - 2026-04-08

### Changed

- Upgraded OkHttp from 4.12.0 to 5.3.2
- Upgraded AndroidX Lifecycle from 2.7.0 to 2.10.0
- Added unit tests step to CI pipeline
- Release workflow now gates on passing tests and lint before building APK

## [1.0.0] - 2026-04-03

### Added

- **Go VPN core embedded via JNI** — libtired.so compiled as a C shared library for arm64-v8a, armeabi-v7a, and x86_64
- **20+ DPI bypass strategies** from the Go core with automatic selection and mid-session fallback
- **Smart auto-reconnect** — survives airplane mode, Wi-Fi/mobile switches, and device sleep; WorkManager watchdog restarts the tunnel if killed
- **Split tunneling** — per-app routing: choose which apps go through the tunnel
- **Port hopping** — dynamically switches server ports to evade port-based blocking
- **Persistent VPN notification** — foreground service with real-time status, latency, and data counters
- **Auto-update system** — checks for new APKs, downloads and prompts installation
- **Deep link & QR import** — configure servers via `tired://` URI scheme or QR code scan
- **ADB control** — connect, disconnect, and import configs via broadcast intents (Android TV, headless setups)
- **Boot auto-start** — optional connection on boot, including direct boot before unlock
- **Android TV support** — leanback launcher, D-pad navigation, banner icon
- **Material Design UI** — server list, settings, log viewer, split tunneling picker, onboarding wizard
- **Minimum Android 7.0** (API 24), target Android 13 (API 33)
