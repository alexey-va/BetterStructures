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



## Integrated BetterStructures lab backend

This experimental branch builds `2.7.10-ruscrafting.lab.5+upstream.15808b1`.
The frozen four-schematic comparison below used lab.3; its runtime SHA is recorded with the results.
Its opt-in [manifest](src/main/resources/native-lab.example.yml) lives at
`plugins/BetterStructures/assets/native-lab.yml` and selects `DEFAULT` or
`NATIVE_GENERATION`. It is restricted to the loopback lab at `127.0.0.1:54295`.
Each boot creates a unique vanilla world with the same explicit seed and inputs.

The comparison covers the clipboard block phase: DEFAULT uses the real
BetterStructures shared paste queue; NATIVE_GENERATION writes immutable slices
through `LimitedRegion` before FULL activation. WorldEdit remains the schematic
reader. Automatic terrain fitting, loot, entities and finishing are outside
this subset. Unsupported metadata is rejected and recorded. AIR clears,
BARRIER preserves and BEDROCK uses an explicit constant pedestal provider;
complete before/expected/final block hashes and settled differences are checked.
The production artifact described below is built on `master` and excludes this
backend. These lab builds are not production candidates.

## Automatic fitting readiness experiment

An additional lab-only opt-in is
`plugins/BetterStructures/assets/chunk-preparation-lab.yml`:

```yaml
enabled: true
world: rc_native_generation_lab
maxQueued: 64
maxHeld: 256
```

It retains the selected fitter and clipboard, prepares its candidate footprint
with async FULL requests, then retains the paste footprint plus a physics halo
through normal Preparation, Clipboard and Finishing stages. It keeps normal
physics, NBT, loot, marker and entity handling. It accepts only the fixed
loopback lab and world; the manifest comparison worlds are outside its scope.

Paper's plugin tickets promote a two-chunk FULL activation ring. The experiment
marks that activation contour before requesting chunks, pins each desired chunk
inside its FULL callback, then waits for the whole activation contour before
resuming fitting on a later tick. Halo-only chunks never receive plugin tickets.
The opt-in requires Minecraft 1.21.11, whose ticket level and expiry behavior were
checked against the running Paper source. After cancellation, suppression remains
for three observed world ticks while native tickets retire; an in-flight request
keeps its own marker until completion. Requests and work slices are bounded, and
completion diagnostics include active/queued jobs, pending requests and holds.
The probe's `requireChunkPreparation=true` option requires those counters to be
idle before reporting completion. Runtime measurements are recorded separately
from the frozen native/DEFAULT block-phase comparison.

The first readiness run (lab.4) failed its liveness check: unretained FULL chunks
unloaded while the coordinator waited for the whole contour, causing repeated
halo requests before fitting could start. It produced no structure placements
and is not a performance result. The [failed report](lab-results/2026-10-29/prepared-lab4-failed-report.json)
and [receipt](lab-results/2026-10-29/prepared-lab4-failed-receipt.json) preserve
that outcome. Lab.5 changes the retention order and adds an eviction regression.

## Real-schematic A/B result, 2026-10-29

Both engines used the same four original production schematics, seed 384291,
anchors, provider JAR and constant STONE pedestal. All four initial volume
hashes matched. This tests the clipboard block phase with identity transforms;
99 of the 103 inventory assets were rejected for this restricted native subset.

| Measured active window | DEFAULT | NATIVE_GENERATION |
| --- | ---: | ---: |
| Main tick maximum | 3306.07 ms | 147.94 ms |
| Main tick p99 | 71.45 ms | 38.82 ms |
| Main tick p95 | 27.99 ms | 7.96 ms |
| Final volume equal to saved block states | 3 / 4 | 4 / 4 |

Three final hashes also match across engines. DEFAULT changed two nether-cistern
stairs from `inner_left` to `straight`; native retained the stored shapes.
The strict DEFAULT benchmark therefore failed and remains recorded as failed;
this is not evidence that the engines are universally equivalent. Native wrote
1621 blocks in 41.76 ms of summed population-worker time, including observation
capture. That worker time is a different metric from DEFAULT's queued wall time.

Active tick windows exclude startup, content wait, idle baseline and report IO.
Content-wait ticks reached 570.24 ms / 595.95 ms; native FULL activation, unload,
physics and GC still produced ticks above 50 ms. This is one controlled pair on
the isolated two-CPU/2 GiB lab, without production or client load. The next
experiment addresses the independently confirmed sync chunk loads in automatic
terrain fitting and physics; it is separate from this frozen comparison.

