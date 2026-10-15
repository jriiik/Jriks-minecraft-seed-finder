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
  - `biome/`: overworld biomes of 26.x bit for bit (float arithmetic as the game's compiled samplers): `PerlinNoise`,
    `NormalNoise` (octaves from the world factory by name, so noises are made lazily), `ClimateFunctions` (the six
    climate functions of a preset from the oracle's `climate.json`; only the types the climate uses, others fail
    when read), `BiomeParameters` + `BiomeTree` (the list from `biome_parameters.json`; the game's RTree built exactly
    as the game builds it, because the tree and the last-result shortcut decide ties), `OverworldBiomes` (per version
    and preset; a `Sampler` is reused across seeds; `couldBe` = the biome is among the closest), `BiomeZoom` (F3's
    block biome, picked among 8 cells with the hashed seed).
  - `structure/`: placements, `StructureLocator` (placement + frequency + exclusion zones; no biome checks yet),
    `RigidStructures` (from version data `rigid_structures.json`: how each rigid structure's main piece is placed).
  - `version/`: `MinecraftVersion` enum, data loaded from `src/main/resources/io/github/jriiik/seedfinder/versions/<v>/`.
  - `finder/`: Phase 2 structure-seed search. `StructureSeedFinder` (plan + search), `ObservationCheck` (low-bit
    lifting, fast checks, twin probabilities), `TopBitsSolver` (solves bits 35..47), `ObservationParser`.
    The plan's wrong-seed estimate uses factors measured with probes (`SHARED_LOW_BITS_FACTOR`, linked temples);
    `StructureSeedFinderTest.theWrongSeedEstimateMatchesReality` guards it. Re-measure if the search changes.
    Phase 3: `FullSeedFinder` (all 65,536 upper-bit values per structure seed, ranked by bedrock mismatches),
    `Seeds.randomSeedCandidates` (the `nextLong()` shortcut) and `textSeedCandidate`, `ObservationReader` (structures
    and bedrock notes in one file). `FullSeedFinder.expectedFalseFits` is measured too and guarded by
    `FullSeedFinderTest.theFalseFitEstimateMatchesReality`. Never present a seed with bedrock mismatches as found.
    `TypedSeedSearch` checks all 2^32 typed seeds (text hashCodes and short numbers) against the hashed seed, in
    1024 resumable pieces, smallest numbers first; its progress gives a safe resume point (all pieces before it
    checked), never a plain count, and `LAYOUT` must change with the pieces. `Seeds.Hasher` is the fast reusable
    hashed-seed function. A seed found from the hashed seed alone is only presented after `SeedCheck` doesn't
    contradict it (servers may send a made-up hashed seed): confirmed when a wrong seed would fit the notes at least
    as well with a chance <= 0.001 (structures: tail bound over all checked notes, linked notes counted once;
    overworld bedrock: measured rate x a factor so that bedrock alone needs 300 blocks, because notes next to x = 0
    or z = 0 fit wrong seeds up to ~90x more often); any overworld bedrock mismatch contradicts it; nether bedrock
    (old chunks) and structures only contradict it when nothing confirms it. `SeedCheckTest` guards the measured
    rates (60 notes; an 18 x 18 layer around the origin).
    `BiomeEvidence`: biomes as proof of the upper 16 bits. Notes are grouped by place (within 512 blocks of a group's
    first note); groups count separately only `groupSpacing` apart (4,096; 16,384 for large biomes, whose noises are
    4x coarser), between the boxes around their notes; `groups` picks as many as can be (exactly up to 16 places,
    preferring more biomes, then more notes, then note order: from the notes only, never from candidates). Each
    group's rate is measured on the other candidates: (k + 3) / M. Proven when it is the only
    candidate that fits and H x 2^(G-1) x prod (k + 3) / M <= 0.001, H = all candidates checked or the likely seeds
    (Minecraft-picked/text). One loaded area proves a likely seed; others need two areas. Of stored cells only
    borders and one per chunk are checked (chosen from the notes, never from candidates). Presets are tried in turn
    (normal, large biomes, amplified) and every scan counts. Unknown biome names are left out of every check.
    `BiomeEvidenceTest.theFalseFitEstimateIsNotBelowReality` guards the estimate per preset (real <= estimate,
    real <= 1.5 x independent, estimate >= 500); re-measure if grouping, spacing or LIFT change. A seed proven
    otherwise is only flagged when biomes don't fit (old or blended chunks, data packs and /fillbiome make the right
    seed miss).
    `NetherBedrockSearch`: the structure seed from nether bedrock alone. Searches one surface's hidden number exactly
    (`BedrockSurfaceSearch`: single, pair and table tests, all superset tests; tests compare with trying every
    number), checks each key against the other surface, inverts `nextLong()` (with the borrow) twice. Proven with
    both surfaces, >= 58 bits together, the near-miss bound, and the chances of a wrong key added up over all
    searches of one `find`. `find` searches chunk by chunk, then together, then leaving out one chunk (old chunks fit
    nothing; the result without it is kept as candidates when not proven) and names chunks that don't fit; a weak
    chunk never rules out candidate keys. About 23% of structure seeds have a twin with the same nether bedrock, so
    the answer is one or two seeds. A capped list (too many fit) is never used as complete.
    `WorldSeedSearch` takes the structure seed from structures and/or nether bedrock (`StructureStep.withNether`:
    both agree -> both; structures can't search -> nether; proven nether disagrees -> the seeds of both sides, so the
    later steps decide; capped -> not used), then the hashed seed (`HashedSeed.genuine` in single player: none
    fitting means the structure seeds are wrong, NO_STRUCTURE_SEED, and bedrock/biome proofs must have the hash),
    overworld bedrock (only the overworld's after a nether search; certain only if structure seeds x expected false
    fits < 0.02), biomes (not presented when the bedrock fits another seed perfectly), likely seeds (minus those the
    biomes rule out). A world seed result names the noted structures that don't fit it (`Result.misfits`).
  - `recognize/`: start chunks of jigsaw structures from blocks. `StartPiece` (a start piece's blueprint: blocks,
    built/terrain split, reference point, anchors), `StartPieceRecognizer` (anchor -> candidate placements that pass
    the chunk-corner and region checks, looked up by the anchor's position in its chunk -> score; the all, built and
    "distinct" (built minus the most common built block) scores must each be >= 0.70), `RecognizedStarts` (grouping
    by set and region; disagreeing regions report nothing). No game data here: the mod builds the blueprints.
    Rigid structures (pyramids, jungle temples, swamp huts, igloos, shipwrecks, ocean ruins, ruined portals), ported
    from the measured research recognizer: `RigidKind` (per kind: trigger and informative block classes, thresholds),
    `RigidBlocks` (block classes: wood types, mossy/cracked variants...), `RigidLayout` (one variant in one pose, as
    offsets from the start chunk's corner, plus its empty spots), `RigidRecognizer` (votes only for origins on chunk
    corners, via tables by trigger class, position in the chunk and chunk offset; `verify` leaves out only fits that
    can never be accepted or be a rival (score sure to stay below minScore - 0.15; with `allVotes`, as the tracker
    counts them, also by votes + `RigidLayout.unvoted`); `acceptable` = thresholds, empty spots, ocean water, biome
    in 3 x 3 cells +-16 blocks; `isClusterPiece` by voting over the distinct small-ruin layouts; `resolve` strongest
    first: separation, overlap >= 0.3, ambiguity; reports only chunks where the set can start; each step also comes
    in small pieces: `Votes`, `Verification`, `ClusterTest` (budgeted by work, reusable `ClusterBuffers`),
    `Resolution`) and `RigidTracker` (the mod's chunk-by-chunk driver: a chunk none of whose own
    fits is acceptable is done once its `readSpan` is read; otherwise it is judged with the fits of the chunks within
    `RIVALS` = 2, once everything within `rivalReach` = `RIVALS + max(span, readSpan)` is loaded and read, or within
    `reach` = `RIVALS + clusterSpan` when a small ocean ruin in the batch needs the cluster test, so the
    answer never depends on what happens to be loaded; steps of at most `STEP_READS` = 512 block reads under a time
    budget; waiting chunks are looked at again when a chunk around them is read; a chunk read again makes it compare
    the fits around it afresh, its votes too if its trigger blocks changed; `forgetFarFrom` is done in slices by
    `work`). Any change must give the same
    answers: compare with the committed version on chunks made by the game's code (scratch probe, every vote count,
    fit, cluster test and tracker answer). Keep the thresholds unless a measurement on chunks made by the game's
    code shows a change is safe.
