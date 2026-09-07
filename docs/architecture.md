# Architecture

Valkyris is a single-home system. The Go process is the security boundary: apps
never receive camera passwords and MediaMTX control/RTSP ports stay internal. Only WebRTC media port 8189 UDP/TCP is published.

1. The camera and its encrypted credentials are persisted immediately with a
   `pending` setup state, so the Android app can open it without waiting for ONVIF.
2. The backend publishes the `queued`, `probing` and `stream` steps over the
   realtime channel while also storing them in SQLite. ONVIF probing then stores
   capabilities, service addresses and the media profile token. A terminal error
   is sanitized and persisted on the camera instead of removing it.
3. MediaMTX pulls the selected RTSP profile once. An internal FFmpeg process
   copies H.264 without re-encoding, converts camera audio such as G.711 to Opus,
   and republishes one normalized WebRTC stream while preserving the rolling fMP4
   buffer used for event clips. WHEP signalling is authenticated by Go; after ICE
   succeeds, media flows directly between MediaMTX and Android or the browser. Snapshots also read this internal stream; they do not open another RTSP session to the physical camera.
4. ONVIF events and local audio/video detectors submit normalized detections to
   the rule engine.
5. Matching rules create an event, materialize a 5-second pre-roll and 10-second
   post-roll clip, and enqueue per-device push deliveries. Overlapping events
   extend and share the same clip while retaining their own snapshots.
6. The Android app receives a minimal encrypted signal, then retrieves the
   authenticated event over LAN or VPN.
7. On app resume, the authenticated API compares both server and client versions
   with the latest stable GitHub release. An actionable update event is shown
   when needed. Only an administrator can ask the internal updater sidecar to
   pull and recreate the backend; the app downloads the signed APK directly
   from the trusted GitHub release URL and Android still requires installation
   confirmation.

Camera credentials and push secrets are encrypted with AES-256-GCM. API bearer
tokens are stored only as SHA-256 hashes. A fresh installation has no preset
credential: the first Android client atomically creates the home password and
becomes the administrator. Only a bcrypt hash is retained in SQLite. An
authenticated administrator may then create short-lived, single-use invitations;
the Android app combines the invitation code with its already-known server URL
and renders the QR locally. The Go backend exposes no setup or QR page.

The updater is not published on a host port. Its random token exists only in the
installation `.env` and the backend/updater containers. It receives a validated
release tag, pulls the pinned GHCR image through the Docker socket, atomically
updates `VALKYRIS_VERSION`, and recreates the backend without deleting the
SQLite/media volume.


The Astro viewer has a separate build (web/viewer → web/dist-viewer) served
by Go at /app/. Its authenticated cookie is HttpOnly/Secure/SameSite=Strict,
with renewable 30-day expiry; middleware enforces read-only access. WHEP
negotiation and session deletion are playback operations. The landing build
remains separate.

Android uses API mutation responses to update repository flows rather than
immediately fetching the same lists again. Camera/event websocket listeners
filter relevant messages; the camera list refreshes every 15 seconds as well.
An idle websocket is not disconnected merely because no event was emitted.

The media configuration lives in mediamtx.yml. Camera setup patches an existing
path and creates only when it is absent; it does not repeatedly patch global
transport settings. The WHEP player uses collected ICE candidates after the
bounded gathering wait, without treating a slow STUN response as a failure or
switching to snapshots/HLS.

See location-tracking.md, motion-regions.md and web-viewer.md for persistence,
detector limitations and rate budgets. Server validation, media path validation,
session authentication and database migrations are intentional boundaries.
Legacy /people endpoints remain for compatibility; current clients use /users.
