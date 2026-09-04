# Architecture

Valkyris is a single-home system. The Go process is the security boundary: apps
never receive camera passwords and MediaMTX is not published on a host port.

1. The camera and its encrypted credentials are persisted immediately with a
   `pending` setup state, so the Android app can open it without waiting for ONVIF.
2. The backend publishes the `queued`, `probing` and `stream` steps over the
   realtime channel while also storing them in SQLite. ONVIF probing then stores
   capabilities, service addresses and the media profile token. A terminal error
   is sanitized and persisted on the camera instead of removing it.
3. MediaMTX pulls the selected RTSP profile once and maintains LL-HLS plus a
   short rolling fMP4 buffer.
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
