# BetterStructures

## Temporary RusCrafting build: 2.7.10-ruscrafting.1

This fork temporarily restores the chunk-load callback correction described in
[upstream issue #71](https://github.com/MagmaGuy/BetterStructures/issues/71).
The official 2.7.10 binary still scans terrain inside `ChunkLoadEvent`, although
the public GitHub source already defers that work. On Purpur 1.21.11 this path
blocked the main thread while a terrain read waited for an unfinished chunk.
The [maintainer confirmed](https://github.com/MagmaGuy/BetterStructures/issues/71#issuecomment-6498340825)
that deferral was deliberately reverted because pregenerators could unload a
chunk before its deferred scan. This fork retains the chunk until that scan
finishes, rather than restoring deferral alone.

The runtime build starts from the exact official 2.7.10 release, SHA-256
`77219d27dcdd423fbeddd95a735d5bcd600a6a0f4e002ce52df262a587846aff`.
It compiles the recovered 2.7.10 listener, reentrancy guard and paste-readiness
owner with the callback fix and a small shared ticket lease. Queued scans retain
their chunk until the scan finishes, including during pregeneration. Scan and
paste consumers share ticket ownership, so finishing one cannot release the
other's chunk. Only these class families, the new lease helper and the plugin
version change; every other archive entry is verified against the baseline.
The original license and bundled dependencies are preserved. The upstream `src/`
tree remains the public 2.7.7 source; `ruscrafting/` owns the matching 2.7.10 runtime
correction and build. The three existing runtime source classes were recovered with
[CFR 0.152](https://www.benf.org/other/cfr/cfr-0.152.jar), SHA-256
`f686e8f3ded377d7bc87d216a90e9e9512df4156e75b06c655a16648ae8765b2`.
The callback change follows upstream commit
[`f6057b6`](https://github.com/MagmaGuy/BetterStructures/commit/f6057b6f8d68162b223c495bb1dc2064cf3190ad).

Build and verify the fork with JDK 25 (production bytecode targets Java 21):

```sh
./gradlew -p ruscrafting check assemble
```

The runtime artifact is
`ruscrafting/build/libs/BetterStructures-2.7.10-ruscrafting.1.jar`.
The focused regression executes the real packaged event and paste handlers,
checks next-tick scheduling, pregenerator unloads, shared scan/paste ownership,
reload/error/shutdown cleanup and nested-scan suppression, and rejects the
official 2.7.10 handler. It verifies the observed callback boundary; native
terrain generation and lighting behavior still require observation on the target
server. The existing drain limits each tick's work; it does not impose a hard
cap on pending tickets if a producer indefinitely outruns the queue. No pending
structure is silently dropped to enforce such a cap.

Published runtime JAR:
[ru.ruscrafting.forks:betterstructures:2.7.10-ruscrafting.1](https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/forks/betterstructures/2.7.10-ruscrafting.1/betterstructures-2.7.10-ruscrafting.1.jar).
Return to an official release after checking that its binary contains the
callback-deferral correction and verifying the affected gameplay path.

## Upstream project

BetterStructures adds schematic structures and modular dungeons to Minecraft worlds. It places content on the surface, underground, in the sky, and on liquid surfaces, with generator rules for terrain, biomes, worlds, spacing, and loot.

[Download](https://nightbreak.io/plugin/betterstructures/) · [Modrinth](https://modrinth.com/plugin/betterstructures) · [Documentation](https://wiki.nightbreak.io/) · [Support](https://discord.gg/nightbreak)

## Features

- Generate standalone WorldEdit schematics or assemble larger builds from modules.
- Install and manage structure and module packs through the in-game setup menu.
- Configure chest and barrel loot, including per-schematic treasure overrides.
- Combine structures with EliteMobs or MythicMobs encounters when those integrations are installed.
- Control generation by world, environment, terrain, and generator configuration.
- Pregenerate terrain with configurable work limits and TPS-based pausing.

## Requirements and installation

Use Java 21 or the newer Java version required by your server, a compatible Spigot/Paper server, and **WorldEdit**. The plugin declares Minecraft API version `1.21.4`; check the download page for the supported server versions of your chosen release. MagmaCore is included in the JAR.

1. Install WorldEdit and put `BetterStructures.jar` in `plugins/`.
2. Start the server and run `/bs initialize` in-game as an administrator.
3. Select the worlds and content you want to use. `/bs setup` opens content management afterward.
4. Explore newly generated terrain to encounter structures. Installing a pack does not populate every existing chunk retroactively.

EliteMobs, MythicMobs, WorldGuard, and the world-generator integrations listed in [plugin.yml](src/main/resources/plugin.yml) are optional. A content pack can require an integration even when the base plugin does not.

## Commands and permissions

The main command is `/betterstructures`, with alias `/bs`. Use tab completion for installed schematic and generator names.

| Command | Purpose |
| --- | --- |
| `/bs initialize` | First-time setup and world selection. |
| `/bs setup` | Install and manage content packs. |
| `/bs reload` | Reload configuration and content. |
| `/bs place <schematic> <type>` | Place a schematic using a surface, sky, liquid-surface, or underground placement type. |
| `/bs generateModules <generator.yml>` | Generate a modular build in a dedicated world. |
| `/bs pregenerate <center> <shape> <radius> <applyWorldBorder>` | Pregenerate chunks; radius is in blocks. |
| `/bs cancelPregenerate` | Cancel pregeneration in the current world. |
| `/bs silent` | Toggle structure-generation notices for yourself. |
| `/bs version` | Show the installed version. |

Pregeneration centers are `HERE`, `WORLD_CENTER`, or `WORLD_SPAWN`; shapes are `SQUARE` or `CIRCLE`. The final argument is `TRUE` or `FALSE`.

Administrative commands use `betterstructures.*`, while setup and module-generation operations also expose specific permissions such as `betterstructures.setup`, `betterstructures.initialize`, and `betterstructures.generatemodules`. See [plugin.yml](src/main/resources/plugin.yml) and the [command implementations](src/main/java/com/magmaguy/betterstructures/commands) for individual checks.

## Configuration

Generated configuration lives under `plugins/BetterStructures/`. Start with the setup menu, then edit the generated files and their inline comments for world choices, structure generators, module generators, schematics, and treasure tables. Keep backups before changing generation on an established world.

The global settings include generation distances, administrator notices, dungeon protection, and the fraction of each tick used for pasting and pregeneration. Generator and schematic settings determine which content can spawn and how its loot is selected.

If nothing appears, check the selected world, installed packs, generator biome and environment restrictions, and whether you are exploring new terrain. Include the relevant generator and schematic configuration with support reports.

## Building from source

Build with JDK 21 and the included Gradle wrapper:

```powershell
.\gradlew.bat shadowJar
```

On Unix, use `./gradlew shadowJar`. The deployable file is `build/libs/BetterStructures.jar`. Set `MC_DIST_DIR` to copy it to a shared artifact directory. When changing MagmaCore locally, publish the matching MagmaCore version to Maven Local before rebuilding.

## Developer API

[Java class and method reference](https://wiki.nightbreak.io/javadoc/betterstructures/index.html).

Structure generation events: [BetterStructures developer reference](https://wiki.nightbreak.io/BetterStructures/developers). See the [Java API index](https://wiki.nightbreak.io/developers) for dependency setup and lifecycle guidance.

Maven: `com.magmaguy:BetterStructures:2.7.1` from [MagmaGuy's repository](https://repo.magmaguy.com/#/releases). Use `provided` or `compileOnly` scope for the installed plugin.

## License

See [LICENSE](LICENSE). Content packs have their own distribution terms.
