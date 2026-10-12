# CLAUDE.md

Notes for Claude sessions working on this repository.

## Project

Jrik's Minecraft Seed Finder: recovers a Minecraft **Java Edition** world seed from observations (structure
positions and the bedrock floor; later biomes). The owner is not a programmer; all development happens in Claude sessions, so explain
results in plain language and keep the README and `docs/ROADMAP.md` up to date when the status changes.

Scope: own worlds, servers with permission, server owners auditing their own seed. Never add features for
anti-cheat evasion, hiding the mod from servers, or bypassing server-side seed protection.

Mod design rule (agreed with the owner): the future Fabric mod registers **no chat commands** (server plugins may use
the same names, e.g. `/locate`, and a client command would intercept them) and **sends nothing to the server**. It
opens with a key binding (unbound by default), uses its own screen and an optional status line, and only reads what
the client already received.

## Layout

- `engine/`: Java library with the seed-finding logic. Package `io.github.jriiik.seedfinder`. No Minecraft dependency.
  - `rng/`: `JavaRandom` (exact java.util.Random / LegacyRandomSource LCG with state access), `XoroshiroRandom`
    (Xoroshiro128++ as Minecraft draws from it), `RandomSupport` (seed mixing, MD5 name hashes), positional factories,
    `RandomState` (per-name block generators of a dimension), `WorldgenSeeds` (incl. `positional` = `Mth.getSeed`).
  - `bedrock/`: bedrock rules per dimension (data from the oracle), `BedrockPattern` (which blocks are bedrock),
    `BedrockEvidence` (observations compiled for fast checking of many seeds), `BedrockText` (the notes format).
  - `structure/`: placements, `StructureLocator` (placement + frequency + exclusion zones; no biome checks yet).
  - `version/`: `MinecraftVersion` enum, data loaded from `src/main/resources/io/github/jriiik/seedfinder/versions/<v>/`.
  - `finder/`: Phase 2 structure-seed search. `StructureSeedFinder` (plan + search), `ObservationCheck` (low-bit
    lifting, fast checks, twin probabilities), `TopBitsSolver` (solves bits 35..47), `ObservationParser`.
    The plan's wrong-seed estimate uses factors measured with probes (`SHARED_LOW_BITS_FACTOR`, linked temples);
    `StructureSeedFinderTest.theWrongSeedEstimateMatchesReality` guards it. Re-measure if the search changes.
    Phase 3: `FullSeedFinder` (all 65,536 upper-bit values per structure seed, ranked by bedrock mismatches),
    `Seeds.randomSeedCandidates` (the `nextLong()` shortcut) and `textSeedCandidate`, `ObservationReader` (structures
    and bedrock notes in one file). `FullSeedFinder.expectedFalseFits` is measured too and guarded by
    `FullSeedFinderTest.theFalseFitEstimateMatchesReality`. Never present a seed with bedrock mismatches as found.
- `cli/`: picocli app `seedfinder` (`find`, `full-seed`, `structure-seed`, `locate`, `bedrock`, `structures`,
  `versions`).
- `tools/oracle/`: `run-oracle.sh <version>` downloads the official server jar into `tools/oracle/build/` and runs
  `Oracle.java` (plus `BedrockOracle.java` next to it, found by Java's multi-file source launcher) against the
  game's own code to write version data and test fixtures (`engine/src/test/resources/fixtures/<v>/`). Output is
  deterministic; rerunning must not change existing files. `BedrockOracle` generates real chunks up to the TERRAIN
  step without a server; that needs the static block tags bound (done in `loadVanillaWorldRegistries`).

## Commands

```
./gradlew build            # compile (-Xlint:all -Werror) and run all tests
./gradlew :cli:fatJar      # cli/build/libs/seedfinder.jar
JAVA_HOME=<jdk25> tools/oracle/run-oracle.sh 26.3
```

## Rules

- The engine must reproduce the game bit for bit. Every generation feature gets oracle fixtures from the real game
  and tests that compare against them. Do not hand-write "expected" values; compute or record them.
- Never commit Minecraft code, jars, decompiled sources or assets. Only facts (numbers, names) produced by the oracle.
- Engine and CLI target Java 21 (`options.release = 21`); Minecraft 26.x itself needs Java 25 (oracle, future mod).
- Dependencies go through `gradle/libs.versions.toml`.

## Sandbox notes

- Maven Central sometimes answers 429 (rate limit) from the cloud sandbox. Workaround used so far: a Gradle init
  script in `~/.gradle/init.d/` that swaps Maven Central for `https://maven-central.storage-download.googleapis.com/maven2/`.
  This lives outside the repo; recreate it when needed.
- JDK 25 is not preinstalled; Temurin 25 can be downloaded from `api.adoptium.net` (verify the SHA-256).
- To read game code: decompile the server jar (Vineflower) into the scratchpad, never into the repo.
