<div align="center">
  <h1>Gita</h1>
  <p><strong>A local music player for Android, built with Jetpack Compose. 🎵</strong></p>
  <p>
    <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT License">
    <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android">
    <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin">
    <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4.svg" alt="Jetpack Compose">
  </p>
</div>

## Description

Gita is a lightweight local music player for Android. It reads your audio library directly through MediaStore, so there is nothing to import, sync, or sign in to.

Under the hood, Gita is built with [Kotlin](https://kotlinlang.org), [Jetpack Compose](https://developer.android.com/jetpack/compose), [Media3 (ExoPlayer)](https://developer.android.com/guide/topics/media/exoplayer) for playback, [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) for settings, and MediaScanner / jaudiotagger for cover-art editing.

## Philosophy

Gita aims to be a music player that feels like an object: a dark, brushed-metal interface, minimal chrome, and everything reachable within one or two taps. It deliberately avoids accounts, streaming dependencies, and unnecessary permissions — your music stays on your device.

## Getting Started

- Prerequisites: Android Studio (latest), Android SDK 26+.
- Clone the repository and open the project in Android Studio.
- Run the `app` module on a device or emulator, or build from the command line:

```bash
./gradlew :app:assembleDebug
```

## Questions

For questions and support, please open a GitHub issue. The issue list of this repo is **exclusively** for bug reports and feature requests.

## Issues

Please make sure to describe the device, Android version, and the song file format involved when reporting a reproducible bug. Issues not conforming to this may be closed.

## Support

Gita is an MIT-licensed open source project. It grows thanks to the time and effort of its contributors.

## Stay in touch

- Website — _none yet_
- Repository — the project lives here on GitHub

## License

Gita is [MIT licensed](LICENSE).
