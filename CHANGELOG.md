## LSPatch 1.0 — rebuilt on Vector

LSPatch 1.0 is a ground-up rebuild on Vector, the framework that succeeds LSPosed. A patched app now
loads modern libxposed (API 102) and legacy Xposed modules through Vector's own runtime — the same
loader the rooted framework uses — with no root and no Zygisk, and a module gets a real `IXposedService`.

> [!IMPORTANT]
> The runtime inside a patched app is entirely new. Apps patched with an older LSPatch won't pick it
> up — re-patch every app you run modules in.

### 🔀 Embedded or Manager mode, chosen at patch time
- **📦 Embedded.** Modules are baked into the APK; the app is self-contained and needs nothing else
  installed. Changing its modules means re-patching.
- **🛰️ Manager.** The patched app binds the manager at runtime, so you change a module's scope live —
  no re-patch — and hot-reload it into a running app, with module storage shared across every patched
  app. The manager has to stay installed.

### 📱 Manager rebuilt on Vector's shared UI
Home, a module Store, Manage, and Logs. Manage lists your patched apps and modules with reach
thumbnails; open any app to a detail page to edit its modules as a draft, re-patch, update its loader,
export the APK, or restore the original.

### 🛠️ One patch flow
Every path — a new app, an APK from storage, a re-patch, a loader update — lands on one screen, and
patching and installing are now separate steps. Patched APKs live in app-private storage (no
storage-permission dance; SAF export is its own button), leaving mid-patch no longer cancels the job,
and a re-patch needs nothing on hand — the patched APK carries the originals and settings it was built
from.

### 🔓 Rootless reach
- **🗄️ Browse a patched app's private data.** An opt-in patch injects a Storage Access Framework
  provider, so any SAF file manager can open the app's `/data/data/<pkg>` without root.
- **🫥 Cloak the manager.** The manager can reinstall itself under a custom or random package name —
  migrating its settings, keystore, and bound apps, with one-tap revert — so a scan for
  `org.lsposed.lspatch` comes up empty.
