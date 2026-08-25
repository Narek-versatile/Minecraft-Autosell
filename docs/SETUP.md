# Setup

## Requirements

- Minecraft **26.2**
- Fabric Loader **0.19.3+**
- Java **25**
- Fabric API **0.158.0+26.2**
- Baritone **1.18.0** for 26.2 (optional but strongly recommended)

## Install

Put all three jars directly in the instance's `mods/` folder:

```
autosell-1.0.0.jar
baritone-fabric-1.18.0.jar
fabric-api-0.158.0+26.2.jar
```

| OS | Path |
|---|---|
| macOS (Prism/Ely) | `~/Library/Application Support/ElyPrismLauncher/instances/<name>/minecraft/mods/` |
| Windows (Prism/Ely) | `%APPDATA%\PrismLauncher\instances\<name>\minecraft\mods\` |
| Windows (vanilla) | `%APPDATA%\.minecraft\mods\` |

Verify with `/autosell fuel status` in game.

## First-run configuration

The jar carries a bundled default config (`assets/autosell/default-config.json`
and `default-shoppath.json`). On first launch, if no config exists, those are
written out — so a fresh install already knows the chests, farm spot, facing
and shop path. An existing `config/autosell.json` always wins.

To set up on a **different server**, re-run:
```
/autosell loot set        looking at the loot chest
/autosell dump set        looking at the dump chest
/autosell fuel spot       standing where you drop, facing the throw direction
/autosell fuel record     then click through /shop once
/autosell add             holding the item you sell
/autosell keep add        holding each piece of gear to protect
```

## Building

```
export JAVA_HOME=~/Library/Application\ Support/ElyPrismLauncher/java/java-runtime-epsilon
./gradlew build
```

Output: `build/libs/autosell-1.0.0.jar`.

**Do not add a `mappings` dependency** — see `MC-26.2-NOTES.md`.

## Building Baritone

No prebuilt release exists for 26.2.

```
git clone --depth 1 -b 26.2 https://github.com/cabaletta/baritone
cd baritone
./gradlew :fabric:build -x proguard -x test
```

`-x proguard` is required — that step needs a full JDK with `jmods` and
fails on a JRE. It is only release minification.

Result: `fabric/build/libs/baritone-fabric-1.18.0.jar`.
