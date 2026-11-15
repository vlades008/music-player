# Library — Android shell

The phone half of a personal music library that runs on a machine at home.

This is **not** the player. The player is a web page served by that machine; this
app is the container that makes it behave like an app on Android:

- remembers the server address (and lets you change it),
- keeps playback alive when the screen is off (foreground service + wake lock),
- puts the current track on a notification with play/pause and next,
- notices when a newer build of the container itself exists, and installs it.

Because the player lives on the server, **changing the player needs no install**.
Only a change to the container (permissions, background behaviour, update flow)
means a new APK — and that is what the update check is for.

## Install

1. Download `Library.apk` from this repository.
2. Open it on the phone and allow installation from this source when Android asks.
3. Start the server on the machine at home: `node serve.js` in `music-resolver`.
4. Open the app. If the address is wrong, tap **⋯ → Server address**.

## Updates

`latest.json` sits next to the APK and carries the build number. On launch and on
resume the app compares it with its own `versionCode`; when the repository has a
higher one, a bar appears: *Update 1.1 is ready — tap to install*. The APK is
downloaded to Downloads and handed to the Android installer.

To point the app at a different repository: **⋯ → Version → Update source**, and
paste the raw `latest.json` URL.

## Releasing a new build

From a machine with the Android SDK in `C:\Users\vlade\AndroidBuild`:

```
powershell -File publish.ps1 -Version 1.1 -Code 2 -Notes "what changed"
```

It rebuilds, copies `Library.apk` and `latest.json` here, commits and pushes.
Nothing else in the repository needs to change — the phone sees the new
`versionCode` on its next launch.

## What it cannot do

Lock-screen controls as a native media session are not here: a WebView does not
publish the page's Media Session to Android. The notification is the control
surface. Native playback would mean a real player (ExoPlayer + MediaSession) and
dropping the web page — a different build, deliberately not this one.

## Source

`src/` holds the three Java files: `MainActivity` (WebView, settings, update UI),
`PlaybackService` (foreground service, notification), `Updater` (check + download).
Built without Gradle by `build-apk.ps1` using the SDK's own tools.