[Comparison and exact inputs](lab-results/2026-10-29/comparison.json),
[DEFAULT report](lab-results/2026-10-29/default-report.json),
[native report](lab-results/2026-10-29/native-report.json).
The [latest upstream probe](lab-results/2026-10-29/upstream-scan-report.json)
loaded all 103 schematics, exercised 16 fresh surface-grid origins and completed
eight natural structures without a deadlock. Its maximum tick was 3054.76 ms.

## Cold tick investigation, 2026-10-29

A replay reproduced a **892.82 ms** vanilla-control tick with no structure
writes; the runner used only **0.0067 ms** of that tick. The saved Spark
background profile identifies the first `CraftEntityTypes.<clinit>` beneath
`FallingBlockEntity.fall` → `Entity.getBukkitEntity`: 820 ms of sampled time in
the first minute, with no later occurrence of that class initializer. Sampling
supports this cause for the reproduced spike; the older 1183.88 ms tick had no
matching profile and cannot be attributed exactly.

An opt-in startup warmup creates a detached ArmorStand through Paper's public
`World.createEntity`, verifies that it is absent from the world entity registry,
and reports its cost separately. In the controlled replay it moved **1656.61 ms**
of adapter initialization into startup; the active maximum fell to **207.19 ms**.
This is one controlled pair on a two-CPU/2 GiB lab, not a global no-spike result.
Native FULL requests, chunk activation/save/unload, physics and GC can still
exceed 50 ms. The warmup remains an explicit lab option and is not in the
production snapshot.

[Unwarmed replay](native-generation-prototype/results/2026-10-29-unprofiled-spike.json),
[warmed replay](native-generation-prototype/results/2026-10-29-entity-adapter-warmup.json),
[filtered Spark replay](native-generation-prototype/results/2026-10-29-spark-slow50.json).
The foreground run sampled only ticks over 50 ms; the background capture retained
the reproduced cold spike. Profile hashes and attribution limits are recorded
in the [investigation receipt](native-generation-prototype/results/2026-10-29-cold-spike-cause.json).

## RusCrafting source build: 2.7.10-ruscrafting.2+upstream.15808b1

