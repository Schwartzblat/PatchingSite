# Patched Store (Android)

Store client for the PatchingSite backend: sign in, browse patched builds, manage the
subscription, download and install — both plain APKs and split-APK bundles.

## Toolchain

Gradle 9.6.1, AGP 9.4.1, Kotlin 2.4.20, JDK 21, compileSdk 37 / minSdk 30 /
targetSdk 36.

Three of those are load-bearing and were each found the hard way:

- **No `org.jetbrains.kotlin.android` plugin.** AGP 9 supplies Kotlin itself and
  fails the build if that plugin is applied.
- **compileSdk 37, not 36.** androidx 2026.09 and okhttp-android 5.5.0 both refuse
  to compile against 36. Install it with
  `sdkmanager "platforms;android-37.0"`.
- **`org.gradle.jvmargs=-Xmx4096m`.** The default heap dies compiling Compose.

## Building

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Release builds are minified; see "Release builds and R8" below.

## Pointing it at a server

Release builds use `https://androidmod.site`. Debug builds default to
`BuildConfig.DEFAULT_BASE_URL` in `app/build.gradle.kts` and can be redirected at
runtime from the Settings screen. The backend must be started with `--host 0.0.0.0`
or the phone cannot reach it. Cleartext HTTP is permitted in debug builds only, via
`src/debug/res/xml/network_security_config.xml`.

## Design

Dark only. `client/src/index.css` pins `color-scheme: dark` and explains why, so
this client takes the same decision and the same tokens rather than maintaining a
second palette: `ui/theme/Color.kt` is that file's variables ported one for one,
and the mapping onto Material 3 roles lives in `ui/theme/Theme.kt`. There is no
light scheme and no `isSystemInDarkTheme()` branch anywhere, and
`res/values/themes.xml` pins the pre-Compose window the same way so cold start
cannot flash light on a light-mode device.

The CSS expresses surfaces as translucent white over a near-black page. Compose
wants opaque colours, so each token is the CSS value already composited over the
layer it sits on - `PapInput` is `rgba(255,255,255,0.06)` resolved over `PapCard`,
not over `PapBg`. `PapOnAccent` is near-black rather than white because white on
`#1791fc` is 3.3:1 and fails contrast, while near-black is 6.2:1.

Icons are hand-authored vectors in `res/drawable/ic_*.xml`, in one stroke language
at 1.9dp. `material-icons-extended` is deliberately not a dependency: it is ~30 MB
for a handful of glyphs and is deprecated upstream. App artwork in
`res/drawable-nodpi/app_*.webp` is the same files `client/public` serves, keyed by
package name in `ui/Common.kt` - by package rather than by the catalogue's `key`
because `LibraryEntry` carries a package and no key, so one map serves both
screens. An unfamiliar package falls back to a monogram.

`GET /api/apps` serves no icon URLs by design; the server comment says the Android
client brings its own, and this is that.

The launcher icon is an adaptive icon drawn from the same gradient
(`res/drawable/ic_launcher_*`), with a `monochrome` layer so Android 13+ themed
icons work. It replaced `@android:drawable/sym_def_app_icon`. The site's
`favicon.ico` was not reused: it is the stock Android mascot and heavily dithered.

Every screen's title is a `TopAppBar` owned by the single `Scaffold` in
`AppNavigation`, not text inside each screen's column. That keeps one app bar in
the tree so window insets are applied once - which matters because targetSdk 36
draws edge to edge unconditionally. The signed-out screens sit outside that
Scaffold and inset themselves with `safeDrawingPadding()`.

## Signing in

The bearer token lives in `EncryptedSharedPreferences` under an Android keystore
key and is never written to disk in the clear. The keystore can be damaged (a
vendor bug, a lock-screen change on some devices, a prefs file that outlived its
key), so `TokenStore` recovers instead of failing for good. A store that will not
open is tried again as it is, because the keystore also throws transient errors
(around a keystore daemon restart, or just after boot), and wiping it over one of
those would sign the user out for nothing. Only a second failure gets the file
and master key deleted and the store opened from scratch; if that fails too, the
token is kept in memory for the rest of the process, so signing in still works and
the user signs in again after the next launch. A stored value that no longer
decrypts is discarded and read as signed out, where it used to throw at launch and
crash the app until its data was cleared. The first read opens the store, so the
launch restore runs on a background thread.

At launch the app renews the stored token (`/api/auth/renew`) and then reads the
user. Only a 401 ends the session, and the login form then says "Your session
expired. Please sign in again." That 401 is also how the server refuses to renew a
session past its 90-day cap, which used to land the user on the login form without
a word. Anything else (no network, a 5xx while the server restarts) keeps the
session and shows "Can't reach androidmod.site" with the reason, a Retry that runs
the same restore again, and a Sign out for a server that keeps failing. Dropping to
the login form with "Network error" read as having been signed out.

The login form's "Forgot password?" opens `androidmod.site/forgot-password` in a
Custom Tab. The reset link the server emails opens a page on the website, so the
flow ends in the browser regardless.

## Email verification

New accounts must verify their email before they can subscribe; they can sign in
and browse meanwhile. After registering, the login form says where the link went,
or, when the server's `verification_sent` is false, that email isn't available
right now and a link can be sent later from the Plan tab.

While `/api/users/me` reports `email_verified: false`, the Plan tab opens with a
"Verify your email to subscribe" card naming the address, with a "Resend link"
button (`POST /api/auth/resend-verification`). Its answers appear inside the card:
the server's confirmation, a 429 as "check your inbox for the last one", a 503 as
the server's message with the support address. A 409 (already verified) marks the
user verified at once and re-reads them. Subscribe stays, outlined rather than
filled, and enabled: the app's copy of the user can lag a link opened in the
browser, and the checkout endpoint's 403 has the final say.

The link opens on the website, never in the app, so the Plan tab re-reads an
unverified user every time it resumes, which is also every visit to the tab.
A missing or null `email_verified` counts as verified, so a server that predates
verification shows nobody the card. Verified users see nothing of any of this.

