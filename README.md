# BetterStructures

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
The complete source tree, including the wrapper, test fixtures and build pins,
is available as a [source archive](https://repo.rus-crafting.ru/grocermc/ru/ruscrafting/forks/betterstructures-source-tree/2.7.10-ruscrafting.2-upstream.15808b1/betterstructures-source-tree-2.7.10-ruscrafting.2-upstream.15808b1.jar).
The [validated implementation revision](https://github.com/alexey-va/BetterStructures/actions/runs/40936018990)
passed all 65 unit tests, assembly and production packaging guards.
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
The FAWE mirror preserves the observed installed snapshot/custom build. Its
embedded commit is
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
