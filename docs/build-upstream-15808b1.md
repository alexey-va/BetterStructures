# BetterStructures source snapshot for Paper 1.21.11

This build starts from upstream BetterStructures commit
`15808b170622d31255e0525f43847c2b2b242120`, including the author's delayed
chunk scan and shared plugin chunk hold fix. Its runtime version is
`2.7.10-ruscrafting.2+upstream.15808b1`.

The supported deployment tuple for this artifact is Paper/Purpur **1.21.11**,
FastAsyncWorldEdit **2.15.5-SNAPSHOT+1079481**, and, when installed, EliteMobs
**10.9.11**. The two clipboard implementations reference FAWE's tile SPI;
this artifact does not claim compatibility with a standalone WorldEdit provider.
The experimental native placement modules are outside this build.

## Source changes from upstream

The build compiles all normal `src/main` source, with three small platform
compatibility changes:

- `WorldInitializer`: use Paper 1.21.11's `keepSpawnLoaded(TriState.FALSE)` in
  place of the removed `keepSpawnInMemory(false)` method.
- `WorldEditUtils`: its empty read-only single-block clipboard implements
  FAWE's `removeEntity(Entity)` as a no-op and `tile(...)` as `false`.
- `SchematicDiskCache`: its rotation-preserving clipboard delegates the same
  two methods. Entity removal unwraps the view's entity wrapper; tile writes
  preserve the delegate coordinates.

The resource source retains upstream's literal version. `processResources`
stamps the actual artifact version into the packaged `plugin.yml`. No placement
engine or default configuration switch is introduced.

## Pinned inputs

Put these immutable files in `build/inputs`, or supply their paths with the
properties shown. The build rejects different bytes.

| Input | Path property | SHA-256 |
| --- | --- | --- |
| `MagmaCore-2.2.0-ruscrafting.paper-1.21.11-upstream.9ccd02d.jar` | `magmaCoreJar` | `e6154a9ee1f9d42a4c8e98d541786457f007f787685d163a82bb621c92cf65af` |
| `FastAsyncWorldEdit-Paper-2.15.5-1079481.jar` | `worldEditApiJar` | `9d3603ea0fb57397a9aa99607db61aba71562870fdce19d93493ab064c6b6c91` |
| `EliteMobs-10.9.11.jar` | `eliteMobsApiJar` | `807c43c515662e798b8eb47b16cb965be3cca182ba3f746f2d4ef8f49b5afec1` |

FAWE and EliteMobs are compile/test inputs, not shaded payloads. They are copies
of the observed installed server providers. MagmaCore is shaded and relocated
to BetterStructures' private package.

The production API is the immutable Paper coordinate
`io.papermc.paper:paper-api:1.21.11-R0.1-20260511.115010-91`.
`gradle.lockfile` fixes the resolved dependency versions, and
`gradle/verification-metadata.xml` fixes non-changing Maven artifacts and
metadata. Paper's POM is used because its timestamped Gradle module metadata
declares a second SNAPSHOT identity. Gradle excludes changing modules from
checksum verification, so `verifyPinnedLibraries` also checks these two
upstream optional compile-only API JARs explicitly:

| Coordinate | SHA-256 |
| --- | --- |
| `io.lumine:MythicLib-dist:1.5.2-SNAPSHOT` | `f8f4743d1062f0d5b74524cb129e14af043b60c7aba9f96a75b5b32ff007fc20` |
| `net.Indyuce:MMOItems-API:6.9.2-SNAPSHOT` | `1cfe0eba7f0d26f0f632fc8a9052ebce58e993c6e9acd97a93852c7efc6b19d4` |

Do not regenerate checksums to bypass a mismatch. Recover the pinned bytes or
review and version an intentional dependency update.

## Building the target MagmaCore input

Use unmodified source from
[`MagmaGuy/MagmaCore`](https://github.com/MagmaGuy/MagmaCore) at exact commit
`9ccd02d9413f23cdb876fb2052c697e534daf6bc`. The only target adaptation is the
external init script `gradle/magmacore-paper-1.21.11.init.gradle` from this
repository. It removes the distribution's `:nms:v26` dependency because the
26.3 BuildTools server artifacts are unavailable and outside the deployment
target. It retains all upstream-distributed 1.21 adapters, including both R7
adapters. No MagmaCore source is changed or installed over a local Maven
SNAPSHOT.

From that clean checkout, using JDK 25 as `JAVA_HOME` and making JDK 21 available
for its adapter compile toolchains:

```sh
env -u MC_DIST_DIR bash ./gradlew --no-daemon :dist:shadowJar \
  -PprojectVersion=2.2.0-ruscrafting.paper-1.21.11-upstream.9ccd02d \
  -Porg.gradle.java.installations.paths=<JDK21_HOME>,<JDK25_HOME> \
  -PtargetBuildProfile=<BETTERSTRUCTURES>/gradle/magmacore-paper-1.21.11.init.gradle \
  --init-script <BETTERSTRUCTURES>/gradle/magmacore-paper-1.21.11.init.gradle
```

Use an isolated writable `GRADLE_USER_HOME` if needed. Copy the output from
`dist/build/libs` to the pinned input path and verify the SHA-256 above. The
MagmaCore JAR records its source commit, target, excluded adapter and init script.
Its `NMSManager` maps Minecraft 1.21.11 to `v1_21_R7`, then selects the Paper
adapter when Paper is present; only Minecraft major versions 26 and later map
to `v26`. This source check does not replace lab runtime validation.

## Building BetterStructures

Use JDK 25 as `JAVA_HOME`; production classes are compiled with `--release 21`.
Lombok 1.18.42 supports this compiler. The author's MockBukkit 26.2 test fixture
runs on JDK 25 and is separate from the production API target.

```sh
env -u MC_DIST_DIR bash ./gradlew --no-daemon \
  test \
  --tests com.magmaguy.betterstructures.listeners.DelayedDungeonScanTrackerTest \
  --tests com.magmaguy.betterstructures.worldedit.PasteBudgetTicketTest \
  --tests com.magmaguy.betterstructures.worldedit.SchematicClipboardCacheTicketTest \
  --tests com.magmaguy.betterstructures.ContentReloadLifecycleTest \
  shadowJar sourcesJar verifyProductionJar
```

The 10 focused tests passed on the target build. They check delayed scan
tracking, paste budget, cache identity and reload lifecycle. They do not execute
a real server's asynchronous chunk lifecycle or prove provider ABI behavior in
an installed JVM. Lab acceptance must exercise both clipboard paths and the
author's chunk hold lifecycle with the actual provider tuple before activation.

`build/libs/BetterStructures.jar` is the runtime artifact. The sources JAR
contains the normal plugin sources plus this recipe, build files, dependency
pins and the MagmaCore target profile under `META-INF/build`.
`verifyProductionJar` checks the stamped version, author chunk-hold class,
R7 Paper adapter, Java 21 bytecode ceiling, and absence of experimental modules,
the v26 adapter, or bundled Paper/WorldEdit/EliteMobs APIs. Archive timestamps
and entry order are normalized for reproducible packaging.