## How downloading works

`DownloadRepository` enqueues a job, polls `GET /api/downloads/{id}` until it is
ready, then streams the file. A failed poll is retried when it is transient (no
answer, or a 5xx such as nginx's 502 while uvicorn restarts) for up to six failures
in a row, about 20 seconds; one blip used to fail a build the server was still
preparing. A 4xx is final, except that a 404 re-enqueues the job once, because a
server restart forgets its in-process jobs.

`ApiClient.download` resumes an interrupted transfer instead of starting over. A
dropped connection or a 5xx is retried up to five requests in all (1, 2, 4 and 8
seconds apart), each asking only for the missing bytes with
`Range: bytes=<n>-`; the server answers 206 with a `Content-Range` (the launch
contract requires it of both the build and the store-update endpoints). A 206 is
appended only when it provably continues the same file: `If-Range` carries the
first response's ETag, and the range must start where the file ends and report the
same total. Anything else (a 200, a range that does not line up, a 416) starts the
file over, because stitching two builds together yields an APK that fails at
install time for no visible reason. Any final failure deletes the partial file,
and every download starts from an empty file, since a re-enqueued job is signed
again and older bytes may belong to a different build. The signed file URL lives
10 minutes, and a resume after that gets a 404; when the link had already served
bytes, `DownloadRepository` reads the job's status again for a freshly signed
link and the transfer resumes on it.

Space is reserved before any bytes are written, through
`StorageManager.allocateBytes`, which clears other apps' caches to make room. A
download that still does not fit, or a disk that fills up mid-write (ENOSPC), fails
with "There isn't enough free space for this download. Free up <size> and try
again." instead of the generic "The download could not be completed.", and is not
retried.

Notifications name the app from the catalogue, "Moovit 5.201.0.1808", instead of
its package. The app detail screen hands `DownloadService` the name with the
download, which avoids a fourth copy of the app names in this client. The ongoing
notification is updated only when a download's percent or phase changes, and at
most once a second, because Android sheds a package's notification updates past
about five a second. When a download
ends, the service leaves "<app> is ready to install" or "<app> could not be
downloaded" behind on a "Finished downloads" channel, if notifications are
enabled; the ongoing notification goes away with the foreground service.

## How installing works

`BundleReader` decides what a downloaded file is by **reading it**, not by its
extension — the same rule the server applies in `utils/apps_utils.py::is_bundle`. A zip
containing top-level `.apk` entries is a bundle and its splits stream straight from the zip
into one `PackageInstaller` session; nothing is extracted to disk. Anything else must
contain an `AndroidManifest.xml` and is installed as a single APK.

Both count only **top-level** entries. Stitch's own `stitch.apk_utils.is_bundle` matches
a `.apk` entry anywhere in the archive, so an ordinary single APK that embeds an
unrelated asset ending in `.apk` is read as a bundle. Stitch itself embeds its helpers
that way (`assets/stitch/com.paywall.apk`, `assets/stitch/com.smali_generator.apk`).
On the client, that installs the wrong bytes. On the server, which used Stitch's
check until 2026-09-30, `patch_paywall` embedded the token into the largest helper
and re-zipped the build itself with no signature. `SignatureVerifier` then rejected
every single-APK WhatsApp download as an untrusted signature. Restricting to the top
level is safe because a real bundle keeps every split at the root of the zip — all 19
of them in a Moovit build, none nested.

Separately, `utils/apps_utils.py::_embed_token` corrupts an already-signed input APK
by appending to the zip, which breaks `zipalign`. It does not affect the normal
pipeline (the sources in `/opt/patched` are unsigned, so the server embeds then
signs), and it is not this client's code.

Re-signing is the reason the download runs as a foreground service, not because it
is slow: on this hardware a ~42 MB Moovit build signs in roughly 6-10 seconds, not
the "minutes" the spec and plan describe. The service exists because artifacts run
38-155 MB and the work can outlive the screen (the user switches apps, the screen
locks) even at that speed, and a bound download must not die with the activity.

In a **debug build** on a rooted device, Settings can switch to `RootInstaller`,
which pipes each APK to `pm install-write ... -` over stdin and installs with no
dialogs. That path is unit-tested but **has not been exercised on a device**, so
release builds do not offer the switch at all: `SettingsStore` neither reads nor
writes the preference in release, and `Container.installer()` always returns
`SessionInstaller` there (`useRootInstaller()`, covered by a JVM test). Root is
probed only when the switch is turned on, never when Settings opens: probing runs
`su`, and the superuser manager answers an app's first `su` with a grant prompt,
which used to pop up just from visiting the tab. A refusal leaves the switch off;
`RootInstaller` caches the answer for the process, so a grant given afterwards
needs an app restart. Root for this app is a separate grant from the adb shell's.
On the verification device, Magisk holds an explicit DENY policy for this app's
uid while the adb shell's uid is ALLOW, so the app cannot obtain root even though
shell-level `su` probes succeed, and the root path is not claimed as verified
anywhere in this document.

A session install waits at most three minutes for PackageInstaller's verdict. The
verdict normally arrives seconds after the user answers the system dialog, but that
dialog is started by `InstallReceiver` from the background: if the user left the
app while the build was staging, Android blocks the start, no dialog appears, and
no verdict ever comes. Without the limit the button said "Installing..." until the
process died. On timeout the session is abandoned and the install fails with
"Installation didn't finish. Tap Install to try again." Giving up and delivering a
verdict are mutually exclusive, so a verdict that arrives late is dropped rather
than overwriting that message, and a late confirmation request for an abandoned
session does not open a dialog. That is every install's default
(`InstallConfirmation.DIALOG`); an on-device update, which nobody may be watching, asks
differently (see "On-device patching", "Where files go").

`SignatureVerifier` needs the base APK of a bundle as a file, so it copies the base
out of the zip into `cacheDir` for the check and deletes it before the install
session is created. That copy does not raise peak disk use, which is the download
plus the session's staged copy (about 2x the build) either way. The zero-copy
alternatives were rejected: `getPackageArchiveInfo` refuses a split APK ("Expected
base APK, but found split"), a session exposes no path to read signatures from,
and the rest would mean adding apksig (which still inflates a compressed entry) or
parsing APK Signing Blocks by hand.

Downloaded artifacts live in `filesDir/downloads` and run 38-155 MB each, so the app
reclaims them rather than letting them pile up invisibly: a successful install
deletes the build it installed, and `Application.onCreate` prunes the directory on a
background thread. A *failed* install keeps its file, so retrying does not
re-download. Pruning everything at startup is safe because `DownloadRegistry` is
memory-only and the foreground service dies with the process, so no transfer can be
in flight at that point.

Patched builds are signed with this project's key, so installing over a Play Store
copy fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. The Library screen recognises
that and offers "Uninstall and retry".

Every finished install, from either installer and from either screen that can start
one, also raises a toast. A success reads "Installed <app> <version>". A failure
reads "Install failed: <short reason>", with the installer's own code (for example
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) on a second line. The toast keeps to two lines
because Android 12+ truncates a text toast after the second, so the full explanation
stays in the error banner.

Because the build is re-signed with a key Play Protect has never seen, Play Protect
shows an "App scan recommended" prompt on every install; tapping "Install anyway"
(or "Install without scanning") continues normally. This is ordinary sideloading
friction, the same thing any unlisted APK triggers, not something specific to or
worsened by this project.

## Moving WhatsApp chats

Switching from the official WhatsApp to ours means uninstalling it, and that deletes the chats.
The WhatsApp page therefore offers a guide that carries them across. Its card shows only when it
matters: the official WhatsApp is installed, or a move is half done. The same guide also goes back
the other way (see "Going back to the regular WhatsApp" below).

**Why it works this way.** A differently signed APK can never inherit an app's data on a
non-rooted phone, so the uninstall cannot be avoided. What can be saved is the local backup
WhatsApp keeps in `/sdcard/Android/media/com.whatsapp/WhatsApp`. The uninstall deletes that folder
too, so the guide moves it to `/sdcard/WAMigrate` first ("parks" it), with one rename. It moves it
back before the new build is installed. Google Drive backups cannot restore into a re-signed build
(see the web install guide), so this local backup is the only route.

The guide's seven steps:
1. Allow file access.
2. Back up in WhatsApp.
3. Download our build.
4. Remove the old WhatsApp. This parks the backup first, then opens the uninstall dialog.
5. Install ours. The backup goes back first.
6. Restore inside WhatsApp's own setup.
7. Finish: a last check that the new WhatsApp makes a backup of its own, then remove the
   spare copy.

Which step is current is read from the phone on every resume, never stored, so leaving half-way
and coming back lands on the right step.

**The engine is the `:migrate` module.** It is Java, package `com.patcher.migrate`, lifted from
a separate project, migrate_app (its git history lives there; its JVM tests moved
here on 2026-10-10 and run with `./gradlew :migrate:testDebugUnitTest`). `Migration` is its
whole facade. `MoveRepository` is the only store class that touches
it. `move/WhatsAppMove.kt` turns the engine's `Status` plus what only the store knows (is the
installed WhatsApp ours, is the build downloaded, what has the user acknowledged) into what every
screen shows. The engine's own sentences name folders and manifests, so they are shown only
under "Details" and in the support email.

