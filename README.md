# PokeEmu

An Android emulator for Game Boy Advance and Nintendo DS Pokémon games and ROM hacks
(FireRed, Emerald, Platinum, HeartGold, Black/White and hacks built on them).

On top of playing, it can edit your Pokémon, move them between games through a
shared Pokémon PC, run Nuzlocke challenges, and use or search for cheats.

> PokeEmu does not include any games. Use ROMs dumped from cartridges you own.

## Install the app

1. You need an Android phone or tablet running Android 8.0 or newer.
2. Download the APK:
   - from the [Releases page](https://github.com/Bruno-PRDA/PokeEmu/releases), pick the latest `PokeEmu-<version>.apk`;
   - or build it yourself (see [Build from source](#build-from-source)).
3. Open the downloaded file on your phone.
4. If Android blocks it, allow your browser or file manager to
   **Install unknown apps** when it asks (Settings → Apps → *your browser* → Install unknown apps), then tap **Install**.
5. Open **PokeEmu** from your app drawer.

To update, install the newer APK over the old one. Your games and saves are kept.

## Add a game

1. Copy your ROM files (`.gba` or `.nds`) to your phone, for example into **Downloads**.
2. In PokeEmu, tap the **Add game** tile.
3. Pick one or more ROM files. They're copied into the app, so you can delete the originals afterwards.

To add a whole folder at once, open the **⋮** menu at the top right and choose
**Import a folder of games**. Subfolders are searched too.

Already have a save from another emulator? Long-press the game, choose **Saves**,
then **Import .sav**.

## Launch a game

- **Tap a game** to start it.
- **Long-press a game** for its options: **Play**, **Saves**, **Pokémon** (editor),
  **Cheats**, **Challenges**, **Rename**, **Cover** and **Remove game**.

While playing, tap the **pause** button to open the menu. From there you can:
- save and load states;
- change the volume and fast-forward speed;
- switch the DS screen layout;
- open cheats, cheat search, challenges and the control editor;
- quit to the library.

Games start from the beginning (your in-game save is still there to continue from).
To pick up exactly where you quit instead, turn on **Continue where you left off**
in the pause menu's Settings section.

## Build from source

You need Windows, JDK 17 and the Android SDK, with NDK 27.2.12479018 and CMake 3.22.1
installed from Android Studio's SDK Manager.

```powershell
git clone --recurse-submodules https://github.com/Bruno-PRDA/PokeEmu.git
cd PokeEmu
# Build the GBA (mGBA) and DS (melonDS DS) emulator cores once:
powershell -File scripts/build-core.ps1
.\gradlew assembleRelease
```

The APKs end up in `app/build/outputs/apk/release/`. Use the `arm64-v8a` one for phones
and the `x86_64` one for the Android emulator on a PC.