This build compiles the normal `src/main` tree from upstream commit
[`15808b170622d31255e0525f43847c2b2b242120`](https://github.com/MagmaGuy/BetterStructures/commit/15808b170622d31255e0525f43847c2b2b242120).
It includes the author's fix for
[issue #71](https://github.com/MagmaGuy/BetterStructures/issues/71): defer terrain
scans beyond `ChunkLoadEvent`, retain each queued scan's chunk, and share chunk
holds with pending pastes so one consumer cannot release another's hold.

This artifact targets **Paper/Purpur 1.21.11** with **FastAsyncWorldEdit
2.15.5-SNAPSHOT+1079481** and optional **EliteMobs 10.9.11**. It compiles against
the exact observed FAWE and EliteMobs JARs. FAWE's clipboard tile SPI is used;
standalone WorldEdit compatibility is not claimed for this build.

The only plugin source changes from that upstream commit are the equivalent
Paper spawn-retention API call in `WorldInitializer` and two mechanical FAWE
interface methods in each of the single-block and rotation-preserving clipboard
views. The packaged version is stamped by Gradle. No experimental placement
backend or configuration switch is included.

Bundled MagmaCore is built from unmodified upstream commit
[`9ccd02d9413f23cdb876fb2052c697e534daf6bc`](https://github.com/MagmaGuy/MagmaCore/commit/9ccd02d9413f23cdb876fb2052c697e534daf6bc)
using the [1.21.11 target profile](gradle/magmacore-paper-1.21.11.init.gradle).
The profile excludes only the out-of-target v26 adapter and retains the 1.21
adapters, including R7 Paper. It is a target-specific library distribution.
The complete source, compatibility changes and dependency pins are described in
the [build recipe](docs/build-upstream-15808b1.md).

The runtime descriptor version contains `+upstream.15808b1`; the Maven release
coordinate uses a hyphen:
[`ru.ruscrafting.forks:betterstructures:2.7.10-ruscrafting.2-upstream.15808b1`](https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/forks/betterstructures/2.7.10-ruscrafting.2-upstream.15808b1/betterstructures-2.7.10-ruscrafting.2-upstream.15808b1.jar).
The previous binary overlay is retired; its implementation remains in Git
history. The current runtime and CI use the root source build.

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

For this RusCrafting artifact, use Java 21 or newer, **Paper/Purpur 1.21.11**,
and **FastAsyncWorldEdit 2.15.5-SNAPSHOT+1079481**. The descriptor retains
upstream's Minecraft API version `1.21.4`; that value is not a claim that this
targeted build supports every later server or WorldEdit provider. MagmaCore is
included and privately relocated in the JAR.

1. Install the target FAWE provider and put `BetterStructures.jar` in `plugins/`.
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

Use JDK 25 and the included Gradle wrapper. Production classes target Java 21;
the upstream MockBukkit 26.2 test fixture runs on JDK 25. The production API is
the immutable Paper coordinate
`io.papermc.paper:paper-api:1.21.11-R0.1-20260511.115010-91`.

Download the three immutable inputs to the default paths expected by Gradle:

```sh
mkdir -p build/inputs
curl --fail --location --retry 3 \
  --output build/inputs/MagmaCore-2.2.0-ruscrafting.paper-1.21.11-upstream.9ccd02d.jar \
  https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/thirdparty/magmacore-paper12111/2.2.0-source.9ccd02d/magmacore-paper12111-2.2.0-source.9ccd02d.jar
curl --fail --location --retry 3 \
  --output build/inputs/FastAsyncWorldEdit-Paper-2.15.5-1079481.jar \
  https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/thirdparty/fawe-paper-api/2.15.5-snapshot.1079481/fawe-paper-api-2.15.5-snapshot.1079481.jar
curl --fail --location --retry 3 \
  --output build/inputs/EliteMobs-10.9.11.jar \
  https://cdn.modrinth.com/data/ypMp4OVC/versions/PN80pvAb/EliteMobs.jar
env -u MC_DIST_DIR ./gradlew --no-daemon check assemble sourcesJar
```

| Input | SHA-256 |
| --- | --- |
| MagmaCore, source `9ccd02d`, Paper 1.21.11 profile | `e6154a9ee1f9d42a4c8e98d541786457f007f787685d163a82bb621c92cf65af` |
| FAWE 2.15.5-SNAPSHOT+1079481 | `9d3603ea0fb57397a9aa99607db61aba71562870fdce19d93493ab064c6b6c91` |
| EliteMobs 10.9.11, observed official JAR | `807c43c515662e798b8eb47b16cb965be3cca182ba3f746f2d4ef8f49b5afec1` |

`verifyPinnedLibraries` checks these bytes before compilation. Maven dependency
versions and checksums are committed in `gradle.lockfile` and
`gradle/verification-metadata.xml`; the two optional compile-only SNAPSHOT APIs
also have explicit SHA-256 guards. EliteMobs is downloaded directly from its immutable official Modrinth version.
The FAWE mirror preserves the installed snapshot, whose embedded commit is
[`1079481486d4cf04283aaa905309cf47cbbe9bf1`](https://github.com/IntellectualSites/FastAsyncWorldEdit/commit/1079481486d4cf04283aaa905309cf47cbbe9bf1);
its corresponding source archive is available alongside the mirrored binary.
[FAWE source archive](https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/thirdparty/fawe-paper-api-sources/2.15.5-snapshot.1079481/fawe-paper-api-sources-2.15.5-snapshot.1079481.jar).
[Bundled MagmaCore sources](https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/thirdparty/magmacore-paper12111-sources/2.2.0-source.9ccd02d/magmacore-paper12111-sources-2.2.0-source.9ccd02d.jar).
FAWE and EliteMobs are not shaded. Avoid
Maven Local snapshots and do not replace a checksum after an unreviewed update.

The outputs are `build/libs/BetterStructures.jar` and
`build/libs/BetterStructures-2.7.10-ruscrafting.2+upstream.15808b1-sources.jar`.
`assemble` runs `verifyProductionJar`, which checks the runtime version,
required chunk-hold/R7 adapter classes, Java 21 bytecode ceiling and excluded
platform/experimental payloads. CI preserves those JARs and the unit-test
reports. Server activation and gameplay acceptance require a separate lab run
with the target provider tuple; compilation and MockBukkit do not establish
native chunk lifecycle or client behavior.

## Developer API

[Java class and method reference](https://wiki.nightbreak.io/javadoc/betterstructures/index.html).

Structure generation events: [BetterStructures developer reference](https://wiki.nightbreak.io/BetterStructures/developers). See the [Java API index](https://wiki.nightbreak.io/developers) for dependency setup and lifecycle guidance.

For this source build, use Maven coordinate
`ru.ruscrafting.forks:betterstructures:2.7.10-ruscrafting.2-upstream.15808b1`
from [the RusCrafting repository](https://repo.rus-crafting.ru/grocermc/).
Use `provided` or `compileOnly` scope for the installed plugin.

## License

See [LICENSE](LICENSE). Content packs have their own distribution terms.