Rules that are easy to get wrong. Each one was learned on a device:

- **All files access (`MANAGE_EXTERNAL_STORAGE`), not the media permissions.** A `.crypt14` is
  not a media type. All files access reaches `Android/media/com.whatsapp` for read, write and
  rename, while `Android/data` stays closed. That asymmetry is the whole reason this works.
  Lint's `ScopedStorage` is suppressed: it enforces a Play policy, and the store is not on Play.
- **Order is the safety property.** `park()` writes its manifest before the rename, `putBack()`
  renames before marking it restored, and finish() deletes the copies before the manifest
  and the record. A crash at any point leaves evidence the next run can read.
- **Put the backup back *before* installing, never after.** WhatsApp reads a local backup only
  while it registers, so a backup that arrives later looks exactly like nothing happening. This
  is enforced in code, not in the instructions: every store install of `com.whatsapp` goes
  through `LibraryViewModel.install`, which calls `MoveRepository.beforeInstall`. That puts a
  parked backup back first, checks the build is not older than the backup (WhatsApp refuses a
  backup from a newer version), and refuses outright while the engine reports a problem.
- **"Keep app data".** WhatsApp declares `hasFragileUserData`, so the uninstall dialog offers to
  keep its data. If that is ticked, our install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
  The guide says to leave it unticked. The engine also looks WhatsApp up with
  `MATCH_UNINSTALLED_PACKAGES`, so a package removed with its data kept still counts as the
  original: put-back is refused while the backup is still parked, and the guide explains the fix
  (reinstall from Play, remove again unticked). **Not yet confirmed on a device.**
- **An unreadable signature fails safe.** If WhatsApp's certificate cannot be read, the engine
  treats it as possibly the original and refuses put-back, and the guide shows "We paused to
  keep your chats safe" with Contact us.
- **The GUI uninstall deletes `Android/media/com.whatsapp` outright, where `adb uninstall`
  leaves it behind empty.** A procedure verified only over adb has not been verified.

**One stored hint.** The engine derives everything from disk, which is right while it can read
the disk. Without All files access it reads nothing, so a half-done move would look like no move,
and a plain install would let WhatsApp register without its backup.
`SettingsStore.whatsAppMoveActive` covers that gap:
- it is set (with `commit()`) when the backup is first saved and again just before parking, and
  cleared when the move is cancelled or finished;
- while access is granted, the engine's answer overrides it;
- with no access and the hint set, the guide asks for access again, and the install gate refuses.

