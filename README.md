# BetterStructures

## Native placement experiment

Branch `experiment/native-structures` contains an independent lab prototype in
`native-prototype/`. It reads Sponge `.schem` block palettes without WorldEdit,
prepares chunks asynchronously, and applies block changes in a shared tick
budget. It runs only on the isolated loopback lab backend on port 54295 and only
in its own `rc_native_lab` world.

```sh
./gradlew -p native-prototype test assemble
```

The test plugin is `native-prototype/build/libs/ArcNativeStructuresLab-0.1.0.jar`.
Startup runs synthetic dense/sparse placements through the same schematic decoder
and placement queue and records per-phase timings, actual block writes and native
tick-duration distributions. Entity and block-entity content is explicitly
unsupported in this experiment. This branch tests placement mechanics; it does
not replace BetterStructures world selection, content management or integrations.

`native-generation-prototype/` adds a separate 0.2.0 candidate for placement
during native terrain generation. Its immutable schematic plan assigns every
non-air position to one central chunk. A `BlockPopulator` writes that slice using
only `LimitedRegion` on generation workers, before FULL activation. The runner
compares a vanilla control with generated structures, verifies target blocks
after FULL activation under a main-thread read budget, and records a final
post-release observation window. This variant uses its own
`rc_native_generation_lab` world and the same lab-only plugin identity; install
one candidate at a time.

