# Base Finder (Fabric, Minecraft Java 26.2)

Client-side mod. It only reads chunks your game already received from the server
(your render distance), scores them by how many player-made blocks they contain,
and prints "Possible base" lines in chat. Everything is also saved to
`.minecraft/basefinder-log.txt`.

## Easiest: let GitHub build it (no Java needed)
1. Make a free account at github.com and create a new empty repository.
2. Upload everything from this folder (including the hidden-looking .github folder).
3. Open the Actions tab, wait for the green check, open the run and download
   the artifact called basefinder-mod. Inside is basefinder-1.0.0.jar.
4. Skip to "Install it" below.

## Or build it on your own PC
1. Install JDK 25 (e.g. Eclipse Temurin 25 from adoptium.net). Check with `java -version`.
2. Open a terminal in this folder and run:
   - Windows: `gradlew.bat build`
   - Mac/Linux: `./gradlew build`
   The first run downloads Gradle, Minecraft and Fabric (a few minutes).
3. Your mod is `build/libs/basefinder-1.0.0.jar`.

## Install it
1. Install Fabric Loader for 26.2 with the installer from fabricmc.net/use/installer
   (point it at your .minecraft folder), then pick that Fabric version in TLauncher.
2. Put these two jars into `.minecraft/mods`:
   - `basefinder-1.0.0.jar`
   - Fabric API for 26.2 (0.161.0+26.2 or newer, from modrinth.com/mod/fabric-api)

## Settings
`.minecraft/config/basefinder.properties` is created on first launch.
- `threshold`      score a 3x3 chunk area needs to be reported (default 50)
- `requireStrong`  also need >=1 strong block (shulker box, concrete, hopper, anvil...)
                   unless the score is 4x the threshold (default true)
- `chunksPerTick`  chunks scanned per tick, lower = less lag (default 2)
- `chat`           print findings in chat (default true)
- `enabled`        master switch

Too many false alarms: raise `threshold`. Missing small early-game bases: set
`requireStrong=false` and/or lower `threshold`.

## Limits
- You only see chunks inside your render distance, so you have to travel
  (elytra works well) for the mod to find anything.
- Villages, mineshafts, strongholds and trial chambers contain chests, beds, furnaces
  and torches too, so they can show up as false positives. The summary in each line
  (e.g. "12 chest, 5 red_concrete") helps you tell them apart.
- Many servers ban client-side mods like this. Only use it where that is allowed.