**The trap it closes.** Before this, tapping Install on WhatsApp's page with the official app
installed led to "Uninstall and retry", which deleted the chats with no backup. Now an Install
tap there, "Uninstall and retry" and the Library's Uninstall all ask "Keep your chats?" first,
while the official WhatsApp is installed and no move is under way.

**Run on a device** on 2026-10-05/06: the owner's Pixel 9a (Android 16), with real chats and a
copy of every file on a computer first. It went official WhatsApp 2.26.39.70 → ours, through all
seven steps, and WhatsApp's own restore brought the chats back. Seen there:
- the backup was parked before the uninstall and put back before the install;
- every database and media file came back byte-identical;
- the GUI uninstall deleted `Android/media/com.whatsapp` outright;
- no Play Protect prompt appeared, either for the store holding All files access or for the
  install.

Every `.nomedia` file changes on a move, because Android writes each one's own folder path into
it. That, not WhatsApp, is the small tree-size delta `Verify.compareTree` reports.

The run found three bits of wording that did not match Android 16 ("Keep app data", the
Uninstall button, "Install this app?"). It also found that step 2 accepted a backup made before
the guide was opened. All four are fixed: a backup now has to be newer than the user's Start
(`SettingsStore.whatsAppMoveStartedAt`).

**Still unverified:** the "Keep app data" detection (the owner chose not to tick it on the real
phone) and the access-revoked path.

### Never losing the backup mid-move

On 2026-10-10 the owner removed WhatsApp from Settings > App info at step 3, before step 4 could
park the backup. Android deleted `Android/media/com.whatsapp` with the backup in it. Nothing had
been parked, and the guide then said "Nothing to move". The store cannot block that uninstall, or
even notice it, so the move now keeps its own copy.

- **A saved copy and a record.** `MoveRepository.track` runs from `MainActivity.onStart` and from
  every guide refresh. The first time the store opens after the backup step 2 accepts, it has the
  engine:
  - copy the newest backup database and `Backups/` into `/sdcard/WAMigrate/Saved/`, with their
    times, verified by hash;
  - write `move.txt` (`MoveRecord`).

  Media is not copied: only step 4's park keeps photos and videos.
- **Removed by hand still goes back.**
  - With a record and no WhatsApp, the engine reads PUT_BACK from the copy. The gate, the watcher
    and the guide's button then put it back as they do a parked one, and the guide warns that
    the media are gone.
  - The same applies when the WhatsApp the backup went into is removed again: the newer of
    `Databases-kept/` and `Saved/` goes back, copied, never moved.
- **Nothing is deleted before DONE.**
  - DONE needs the user's "My chats are back" (kept in the record) and the new WhatsApp's own
    backup (`Finishing.findEvidence`). That backup must be written after the put-back, be at least
    3/4 the size of the one put back, and not be a copy of it.
  - `Migration.finish()` is the only delete of a copy the move still depends on. It removes all of
    `WAMigrate`, and only in DONE. Cancel (at the start, while WhatsApp still holds the backup) and
    the copy's own temp-folder cleanups are the only other deletes.
  - Cancel, at the start, deletes the copy only while WhatsApp still holds the same backup.
  - Anything else in the way is set aside into `previous-<date>/`.
- **One write at a time.** `OneAtATime` serialises every write the store makes to the move, so
  step 4's park waits for a copy that is still running.
- **Not covered:** a user who makes the backup and removes WhatsApp without opening the store in
  between. Steps 2–4 tell them not to.

### Going back to the regular WhatsApp

The same guide runs the other way, from ours to the official WhatsApp from the Play Store, in six
steps: allow file access, back up, remove ours, install from the Play Store, restore, remove the
spare copy. It is offered by a text link on the WhatsApp page, a row in Settings > Help, and the Library's
Uninstall on our WhatsApp. It is never offered by a card: every subscriber sees that page, and a card
would read as a nudge to leave.

**The direction is read from the phone, like the step.** The manifest records the certificate of
the build the backup was parked from. Before anything is parked, the installed WhatsApp is the one
there is to leave. Without file access the manifest can't be read, so `park()` stores
`SettingsStore.whatsAppMoveToOfficial` beside the old hint, and the two are cleared together. The
engine did not change: "the original" in it always meant "the build the backup was parked from".

**The backup stays parked until the Play Store has installed WhatsApp.** The install gate never sees
a Play Store install. So `WhatsAppInstallWatcher`, a `PACKAGE_ADDED` receiver registered in
`StoreApp`, puts the backup back once WhatsApp is installed. WhatsApp only reads it during
registration, after the SMS code, so there is time. The watcher acts only when
`shouldPutBackOnInstall` holds:
- the engine's `canPutBack`, which already refuses the build the backup came from;
- an installed version no older than the backup's.

The rule doesn't know which direction it is in, so it also covers ours installed from outside the
store during a move in.

Why not put the backup back first and then open the Play Store, which is simpler? The Play Store's
copy can be older than ours: our builds are not always from its stable release, and rollouts are
staged. WhatsApp refuses a newer backup only after the number is verified. With the backup already
in WhatsApp's folder there would then be no safe way out, because removing WhatsApp deletes it.
Kept parked, the backup just waits behind "Don't open WhatsApp yet" until the Play Store updates
the app. The user then comes back to the guide, and the backup goes back before they open WhatsApp.

**The watcher is best effort, so the words don't lean on it.** The receiver lives only as long as the
store's process. Since Android 14, a *cached* process, such as the store sitting in the background
while the user is in the Play Store, gets the broadcast only once it is uncached, usually when the
user returns. So step 4 tells the user to come back here before opening WhatsApp, and offers **Put
my backup back** when the backup is still parked. The known gap: a user who taps **Open** in the Play
Store and gets past the SMS code before coming back registers WhatsApp without the backup. The backup
is still parked, and the guide shows Attention. If the device run shows this happens often, the
next step is a short foreground service while step 4 waits on the Play Store.

