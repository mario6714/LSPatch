### 🔗 A patched app no longer needs the manager alive
A manager reaped in the background, or force-stopped, left a manager-backed app starting with no
modules after five seconds spent waiting. It now records which modules it runs and where their APKs
are, loads from that copy when the manager does not answer, and waits one and a half seconds. The
binding is re-established rather than made once, restoring the hot-reload channel and each module's
service; where Shizuku is granted, the shell service restarts a manager it finds gone.

### 🪵 Logs that contain the app you patched
The framework stream's uid set was built before the package scan that fills it, so it held the manager
alone and dropped what a patched app and its modules wrote. It is built after the scan and refreshed
as apps are patched. The release loader keeps its own lines too — signature bypass, module loader,
service client. You can filter by writer, and a native crash dump reads as one entry rather than a
page of fragments.

### 🌐 The Store resolves over HTTPS
Catalogue, downloads and self-update go through Vector's OkHttp client and its DoH resolver, so DNS
blocking can no longer leave the Store quietly empty.

### 🩹 A patch reads the file you chose
- **Target apks resolved when the patch runs** — a recorded path is a name, not a location, so
  an app the system moved is found again.
- **A picked apk** is kept in no-backup storage, not the cache the platform may evict.
- **An apk carrying a nested apk** is no longer read as an app bundle.

### 🧹 Also
- Every wait on a system service has a deadline, so a lost install status is re-checked rather than
  hanging.
- The uninstall prompt appears only when the signing certificates differ; a matching custom keystore
  updates in place.
- Manage no longer crashes while listing an integrated patch's modules.
- Shizuku permission is re-read at each use, and keep rules let the user service survive a minified
  build.
- A "Rolling" loader badge that all 18 locales mistranslated as a verb is gone.