- `cli/`: picocli app `seedfinder` (`find`, `full-seed`, `typed-seed`, `structure-seed`, `locate`, `bedrock`,
  `biomes`, `structures`, `versions`). Notes: `biome <name> x y z` (F3), `biome-grid x y z a=... b=...` with rows of
  stored cells (the mod writes these), `preset large_biomes`. `find` searches nether bedrock alone or together with
  structures (`StructureStep.withNether`, as the mod) and names structures that don't fit the seed found;
  `FullSeedReport` uses the engine's certainty rule for bedrock and checks biomes when bedrock doesn't settle it.
- `mod/`: Fabric client mod for 26.3 (Loom, non-remapping, Mojang names; the engine is nested as jar-in-jar, Gson
  comes from Minecraft). `SeedFinderClient` registers an unbound key, a HUD line, chunk-load and system-message
  listeners; `WorldSession` holds per-world notes (opt-in `collecting`, saved as JSON plus a CLI-readable notes
  file in `config/jriksseedfinder/`); `SearchRunner` runs `WorldSeedSearch` on its own low-priority thread;
  `TypedSeedRunner` runs `TypedSeedSearch` in the background (a quarter of the CPU threads, progress saved, stops
  once the seed is known or the player leaves; kept by "Remove all notes", since it depends only on the hashed seed
  in the notes' key), and `SearchRunner` checks a typed seed against the notes (`SeedCheck`) before its structure
  search;
  `BedrockScanner` reads bedrock layers (B / natural stone / unknown; floor and roof separately, so a hole in the nether
  roof doesn't lose the floor); `BiomeScanner` reads the exact stored biome of each 4 x 4 x 4 cell of loaded overworld
  chunks at y 64, -48, -16 and 16 (a biome the vanilla list lacks marks the world as data-pack and stops biome notes);
  `WorldSession` keeps biome areas (up to 100 chunks within 192 blocks of an area's first chunk; a new area only where
  its notes are sure to be `groupSpacing` from every area's notes, about 4,500 blocks between first chunks; 4 areas,
  plus a fifth 16,384 + reach from one of them for large-biome worlds), written as `biome-grid` lines, and leaves
  out nether chunks the search found unusable (`leaveOutBedrock`: notes dropped, not read again, not counted against
  the 4-chunk limit); `SearchRunner` checks biomes (with a scan cache sized for every structure seed, preset and
  area) and searches nether bedrock (also with no structures); the biome list for `BiomeScanner` loads off the main
  thread; `StartPieceLibrary` builds the start-piece
  blueprints at runtime from the game jar's own data (structure, template pool, processor list, block tag JSON and
  `.nbt` templates; 26.x block states use `id`/`properties`); `RigidLibrary` builds the rigid layouts at runtime:
  templates from the game jar placed as `rigid_structures.json` says (turned with the game's
  `StructureTemplate.transform`), and pyramids, temples and huts by running the game's own piece code in a
  `SyntheticLevel` (an in-memory `WorldGenLevel`; no reflection or proxies). `StructureRecognition` scans loaded
  chunks for anchor and trigger blocks on the main thread (2 ms per tick, resumable scans; rigid judging gets 1 ms
  of it first and whatever is left at the end, one tracker per dimension; the rigid recognizers are made in the
  background with the layouts; ruined portals in the nether only when the player switches `netherPortals` on, since
  nether chunks made by 1.16 to 1.18.1 have them on a 25/10 grid) and notes recognized starts; `WorldSession`
  keys notes by address + hashed seed (`WorldIdentity.key`), checks recognized starts against all notes of their
  placement region, and persists starts not to note again (`removed_structures`: removed by the player, placed
  with /place (source `PLACED`), or contradicted; kept by "Remove all notes"). The answers to `/place structure`
  (`PlacedStructureMessage`) and `/place template` (`PlacedTemplateMessage`: every pose's start chunk, rigid
  templates about their pivot, start pieces by their reference point) are read also while collecting is off (then
  kept only in memory until collecting is switched on); recognition sets such matches aside, also from the region
  conflicts. `WorldSeedSearch.withoutMisfit` retries a failed search leaving out one structure
  and only returns a proven seed; the class tweaker opens
  `BiomeManager.biomeZoomSeed` (the hashed seed). `NoNetworkGuardTest` scans the built jar and fails on any
  command, networking, packet, mixin or reflection reference. `src/gametest` is an in-game test (real client under
  Xvfb) with a test-only mixin that works around Xvfb's missing sRGB framebuffer; it also teleports to one start of
  each recognized structure set (and a ruined portal in the nether) and checks the mod's answers against the
  server's own structure starts, runs `RecognitionSweep` (the recognizers on the nearest real starts of each set,
  at least one per set, searching farther for sets of rare biomes; chunks generated by the server; rigid ones in
  both dimensions), checks that nether portals are only noted once switched on, and checks that a /place'd camp,
  ruined portal and portal template are set aside and not noted. It also checks every biome cell the client received
  against the engine, F3's biome against `BiomeZoom`, that the area's biomes prove the seed, and that the nether
  bedrock read in the nether alone proves the structure seed.
  Recognition was measured offline with a probe that runs the real classes on chunks generated by the game's code
  (scratch harness, not in the repo); wrong answers matter far more than misses, so keep both thresholds at 0.70
  unless such a measurement shows a change is safe.