**Two installs the gate refuses mid-move.** The store only installs ours, which can never install
over a WhatsApp signed by someone else. So while a move is past its removal step and such a WhatsApp
is installed (the official one, mid-return), `beforeInstall` refuses before anything moves
(`storeInstallWouldStrandBackup`). Otherwise it would put the backup back in front of an install
bound to fail, and that failure offers "Uninstall and retry", which deletes WhatsApp's folder with
the backup in it. For the same reason, while a move is in progress the Library's **Uninstall** and
**Uninstall and retry** on WhatsApp open the guide instead of uninstalling. Found by review before
the first device run.

**Run on a device** on 2026-10-09: the owner's Pixel 9a (Android 16), with real chats. The owner chose
this phone over a spare one. Every file was copied to a computer first, and the copy was deleted
after the run. It went ours 2.26.39.70 → the Play Store's 2.26.39.74, through all six steps. The
store was the branch build (versionCode 405621). Seen there:
- the backup was parked before the uninstall, and the GUI uninstall left nothing behind;
- the user installed from the Play Store and only then came back. The store had been in the
  background throughout, yet the watcher put the backup back 3 seconds after the install ("Put back
  75 files, databases verified"), before WhatsApp was ever opened;
- WhatsApp's "Backup found" offered the local backup made in step 2, dated that evening, and the
  restore brought the chats back. Whether the account also held an older Google Drive backup was
  not checked.

**Still unverified:** tapping **Open** in the Play Store straight away, a Play Store copy older than
ours, "Keep app data" on our build, the **Put my backup back** fallback, and the gate's mid-move
refusal.

## How the store updates itself

On launch the app reads `GET /api/store`, which reports the `version_code` the
server read out of the published `store.apk`'s own manifest. If it is higher than
the installed build's (read from `PackageManager`, not `BuildConfig`), the Apps tab
shows an "Update" banner, and Settings shows the same state under "App version"
with a manual "Check for updates". Nothing is offered when the server sends no
version (an unreadable APK, or an older server): an APK that cannot be ordered
might be a downgrade.

**Version codes are stamped, not bumped.** Release builds set `versionCode` to the
minutes since 2026-01-01 UTC at build time (`app/build.gradle.kts`), so any release
built later is newer, with nothing to remember. A forgotten manual bump would fail
silently: the server would report the same code and nobody would be offered the
update. Debug stays at `1`. `versionName` is still edited by hand, which is why the
UI always shows the build number next to it.

The update goes through `Container.installer()`, like a patched app, so it passes
`SignatureVerifier` and uses root when silent install is on (debug builds only).
`PackageSelfInstaller`
also refuses an APK whose package is not `site.androidmod.store`. Every patched app
carries the same key, so a WhatsApp build copied over `store.apk` by mistake would
otherwise pass the signature check.

On Android 12+ `SessionInstaller` asks for `USER_ACTION_NOT_REQUIRED` when the package
being installed is the store itself or an app the store installed (its installer of
record), and the manifest declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, so there is
no "Do you want to update this app?" dialog (`silentInstallAllowed`). That covers every
app the store installed, off-device ones as much as on-device updates of WhatsApp, and
`SignatureVerifier` and WhatsApp's keep-chats gate still run first. Anything else keeps
its dialog: the guide's first install, and an app a user installed by hand or with adb.
Play Protect's "App scan recommended" prompt can still appear, because it is the
device's verifier, not the installer's dialog.

Replacing its own APK kills the app, and Android does not relaunch it. The banner
says so while installing. `UpdatedReceiver` listens for `MY_PACKAGE_REPLACED` in
the new build and posts a "tap to open" notification, but only when
`SettingsStore.pendingSelfUpdate` shows that the store started the update itself,
so `gradlew installDebug` and manual installs stay quiet. The downloaded APK is left
in `filesDir/downloads`, which the updated app's first launch prunes.

The first build with the updater has to be installed by hand once. Builds from before
it (`versionCode 1`) never check.

## On-device patching

For an app the server marks `on_device` in `GET /api/apps` (WhatsApp, once the server
flag is set), the store builds the patched APK on the phone from the publisher's own
official APK and never downloads a build from the server. For every other app nothing
below applies and the download, `/api/list`, guide and Library work as described above.
The server side (records, bundles, the key and paywall-token endpoints) is described in
the repo README, "On-device patching endpoints". It needs Android 13 (API 33, for
ArtHooks); the store's minSdk stays 30, so an older phone gets "Patching on this phone
needs Android 13 or newer." instead of a build. It is not offered anything it can't
finish either: on Android 11 and 12 `updateOffer` is always `None` (no Update or Move
button, no update notification), and the move guide's way into ours shows that sentence
up front (`MovePhase.AndroidTooOld`) while going back stays available.

**The pieces** (package `patch/`, in the order a build uses them):

1. `PatchApi` is the client for `/api/patch/{pkg}/latest`, `/artifacts`, `/bundle/{version}`,
   `/api/app-token` and `/api/signing-key`. A 426 from `latest` becomes
   `StoreUpdateRequired`; 401, 402 and 404 stay `ApiException`. `ApiClient` sends the bearer
   token and `X-Store-Version-Code` only to the base URL's host, on every hop: a network
   interceptor strips both from a redirect anywhere else (OkHttp itself drops only
   `Authorization`). A HEAD that whatsapp.com refuses just leaves the size unknown.
2. `BundleCache` keeps `filesDir/patch/bundles/<pkg>/<patcherVersion>.zip` and its SHA-256
   beside it, asks the server with `If-None-Match`, and re-verifies the zip against the
   pinned key on every open. A bundle that fails verification, or is for another package
   or version, is deleted.
3. `PatchJob` is one build, with no Android types in it so the JVM tests drive it: signed in,
   API 33+, `latest`, the verified bundle (its `minSdk` and `minStoreVersionCode` can refuse
   the build), the official APK from `apk_url`, its signer checked against the bundle's
   official certificates, the record looked up by the downloaded file's own `apk_sha256`
   (then `dex_sha256`, since the publisher may have moved on since `latest`), the features-off
   question, the paywall token, the signing key, `PatchEngine` (`:patch-engine`) signing
   through fastsigner, and a last check that the output has the right package, version and
   the user's key. The result is moved into place atomically. Room for 3.2 times the
   official APK is reserved before downloading. Failures are a `PatchFailure` with the
   user-facing words in `PatchFailure.message`; a coroutine cancellation is rethrown, never
   reported as a failure. The job never touches an installed app; it ends by handing over a file.
4. `PatchService` runs jobs in a `dataSync` foreground service, one per package, because the
   official WhatsApp download is about 155 MB and can outlive the screen; patching and
   signing take about 2 seconds on the emulator and 3 on a Pixel 9a. A build uses up to
   about 221 MB of Java heap, more than many phones give an app by default, so the
   manifest sets `largeHeap`. It posts progress and result
   notifications (`PatchNotifications`), and on Android 15's foreground time limit it
   cancels the jobs. `PatchRegistry` is the process-wide state the screens read
   (`PatchState`), like `DownloadRegistry`; it also carries the user's answer to the
   features-off question. Neither survives the process: a killed store starts again.
5. `UpdateOffer` / `UpdateWorker` / `UpdateAppActivity` are the update side, below.
6. `PatchInfo` reads `assets/patcher/patch-info.json`, which the engine writes into the
   build, from the installed APK: the revision and the features that were off.

**Where files go.** `filesDir/patch/work/<pkg>` is the job's scratch space. The job empties
it when it starts and ends, and `StoreApp` wipes `patch/work` and `patch/ready` at launch.
Neither is under `filesDir/downloads`, which `DownloadCleanup` prunes. A guide build (`PatchMode.GUIDE`) is
handed over at `DownloadPaths.targetFile(downloads, DownloadKey(pkg, versionName), "apk")`
and marked `DownloadState.Done`, so the guide's install step takes it exactly as it takes a
server build. An update (`PatchMode.UPDATE`) goes to `filesDir/patch/ready` and is installed
by the service straight away through `Container.installer()` (for WhatsApp after
`MoveRepository.beforeInstall`, the same gate that puts a parked backup back), then deleted
once the outcome is known. Usually that install is silent (see "How the store updates
itself"). When Android still wants its dialog (a WhatsApp not installed by the store), the
user may have left the app during the download, where the dialog can't be started: the
service's install (`UpdateInstall`) passes an `InstallConfirmation` whose notification,
"Tap to install WhatsApp <version>", carries the system's confirm intent, and waits up to
30 minutes for that session instead of three. The notification goes when the session
ends; no confirmation in time fails with "The update wasn't confirmed. Tap Update to try
again."

**In the screens.** `GuideDownloadControl` replaces the move guide's download with
`PatchControl` when the server flag is on, and "Not possible yet" compares against the
server's `latest`. The guide's steps are unchanged. `PatchControl` (`ui/patch`) is shared with
the Library, whose card shows "Update" (or "Move to the new update system") from the offer;
Update is disabled while a chat move is under way.
When visible features are off in this version, the job stops and the app asks "Some features
are off in this version" with Continue and Cancel; the notification says to open the app.
Back, Cancel, or no answer for 30 minutes counts as Cancel; a tap outside the dialog does
not. A record with `supported: false` (the crawler's verdict) is not asked about: the job
ends with "Not supported yet".

**Signing key.** One per account, from `GET /api/signing-key`, kept by `SigningKeyStore` in
`filesDir/signing` (a PKCS#12 file and a small JSON beside it) and cleared on every way a
session ends (`AuthRepository.endSession`). `backup_rules.xml` and
`data_extraction_rules.xml` exclude every domain, so it is never backed up or transferred; keep
it that way. A fetch that began before a sign-out is discarded rather than stored. Nobody
logs or prints the key, its password or the paywall token; their `toString` hides them.
The password reaches fastsigner on stdin only (the engine's `FastSigner`), and the Settings
screen shows only the certificate's SHA-1, and only once WhatsApp is on-device; while the
installed WhatsApp is on another key it adds that the key applies after the one-time move.
`SignatureVerifier` accepts an APK signed by the store's own key, or by the cached user key
and nothing else.

**fastsigner.** The signer is an NDK (bionic) executable shipped as `libfastsigner.so` under
`app/src/main/jniLibs/{arm64-v8a,x86_64}/`. `packaging.jniLibs.useLegacyPackaging = true`
in `app/build.gradle.kts` makes Android extract it to `nativeLibraryDir`, where it can be
executed; inside the APK it could not be. It must be a bionic build, not glibc (static or
not): glibc's startup calls `set_robust_list`, which Android's app seccomp filter traps, so
the process dies with SIGSYS (exit 159) from the store even though the same binary runs fine
from `adb shell`. To refresh the binaries after changing `tools/fastsigner`, run
`scripts/update-fastsigner.sh [fastsigner-checkout]`: it builds both Android targets with the
NDK (`$ANDROID_NDK_HOME`, else the newest under `$ANDROID_HOME/ndk`) into a temporary target
directory and refuses anything that is not the right architecture, dynamically linked against
`/system/bin/linker64`, with only bionic system libraries as dependencies.
`FastsignerBinariesTest` checks the committed ELF headers.

**The bundle key pin.** `patch/BundleKey.kt` holds the public half of the key that signs
patch bundles; the private half lives outside every repo. A store trusts a bundle only if
that one key verifies it. To rotate the key, replace the PEM in `BundleKey.kt` and ship a new
store release; the server verifies uploads against its own `BUNDLE_PUBLIC_KEY_PATH`, which
moves to the new public key when the bundles do. There
is no second slot: a store with the old pin rejects bundles signed with the new key, and a new
store rejects the old ones. So release the store first, let phones update, then switch the server's
key file and re-upload every bundle signed with the new key.

**Updates.** `UpdateWorker` runs every six hours (WorkManager, network required, scheduled
from `StoreApp.onCreate`). For each package in `SettingsStore.onDevicePackages` (the
catalogue's `on_device` apps, remembered for offline use) that is installed, it asks the
server for `latest` and `updateOffer` decides:

- `NewVersion`: the server's `latest` has a higher version code than the installed app, signed
  with the user's key. "Tap to update. Your chats stay."
- `PatchUpdate`: same version, a higher record revision than the installed build's
  `patch-info.json`; the notification lists the features that come back.
- `NeedsMove`: still on the old shared-key build. The tap opens the move guide once to
  move to the user's own key; an update cannot install over it.
- `None`: not on-device, not ours (the regular app), current, or Android 11/12.

An offer is announced once (`SettingsStore.notifiedOffers`, keyed by package, version code
and kind, plus the revision for a `PatchUpdate`), and is not remembered when notifications
are off. A 402 ends the run.
Tapping the notification opens `MainActivity` with an extra that `NavigationRequests`
holds until the app can act (after sign-in, if need be); `LibraryViewModel.handleUpdate`
then asks again, so a stale notification says "is up to date" instead of building (or
"Couldn't check for an update. Try again later." when the user's key can't be fetched),
and starts the update. A relaunch from Recents is ignored. A 426 makes the store update
itself first (`StoreSelfUpdate`), as does a bundle needing a newer store; the move guide
doesn't start that by itself while a move is under way.

A patched app's own update button opens `UpdateAppActivity`: action
`site.androidmod.store.action.UPDATE_APP`, string extra `package`. It is exported and has
`CATEGORY_DEFAULT`, so it validates the extra (`updateAppRoute`): an installed `on_device`
package is forwarded to `MainActivity` for its update, any other well-formed package name
opens the Library with nothing started, and anything else finishes silently.

**Which WhatsApp is "ours".** With the server flag off, ours is a build signed with the
store's certificate, as before. With it on, ours is a build signed with the user's own key,
and the store's certificate marks a previous build (`buildKind`, `BuildKind.PREVIOUS`).
A previous build gets the one-time move above, or the user can choose to go back to the
regular WhatsApp. While the key is unknown (not cached and the fetch failed) the guide
offers no move, so a reinstalled store cannot send someone on a needless one; the chat-loss
guards on Install and Uninstall stay on.

**What changes when the server sets `on_device`.** The guide's DOWNLOAD builds on the phone
instead of fetching a server build, and the Library asks `/api/patch/{pkg}/latest` instead of
`/api/list/{pkg}`.
Updates are notified and installed over the app by the store. The key is fetched, and
"ours" changes meaning as above.

**Staging build.** `./gradlew :app:assembleStaging -Pandroidmod.stagingBaseUrl=http://HOST:8000`
builds the release as shipped (minified, shared key, stamped version code, so it installs over
a release store) but pointed at a dev server (default `http://127.0.0.1:8000`); `src/staging`
allows cleartext like debug does. It needs the same signing environment as a release build.
Use it, not a debug build, to prove on a device that R8 left the engine's reflection intact:
`app/proguard-rules.pro` only silences warnings for commons-compress's optional codecs, and
the rest is checked by running the minified build.

### Patching any app

Besides WhatsApp, `:patch-engine` can remove ads from any installed app. It uses the
**generic bundle** (`PatchBundle.isGeneric`, package `generic`): the same signed zip with
the hook module, the paywall module and the manifest additions, but an ad catalog
(`catalog.json`) in place of per-app features. `GenericPatchEngine` takes the app's APKs
(base first, then splits), scans the dex files for ad SDKs, and rebuilds the base with the
modules, providers and `assets/` added. Every split is re-signed unchanged with the user's
key. Nothing is decompiled: `AdScan` reads the dex directly and gives the artifacts the
desktop finder gives, which `GoldenScanTest` checks against `golden.py`'s output for the
corpus apps.

**Checks** (`GenericChecks`, from manifests and zip listings only): an app is refused
(`PatchException.Unpatchable`, with `CantPatch` reasons) when it uses PairIP, a shared user
id, a target SDK the device would refuse to install (below 23 on Android 14, below 24 from
Android 15; older devices enforce none), or when it is already patched and this isn't a
Refresh. The ABI rule picks which hook goes in: the app's own 64-bit or 32-bit libs decide, `armeabi` gets
the v7a hook under `lib/armeabi/`, and an ARM-only app on an x86 device is
`NEEDS_TRANSLATION`. The store (plan 4) adds the checks that need Android: a system app,
the store itself, a signer that is neither the shared key nor the user's key, an app not
listed in `/api/apps`, and OBB files. It also offers the picker, and reserves `bytesNeeded`
plus the saved original before patching.

**Refresh** (`GenericSource.PATCHED`) re-patches from our own copy: the original manifest
is kept as `assets/patcher/original-manifest.axml`, and `assets/patcher/patch-info.json`
(kind `generic`, patcher version, bundle id, the ABI used, the SDKs found, the entries
added) says what was done and marks the copy as ours. A Refresh ends with the same
entries as patching the original.

**What gets patched.** The confirm screen (`ConfirmScreen`, `ui/generic`) asks "Patch <App>?"
before the build and carries two switches, "Remove ads" (on) and "Bypass in-app purchases"
(off); each visit starts from those defaults and keeps no state. With both off the Patch button
stays disabled and the screen's first bullet reads "Nothing will be patched."; otherwise that
bullet follows the mix — ads only: the phone removes its ads; purchases only: the app's
purchases succeed without paying; both: the phone removes its ads and makes its purchases
succeed without paying — and every version ends "Nothing is uploaded." A bundle whose catalog
has no `iap` section still patches: the run writes `IAP_ENABLED` with an empty census and no
verifier keys, so purchases do nothing while the ads still come off. The confirmed mix is
recorded on the patch record: the run screen shows it, a Refresh rebuilds with it, and a later
start that carries no choice never resets it. `patch-cli` takes `--capabilities` on both
`generic` and `scan`: comma-separated `ads` and `iap`, case-insensitive, `ads` alone when it is
absent, an unknown name a usage error (exit 2).

**On a desktop** `patch-cli` has two commands (WhatsApp's flags with no command are
unchanged). Passwords come from environment variables named by flags, never from flags:

```
patch-cli scan    --base FILE [--split FILE]... --bundle FILE --bundle-key FILE
patch-cli generic --base FILE [--split FILE]... --bundle FILE --bundle-key FILE
                  --fastsigner FILE --keystore FILE --keystore-pass-env NAME
                  [--keystore-alias NAME] [--key-pass-env NAME]
                  --abis A,B,... --sdk N [--token TOKEN] [--refresh]
                  [--capabilities ads,iap] --out-dir DIR
```

`scan` prints the artifacts as JSON (keys sorted, two-space indent), to compare with a
golden. `generic` signs with the keystore (its only alias, or `--keystore-alias`), takes the
cert's SHA-256 from it, and writes the signed base and splits to `--out-dir`. `--abis` and
`--sdk` describe the target device (`adb shell getprop ro.product.cpu.abilist` and
`ro.build.version.sdk`). Exit codes: 0, 2 for usage errors, 1 for a failure
(`patch-cli: cannot patch: PAIRIP` for a refused app).

## Help, bug reports and links

Settings has three sections of links. Help: "How to install", "Report a bug" and
"Contact support". Account: "Change password" (below) and "Manage devices", which
opens `androidmod.site/subscription`, where the devices a subscription's apps run on
can be reset. Legal: the terms, privacy and refund policies. Website pages open in a
Custom Tab, so a page that needs a web sign-in gets the browser's session, and
always on the public site (`ui/Links.kt`): a debug build's base URL points at the
API server, and uvicorn does not serve the website.

"Report a bug" is an in-app form that posts to `/api/bug-reports` with
`source: "android"` and a `context` of `app_version` ("1.0 (389123)", from
PackageManager), `os` ("Android 16 (API 36)") and `device` ("Google Pixel 9a").
The form shows that context to the user before sending. The email is prefilled
from the signed-in account and optional; left empty, it is omitted, and the server
replies to the account's address. Send is enabled only for what the server accepts:
10-5000 characters, counted in code points as Python counts them (an emoji is two
UTF-16 units but one character to the server), and a plausible email if one is
given. A 429 explains the hourly limit, and any failure keeps the text and offers
"Email support instead".

"Contact support" starts an email to sudorm.ext@gmail.com with the same context
prefilled. With no mail app installed, the address is copied to the clipboard and
a toast says so.

"Change password" is an in-app form that posts to `/api/users/me/password` with the
app's bearer token. It is native, unlike "Manage devices": the browser behind a
Custom Tab usually has no web sign-in, so the website's form would first ask for the
password being changed. Save is enabled only for what the server accepts: a new
password of 8-128 code points that differs from the current one, typed twice. A
wrong current password is a 400 shown on that field (the server never answers it
with 401, which the app treats as an ended session), a 429 says to wait an hour, and
any failure keeps what was typed. The token is kept: sessions are not bound to the
password, so this phone and every other device stay signed in, and the form says so.

## Backups

Nothing leaves the device, by cloud backup or by device-to-device transfer.
`allowBackup="false"` alone no longer stops a device transfer on Android 12+, so
`res/xml/data_extraction_rules.xml` excludes every domain from both, and
`res/xml/backup_rules.xml` does the same for Android 11 through
`fullBackupContent`. The file that matters is the encrypted token store: its
keystore key never leaves the phone, so on a new device the file would arrive
undecryptable. Everything else is per-device state.

## Release builds and R8

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
# app/build/outputs/mapping/release/mapping.txt
```

Release builds are shrunk, optimised and obfuscated by R8 (`isMinifyEnabled` and
`isShrinkResources`), which took the APK from 28.1 MB to 3.3 MB.

**Keep `mapping.txt` for every release you publish.** A crash from a release build
reports obfuscated class and method names, and only the mapping file from that
exact build can translate them; rebuilding later produces a different one. The
versionCode is stamped per build, so store each mapping under that code, for
example `mapping-392615.txt` (the code is in
`app/build/outputs/apk/release/output-metadata.json`). `deploy/deploy.sh` does
this for every build it publishes, into `~/.local/share/androidmod/mappings/`.
To read a stack trace:

```bash
$ANDROID_HOME/cmdline-tools/latest/bin/retrace mapping-392615.txt stacktrace.txt
```

`app/proguard-rules.pro` holds a single rule, silencing Error Prone annotations
that Tink is compiled against but does not ship. Everything reflective is kept by
the libraries' own consumer rules, and was checked in the release dex with
`apkanalyzer dex packages --proguard-mappings mapping.txt`: every `@Serializable`
model's `$$serializer`; the Application, Activity, Service and both Receivers named
in the manifest, under their own names; the no-argument constructor of every
ViewModel created by `viewModel()`; Tink's protobuf message fields, which
protobuf-lite reads by name (otherwise the token store could not parse its own
keyset); and the coroutines Main dispatcher, whose ServiceLoader lookup R8 rewrites
into a direct construction. Repeat that check after adding a dependency that uses
reflection, or a model that is looked up by name.

**No dependency may bring its own copy of a framework class.** ARSCLib, under
`:patch-engine`, ships `android.util.AttributeSet`,
`android.content.res.XmlResourceParser` and `org.xmlpull.v1.*` so it runs on a desktop
JVM. Given to R8, those copies shadowed the framework's, and R8 compiled Compose's
vector loading into `throw null`: every minified build crashed at launch while debug ran
fine. So the APK takes ARSCLib through an artifact transform that drops them ("Android
framework stubs" in `app/build.gradle.kts`); `:patch-engine`'s tests, `:patch-cli` and
the app's unit tests keep the jar as published. Before R8 runs,
`verifyReleaseNoFrameworkClasses` (and the staging twin) fails the build if any jar R8
reads defines a class `android.jar` defines. If it fires after a dependency change,
strip that jar's copies the same way rather than adding `-keep` rules.