The pinned API's [BlockPopulator contract](https://github.com/PaperMC/Paper/blob/bd74bf6581ce81e59bdab07eadbfbe5d485eefa7/paper-api/src/main/java/org/bukkit/generator/BlockPopulator.java)
requires concurrent-safe callbacks and limits access to `LimitedRegion` during
population. The server [invokes the LimitedRegion overload](https://github.com/PaperMC/Paper/blob/bd74bf6581ce81e59bdab07eadbfbe5d485eefa7/paper-server/patches/sources/net/minecraft/world/level/chunk/ChunkGenerator.java.patch#L96-L119)
and [fires WorldInitEvent before initial generation](https://github.com/PaperMC/Paper/blob/bd74bf6581ce81e59bdab07eadbfbe5d485eefa7/paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch#L371-L382).
The benchmark refuses placement on the main thread and requires a fresh
population receipt for every planned chunk, including the vanilla control.

The first generation run is preserved in
`native-generation-prototype/results/2026-10-29-generation-first.json`. All 56
planned chunks produced fresh receipts on `Paper Common Worker #0`; all four
structures matched independent final-world hashes and released their tickets.
Dense 64³ wrote 262,144 blocks in 140.39 ms of summed population-worker time;
its entire generation-to-FULL phase took 13.50 s, against 13.80 s for the
16-chunk control. Worker time is not time-to-ready, and the regions have different
terrain. Native generation phases reached 49.23 ms / 46.77 ms maxima for the
dense/sparse 64³ cases; the control reached 75.15 ms during generation and
1078.51 ms during settle. This is not evidence of globally spike-free operation.
The first creation of the secondary test world also took a 22.14 s server tick
and triggered watchdog diagnostics; that harness startup path is recorded
separately from structure placement.
The current candidate prepares fixtures/palettes and creates the lab world in
`onEnable`, before starting the benchmark ticker. The decode worker has a
30-second await limit and never depends on main-thread tasks. Startup cost stays
explicit in the report rather than appearing as an in-game WORLD phase.

The final replay is preserved as
`native-generation-prototype/results/2026-10-29-generation-final.json` (candidate
SHA256 `8f2d2790dbe3a777f82f2ca7c7f462e79c76c4c3166b1fa6e9a3a0c9db81b385`).
It loaded the existing lab world during startup in 1.37 s with no watchdog or
ERROR lines; this does not repeat cold first-world creation. All 331,776 target
blocks again matched independent hashes; all 56 callbacks ran on the generation
worker and all native tickets were released.

| Final case | Actual writes | Summed population worker time | Entire generation to FULL |
| --- | ---: | ---: | ---: |
| Vanilla control, 16 chunks | 0 | 2.93 ms | 20.40 s |
| Dense 32³ | 32,768 | 59.10 ms | 7.89 s |
| Sparse 32³ | 4,096 | 2.15 ms | 9.00 s |
| Dense 64³ | 262,144 | 102.16 ms | 10.80 s |
| Sparse 64³ | 32,768 | 27.21 ms | 17.40 s |

The final dense 64³ generation phase had a 21.75 ms p99 and 25.04 ms maximum
server tick. The whole active window still reached 1183.88 ms in control settle,
and a control FULL request took 255.11 ms. No global no-spike guarantee follows
from off-main structure writes. This is a two-CPU/2 GiB lab benchmark without
client load driven by the test, not production-load acceptance. This prototype
handles block palettes only; terrain-fit rules, block entities/loot, protection
and content-pack integration remain outside its scope.

```sh
./gradlew -p native-generation-prototype test assemble
```

The 0.1.0 placement queue shares a 1.5 ms tick budget, checks time after each operation,
and caps block work at 4096 positions per tick. Schematic decoding compacts air
off-thread while preserving coordinates; reset still visits the entire volume.
Only one asynchronous chunk request is outstanding. Native block-state setup
runs on the main thread; the initial warmup is measured separately from idle and
active benchmark windows. An individual native call cannot be interrupted by
the time budget, so measured tick maxima remain part of acceptance.

Raw lab measurements live in `native-prototype/results/`. The first run used a
512-position cap and is retained as `2026-10-29-lab-512.json`: its overall maximum
was 85.09 ms, while warmed dense 64³ placement reached 10,102 writes/s with a
3.72 ms p99 tick. Subsequent reports must distinguish startup, preparation,
placement and reset, and include actual remaining native plugin chunk tickets.

The optimized 4096-position run is retained as `2026-10-29-lab-4096.json`.
All four write/air counters matched and all native ticket counts returned to
zero. Dense 64³ apply improved from 25.95 s to 12.65 s; sparse 64³ from 25.80 s to
1.95 s. The active maximum increased to 141.41 ms and a chunk-request slice took
115.66 ms, so this run does **not** meet a no-spike acceptance criterion. Startup
native-state warmup took 287.88 ms outside the active window. These synthetic
measurements are from the isolated Purpur 1.21.11 lab with two-CPU affinity and a
2 GiB maximum heap; they are not production or player-client acceptance.

The diagnostic build records bounded JFR (32 MiB / ten minutes), including native
request/pin spans, placement phases, timestamped server-tick durations, execution
samples and GC/safepoints. It dumps an immutable UUID receipt off-thread next to
the JSON report. Profiling results must be identified separately from unprofiled
performance runs.

The exact lab server [pins Paper bd74bf6](https://github.com/PurpurMC/Purpur/blob/f57bd8659b5c3cbaf03984ee5a8ecdcc0849040a/gradle.properties#L6).
Its [CraftWorld async API](https://github.com/PaperMC/Paper/blob/bd74bf6581ce81e59bdab07eadbfbe5d485eefa7/paper-server/src/main/java/org/bukkit/craftbukkit/CraftWorld.java#L1989-L1998)
requests FULL status; Moonrise [immediately processes ticket updates on the tick thread](https://github.com/PaperMC/Paper/blob/bd74bf6581ce81e59bdab07eadbfbe5d485eefa7/paper-server/patches/features/0001-Moonrise-optimisation-patches.patch#L8698-L8705).
Calling this API from a worker queues the same work on the main executor. A
worker-call change alone would not establish lower server-tick latency.

The instrumented run and bounded JFR analysis are retained as
`2026-10-29-lab-jfr.json` and `2026-10-29-lab-jfr-analysis.json`. A 105.11 ms
native chunk-request span overlaps a server-thread sample in chunk-holder/ticket
propagation and no recorded GC pause. The longest 187.96 ms tick overlaps a
99.78 ms GC pause plus entity/collision samples; GC does not explain its entire
duration. Other long ticks include native FULL activation and chunk save/unload
samples. Ten-millisecond sampling identifies observed work, not an exact cost
breakdown for every method. Reproduce the bounded analysis with:

```sh
jfr print --json --events ru.ruscrafting.NativeSlice,jdk.ExecutionSample,jdk.GCPhasePause,jdk.ExecuteVMOperation,jdk.SafepointBegin recording.jfr | python3 native-prototype/analyze_jfr.py
```

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