- `tools/oracle/`: `run-oracle.sh <version>` downloads the official server jar into `tools/oracle/build/` and runs
  `Oracle.java` (plus `BedrockOracle.java` and `RigidOracle.java` next to it, found by Java's multi-file source
  launcher) against the game's own code to write version data and test fixtures
  (`engine/src/test/resources/fixtures/<v>/`). Output is deterministic; rerunning must not change existing files.
  `BedrockOracle` generates real chunks up to the TERRAIN step without a server; that needs the static block tags
  bound (done in `loadVanillaWorldRegistries`). `RigidOracle` runs the game's structure generation in thousands of
  chunks (any biome allowed) and records how each rigid structure's main piece is placed; it fails unless every
  template was seen in every rotation and mirror. `BiomeOracle` writes `climate.json` and `biome_parameters.json`
  (version data, in the game's codec format and the game's list order) and `fixtures/26.3/biomes.json` (noise and
  climate values, a lookup series with ties and whole chunks filled on fresh threads, so the RTree's last-result
  shortcut starts empty, and the zoom).

## Commands

```
./gradlew build            # compile (-Xlint:all -Werror) and run all tests (Gradle must run on JDK 25 for Loom)
./gradlew :cli:fatJar      # cli/build/libs/seedfinder.jar
xvfb-run -a ./gradlew :mod:runProductionClientGameTest   # in-game test, about 2-5 minutes
JAVA_HOME=<jdk25> tools/oracle/run-oracle.sh 26.3
```

