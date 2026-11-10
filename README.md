# RomM TV

A Google TV app for playing the games in a [RomM](https://github.com/rommapp/romm) library, with the emulators built in.
Install it, approve the TV in RomM once, and play. No emulator setup.

Built on [Lemuroid](https://github.com/Swordfish90/Lemuroid) (GPL-3.0), which provides the emulator cores, game screen,
in-game menu and controller handling. Lemuroid's original README is in `LEMUROID_README.md`.

## How it works

- **Sign in:** the TV shows a code and a QR code. Open it on your phone, sign in to RomM and approve the TV
  (RomM's device login, `/api/auth/device`). The TV gets its own token; no password is typed on the TV.
- **Library:** titles, systems and covers come from the RomM API and refresh every time the app starts.
  Only systems the app can play are shown.
- **Playing:** a game downloads from RomM the first time it's played and stays on the TV as a cache.
  The games played longest ago are removed when space runs low.
- **Emulator cores** download from GitHub the first time each system is played.
- **BIOS files** come from the game's platform firmware in RomM, the first time a game needs them.
- **Updates:** every push to `main` builds a new APK and publishes it as the `latest` release.
  The app checks that release at start-up and offers the newer build.

## Building

GitHub Actions builds it (`.github/workflows/android.yml`). Locally:

```
./gradlew :lemuroid-app:assembleFreeDynamicRelease -PbuildNumber=1
```

The APK is signed with the repository's `debug.keystore`, so every build installs over the previous one.

## Where the RomM code lives

- `retrograde-app-shared/.../lib/romm/` — API client, session, platform mapping, game downloads, BIOS fetch
- `retrograde-app-shared/.../lib/storage/romm/RommStorageProvider.kt` — feeds the RomM library into the app's game list
- `lemuroid-app/.../app/tv/romm/` — sign-in screen, Larry Slop splash, in-app updater