## Rules

- The engine must reproduce the game bit for bit. Every generation feature gets oracle fixtures from the real game
  and tests that compare against them. Do not hand-write "expected" values; compute or record them.
- Never commit Minecraft code, jars, decompiled sources or assets. Only facts (numbers, names) produced by the oracle.
- Engine and CLI target Java 21 (`options.release = 21`); Minecraft 26.x itself needs Java 25 (oracle, mod), and
  Gradle runs on JDK 25 (CI too).
- The mod must stay command-free and send-free; extend `NoNetworkGuard` rather than weakening it. Only the passive
  Fabric API modules listed in `mod/build.gradle.kts` may be used.
- Dependencies go through `gradle/libs.versions.toml`.

## Sandbox notes

- Maven Central sometimes answers 429 (rate limit) from the cloud sandbox. Workaround used so far: a Gradle init
  script in `~/.gradle/init.d/` that swaps Maven Central for `https://maven-central.storage-download.googleapis.com/maven2/`.
  This lives outside the repo; recreate it when needed.
- JDK 25 is not preinstalled; Temurin 25 can be downloaded from `api.adoptium.net` (verify the SHA-256). In earlier
  sessions it was at `/opt/jdks/jdk-25.0.4.1+1`.
- Xvfb and Mesa (llvmpipe) are installed, so the in-game test runs here; its first run downloads ~480 MB of assets.
- To read game code: decompile the server jar (Vineflower) into the scratchpad, never into the repo.
