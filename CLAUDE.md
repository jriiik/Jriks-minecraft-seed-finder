# CLAUDE.md

Notes for Claude sessions working on this repository.

## Project

Jrik's Minecraft Seed Finder: recovers a Minecraft **Java Edition** world seed from observations (structure
positions, the bedrock floor and roof, biomes). Supported versions: **26.3** and **1.21.11** (`MinecraftVersion`; one
engine and CLI for both, one mod jar per version). The owner is not a programmer; all development happens in Claude
sessions, so explain results in plain language and keep the README and `docs/ROADMAP.md` up to date when the status
changes.

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
  - `biome/`: overworld biomes bit for bit, per version. `ClimateEvaluator` (the six climate functions of a preset;
    per-thread `State` with `reset`, `climate` = the float the game quantizes, `exact`) has two implementations, each
    parsing only its own version's `climate.json` format and only the types the climate uses (others fail when read):
    `ClimateFunctions` (26.x: float arithmetic as the game's compiled samplers, `NormalNoise`) and
    `ClassicClimateFunctions` (1.21.x: the classic codec format (`argument1`/`argument2`, `shifted_noise`,
    `flat_cache`/`cache_2d`/`cache_once`, `y_clamped_gradient`, router fields inline) in double arithmetic as the
    game's `DensityFunctions`: `ClassicNormalNoise` (two octave stacks, per-octave factors, `valueFactor`),
    `PerlinNoise.sampleDouble` (ImprovedNoise in double; the float `sample` is 26.x's), add/mul with a constant side =
    `MulOrAdd` (no shortcut for 0), `blend_alpha`/`blend_offset` 1/0 but not constants, splines in float from the
    coordinate cast to float, `Mth.clampedMap` in double; a column cache over a y-dependent function is refused).
    `NoiseGenerators` is where both versions' noises get their layers (id hash, two forked stacks, `octave_<n>`
    hashes, second stack x 1.0181268882175227); only the arithmetic differs. `BiomeParameters` + `BiomeTree` (the list
    from `biome_parameters.json`; the game's RTree built exactly as the game builds it, with the version's children
    per node, because the tree and the last-result shortcut decide ties), `OverworldBiomes` (per version and preset;
    exhaustive switches on the version pick the evaluator and `treeChildrenPerNode`: 1.21.11 classic double + 6,
    26.3 float + 19; with 19 on 1.21.11 ~1 cell in 5,000 is wrong, which the search fixture catches (its rows are
    ready-made climate targets, so it checks only the tree: 242 of 1884 lookups differ with 19); float for double ~2
    per million, which only the noise and climate fixtures catch (`noisesMatchTheGame` and
    `climateMatchesTheGameBitForBit` compare 1.21.11's doubles bit for bit; each parser also refuses the other
    version's format), so never loosen those comparisons; a `Sampler` is reused across seeds; `couldBe` = the biome is
    among the closest), `BiomeZoom` (F3's block biome, picked among 8 cells with the hashed seed; same in both).
  - `structure/`: placements, `StructureLocator` (placement + frequency + exclusion zones; no biome checks yet),
    `RigidStructures` (from version data `rigid_structures.json`: how each rigid structure's main piece is placed).
  - `version/`: `MinecraftVersion` enum (`V1_21_11`, `V26_3`, oldest first; `latest()` = the last = 26.3; `byName`,
    `supportedNames()`), data loaded from `src/main/resources/io/github/jriiik/seedfinder/versions/<v>/` (7 files per
    version, the same formats except `climate.json`). 1.21.11 vs 26.3: structure sets (the 20 shared ones, placements
    included), bedrock rules, `rigid_structures.json`, RNG, seeds and zoom are equal
    (`VersionDataTest.sharedStructuresBedrockAndRigidStructuresAreTheSameInBothVersions`), but that is the placement
    data: stronghold positions follow the biomes and differ (27 of the 384 in `strongholds.json`), so a stronghold
    feature must be tested on each version's fixture; 26.3 adds
    `abandoned_camp` and the biomes `dappled_forest` (92 entries that are plains in 1.21.11) and `sulfur_caves`; the
    climate is computed differently (see `biome/`). Anything that assumes a structure exists must ask the version
    data.
  - `finder/`: Phase 2 structure-seed search. `StructureSeedFinder` (plan + search), `ObservationCheck` (low-bit
    lifting, fast checks, twin probabilities), `TopBitsSolver` (solves bits 35..47), `ObservationParser`.
    The plan's wrong-seed estimate uses factors measured with probes (`SHARED_LOW_BITS_FACTOR`, linked temples);
    `StructureSeedFinderTest.theWrongSeedEstimateMatchesReality` guards it. Re-measure if the search changes.
    Phase 3: `FullSeedFinder` (all 65,536 upper-bit values per structure seed, ranked by bedrock mismatches),
    `Seeds.randomSeedCandidates` (the `nextLong()` shortcut) and `textSeedCandidate`, `ObservationReader` (structures
    and bedrock notes in one file; the version line `minecraft <version>` (`versionLine`, exactly "minecraft " +
    `displayName()`; "Minecraft: 1.21.11", "minecraft version 1.21.11" and a trailing # comment are read too; the
    version starts with a digit, so "minecraft:igloo" stays a structure): `minecraftVersion(lines)` rejects an
    unknown version or two different ones, `read(data, lines)` rejects a line naming another version than `data`, a
    line that starts like one but has more ("minecraft 1.21.11 world") is a version-line error; callers read notes
    without the line as `latest()`; `Observations.minecraft()`), `ObservationParser` (a structure the version lacks
    names the versions that have it). `FullSeedFinder.expectedFalseFits` is measured too and guarded by
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
    nothing; the result without it is kept as candidates when not proven) and names chunks that don't fit; every chunk
    that can be searched alone is (FITS_NO_SEED whenever such a search finds nothing: every number was tried); a weak
    chunk never rules out candidate keys: a key is ruled out only with >= `MIN_MISMATCHES_TO_RULE_OUT` (3) blocks
    wrong and keys x 2^-`separatingBits` <= 0.001 (a surface whose hidden number two keys share doesn't count). About 23% of structure seeds have a twin with the same nether bedrock, so
    the answer is one or two seeds. A capped list (too many fit) is never used as complete.
    `WorldSeedSearch` takes the structure seed from structures and/or nether bedrock (`StructureStep.withNether`:
    both agree -> both; structures can't search, or fit none -> nether candidates; proven nether disagrees -> the
    seeds of both sides, so the later steps decide; capped -> not used), then the hashed seed (`HashedSeed.genuine`
    in single player: none fitting means the structure seeds are wrong, NO_STRUCTURE_SEED, and bedrock/biome proofs
    must have the hash), overworld bedrock (`bedrockForFullSeed`: nether notes still count, except chunks found
    unusable for sure (`unusableNetherChunks`: FITS_NO_SEED, or CONTRADICTS of a proven result) and all of them in a
    proven disagreement; certain only if structure seeds x expected false fits < 0.02), biomes (not presented when
    the bedrock fits another seed perfectly), likely seeds (minus those the biomes rule out when some seed fits them
    (`Decision.ruleOut`), and those the overworld bedrock rules out when some seed fits it; none after a biome/bedrock
    disagreement). A world seed result names the noted structures that don't fit it (`Result.misfits`).
    `BiomeEvidence.fitChance` uses the best part of the counted groups (a group whose LIFT x rate is >= 1 is left
    out: fitting all groups implies fitting any part). A seed is only taken as fitting (and proven) when it also fits
    every cell noted, the thinned-out ones included (`fits`).
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
  - Tests: `Fixtures.versions()` (every `MinecraftVersion`, so a version without fixtures fails) and the cached
    `Fixtures.load(version, file)`; every test that compares with the game is parameterized over all versions
    (`@ParameterizedClass`/`@ParameterizedTest`; doubles compared bit for bit for 1.21.11, floats for 26.3). Guards of
    measured estimates: `BiomeEvidenceTest` (whole class) and `SeedCheckTest`'s structure rate run for both versions
    (1.21.11 real/estimate/independent: 948/1845/921 normal, 1025/2042/1019 large, 922/1783/890 amplified); the
    bedrock-rate guards, `StructureSeedFinderTest.theWrongSeedEstimateMatchesReality` and most of
    `NetherBedrockSearchTest` run on 26.3 only, with comments saying why (equal data, checked by `VersionDataTest`).
    A new version must pass every guard, or argue in a comment why one version suffices.
- `cli/`: picocli app `seedfinder` (`find`, `full-seed`, `typed-seed`, `structure-seed`, `locate`, `bedrock`,
  `biomes`, `structures`, `versions`). Notes: `biome <name> x y z` (F3), `biome-grid x y z a=... b=...` with rows of
  stored cells (the mod writes these), `preset large_biomes`. `find` searches nether bedrock alone or together with
  structures (`StructureStep.withNether`, as the mod), names structures that don't fit the seed found, and retries
  without each structure (`withoutMisfit`) when none fits; `full-seed` runs the nether search only to leave out
  chunks no seed made; `FullSeedReport` uses the engine's certainty rule for bedrock, checks biomes when bedrock
  doesn't settle it, and narrows by nether notes only when every seed ruled out gets >= 3 blocks wrong and
  (seeds - 1) x 2^-`separatingBits` <= 0.001 (it never leaves chunks out itself). Version: `-m`/`--minecraft`
  (`VersionOption`; picocli's own `--version` is the app's); `resolve(commandLine, notes)` (find, full-seed,
  typed-seed with notes) = the option, else the notes' `minecraft <v>` line, else `latest()`, and refuses an option
  that contradicts the line; `bedrock` and `biomes` print the version line in the notes they write, `full-seed`
  and `typed-seed` with notes print "Minecraft <v>." first, `locate`/`structures` take the option alone, `versions`
  lists them. A printed command that reads the same notes file carries `--minecraft <v>` when the file doesn't name
  a version that isn't the newest (`VersionOption.argumentFor`); when the version was only assumed (`assumed`: no
  option, no line) and biome notes fit no seed, don't fit the seed found or rule likely seeds out, the output says
  so (`assumedVersionHint`), since biomes differ between versions. `ObservationInput` gives version-line errors no
  structure hint, and a structure written right that only another version has a hint about the version instead.
- `mod/`: Fabric client mod, one source tree for every Minecraft version (the engine is nested as jar-in-jar, Gson
  comes from Minecraft). Shared, version-neutral code: `mod/src/{main,test,gametest}` (chunk coordinates through
  `Chunks` (`getMinBlockX() >> 4`, the game's packing formula), the key type from `InputConstants.UNKNOWN.getType()`,
  `SyntheticLevel.getShade` without `@Override` (only pre-26.x has it), data keys read in both formats). One thin
  Gradle project per version, `mod/versions/<v>/` = `:mod-26.3`, `:mod-1.21.11` (`settings.gradle.kts` lists the
  versions and sets their projectDir; the root `build.gradle.kts` loads both Loom plugin ids once with
  `apply false`, as Loom wants with several projects), each with: its `build.gradle.kts` (26.3:
  `net.fabricmc.fabric-loom`, non-remapping, Mojang names, release 25, `jar` classifier `mc26.3`; 1.21.11:
  `net.fabricmc.fabric-loom-remap`, `mappings(loom.officialMojangMappings())`, `modImplementation`, release 21,
  `remapJar` to intermediary names with classifier `mc1.21.11` (the unremapped jar is in `build/devlibs`),
  `remapGametestJar`, `-Dseedfinder.mappings` for the guard test); adapters `GameVersion` (src/main: `NAME`,
  `registerKey`, `addStatusLine`, `setScreen`, `vanillaData`) and `GameTestVersion` (src/gametest:
  `waitForChunksRender`); its class tweaker (`classTweaker v1 official` for 26.3; `named` for 1.21.11, remapped to
  intermediary by Loom); gametest resources (gametest `fabric.mod.json`; 26.3's Xvfb sRGB mixin and its config live
  only in `mod/versions/26.3`). `mod/shared.gradle.kts` (applied by each version file after it sets the extras
  `minecraftVersion`, `javaRelease`, `keyModule`, `shippedJar`) holds the shared steps: the shared source dirs,
  engine jar-in-jar, `-Xlint:all -Werror` at the version's release (unit tests at 25: `java.lang.classfile`), the
  `fabric.mod.json` template (`${minecraft}`, `${java}`, `${keyModule}`), the test task (shipped jar,
  `seedfinder.minecraftVersion`) and `gametestJar`; Loom's own settings stay in the version files (an applied
  script can't see Loom's classes). Jars: `jriks-seed-finder-mod-<version>-mc<minecraft>.jar` ("version" in
  fabric.mod.json stays plain). `SeedFinderClient.minecraftVersion()` = `MinecraftVersion.byName(GameVersion.NAME)`,
  used for the data, the biome list, `WorldSession` and the gametest; no `V26_3` in mod code. `GameVersionTest` fails
  when `NAME` or the shipped jar's minecraft dependency isn't the version the project builds for.
  `SeedFinderClient` registers an unbound key, a HUD line, chunk-load and system-message
  listeners; `WorldSession` holds per-world notes (opt-in `collecting`, saved as JSON plus a CLI-readable notes
  file in `config/jriksseedfinder/`; `notesLines()` = `ObservationReader.versionLine(version)` first, then the notes:
  the saved `.txt`, "Copy notes" and `SearchRunner`'s searches all read exactly that text, and the engine checks the
  line against the data it reads with); `SearchRunner` runs `WorldSeedSearch` on its own low-priority thread;
  `TypedSeedRunner` runs `TypedSeedSearch` in the background (a quarter of the CPU threads, progress saved, stops
  once the seed is known or the player leaves; kept by "Remove all notes", since it depends only on the hashed seed
  in the notes' key), and `SearchRunner` checks a typed seed against the notes (`SeedCheck`) before its structure
  search;
  `BedrockScanner` reads bedrock layers (`read(dimension, block)`: B / natural stone / unknown; floor and roof
  separately, so a hole in the nether roof doesn't lose the floor). Unknown in every version, too: blocks the terrain
  step can put in a bedrock layer before the roll, where the roll never ran (1.21.x rolls only on the default block,
  `SurfaceSystem`'s `blockState == defaultBlock`; 26.x on any non-air, non-fluid block): the iron-vein blocks tuff,
  raw iron block and deepslate iron ore (`OreVeinifier`, y -60..-8; measured once at a rolled-bedrock spot in
  1,600 chunks on 1.21.11) and overworld lava (aquifer lava below y -54). Nether lava stays '.': the nether's terrain
  step only leaves netherrack at y 0..8 and 120..128 (no veins, no aquifers). Without this the right seed could get
  a mismatch (a miss; seeds with mismatches are never presented). For 26.3 this is a deliberate change (slightly
  fewer known blocks; the gametest still notes ~3,700). `BedrockScannerTest` boots Minecraft and runs in both
  projects. `BiomeScanner` reads the exact stored biome of each 4 x 4 x 4 cell of loaded overworld
  chunks at y 64, -48, -16 and 16 (a biome the running version's list lacks marks the world as data-pack and stops
  biome notes);
  `WorldSession` keeps biome areas (up to 100 chunks within 192 blocks of an area's first chunk; a new area only where
  its notes are sure to be `groupSpacing` from every area's notes, about 4,500 blocks between first chunks; 4 areas,
  plus a fifth 16,384 + reach from one of them for large-biome worlds), written as `biome-grid` lines, and leaves
  out nether chunks the search found unusable (`leaveOutBedrock`: notes dropped, not read again, not counted against
  the 4-chunk limit; once 8 are left out, chunks within 8 chunks of one are not read, so newer land farther away
  is; at most 256 remembered; a chunk contradicting a proven result is only left out when the structures agree with
  it); `SearchRunner` checks biomes (with a scan cache sized for every structure seed,
  preset and area), caches the structure search and the nether search separately, and searches nether bedrock
  (also with no structures); the biome list for `BiomeScanner` loads off the main thread; `StartPieceLibrary` builds the start-piece
  blueprints at runtime from the game jar's own data (structure, template pool, processor list, block tag JSON and
  `.nbt` templates; block states and palette properties are `id`/`properties` in 26.x and `Name`/`Properties` in
  1.21.x, both read; `setsOf(data)` = the jigsaw sets the version data has, so no camps on 1.21.11: 40 start pieces
  of 5 sets vs 220 of 6); `RigidLibrary` builds the rigid layouts at runtime (440 in both versions):
  templates from the game jar placed as `rigid_structures.json` says (turned with the game's
  `StructureTemplate.transform`), and pyramids, temples and huts by running the game's own piece code in a
  `SyntheticLevel` (an in-memory `WorldGenLevel`; no reflection or proxies). `StructureRecognition` scans loaded
  chunks for anchor and trigger blocks on the main thread (2 ms per tick, resumable scans; rigid judging gets 1 ms
  of it first and whatever is left at the end, one tracker per dimension; the rigid recognizers are made in the
  background with the layouts; ruined portals in the nether only when the player switches `netherPortals` on, since
  nether chunks made by 1.16 to 1.18.1 have them on a 25/10 grid) and notes recognized starts; `WorldSession`
  keys notes by address + hashed seed (`WorldIdentity.key`; the same key in every version, and jars of two versions
  may share the config folder, so a saved structure whose set the running version lacks is kept aside in
  `otherVersions`: saved again, a comment in the notes, never searched), checks recognized starts against all notes of their
  placement region, and persists starts not to note again (`removed_structures`: removed by the player, placed
  with /place (source `PLACED`), or contradicted; kept by "Remove all notes"). The answers to `/place structure`
  (`PlacedStructureMessage`) and `/place template` (`PlacedTemplateMessage`: every pose's start chunk, rigid
  templates about their pivot, start pieces by their reference point) are read also while collecting is off (then
  kept only in memory until collecting is switched on); recognition sets such matches aside, also from the region
  conflicts. `WorldSeedSearch.withoutMisfit` retries a failed search leaving out one structure
  and only returns a proven seed; the class tweaker opens
  `BiomeManager.biomeZoomSeed` (the hashed seed). `NoNetworkGuardTest` scans the jar that ships (26.3's `jar`,
  1.21.11's `remapJar`) and fails on any command, networking, packet, mixin or reflection reference. For a remapped
  jar `NoNetworkGuard.Names` reads Loom's Tiny v2 mappings (`loom.mappingsFile`, passed as `-Dseedfinder.mappings`
  by 1.21.11's build file) and turns intermediary class names (nested ones too) and member names (a member maps to
  all its named names) back into Mojang names before the same rules run; an intermediary name it can't translate,
  any intermediary name with no mappings given, and a mapping with an empty name are problems themselves. Tests
  catch an intermediary-named command sender and packet field (sample mappings in both projects, the real mappings
  in 1.21.11). `src/gametest` is an in-game test (real client under Xvfb; 26.3 adds a test-only mixin that works
  around Xvfb's missing sRGB framebuffer, which 1.21.11 doesn't need); it also teleports to one start of
  each recognized structure set (and a ruined portal in the nether) and checks the mod's answers against the
  server's own structure starts, checks that the version data's jigsaw sets equal the server registry's, runs
  `RecognitionSweep` (the recognizers on the nearest real starts of each set,
  at least one per set, searching farther for sets of rare biomes; chunks generated by the server; rigid ones in
  both dimensions; 24 jigsaw starts in 26.3, 20 in 1.21.11), checks that nether portals are only noted once switched
  on, and checks that a /place'd abandoned camp (a pillager outpost where the version has no camps, after checking
  no real start of either is near), ruined portal and portal template are set aside and not noted. It also checks
  every biome cell the client received against the engine, F3's biome against `BiomeZoom`, that the area's biomes
  prove the seed, that the nether bedrock read in the nether alone proves the structure seed, and that "Copy notes"
  and the saved notes file start with the version line and read back through the engine.
  Recognition was measured offline with a probe that runs the real classes on chunks generated by the game's code
  (scratch harness, not in the repo); wrong answers matter far more than misses, so keep both thresholds at 0.70
  unless such a measurement shows a change is safe.
- `tools/oracle/`: `run-oracle.sh <version>` downloads the official server jar into `tools/oracle/build/` and runs
  `Oracle.java` (plus `BedrockOracle.java` and `RigidOracle.java` next to it, found by Java's multi-file source
  launcher) against the game's own code to write version data and test fixtures
  (`engine/src/test/resources/fixtures/<v>/`). Output is deterministic; rerunning must not change existing files.
  Sources per version: `tools/oracle/<v>/*.java` when that folder exists (`tools/oracle/1.21.11/`), else
  `tools/oracle/*.java` (26.x). The copies differ in API calls only (1.21.11: bedrock rules read through the codec
  JSON, as its surface-rule records are package-private; the bedrock harness without carvers, safe because bedrock
  is only rolled on the default block and no vanilla carver tag has bedrock; `RandomState.sampler()` for climate
  rows) and must record the same facts. Obfuscated versions (the version JSON has `downloads.server_mappings`):
  the script reads `fabric-loom` from `[versions]` in `gradle/libs.versions.toml`, writes a Loom project into
  `tools/oracle/build/<v>/loom` (`net.fabricmc.fabric-loom-remap`, `minecraft(...)`,
  `mappings(loom.officialMojangMappings())`, a `printClasspath` task), runs it with the repo's `gradlew` on the same
  JDK (log `tools/oracle/build/<v>/loom.log`), takes exactly one `minecraft-merged-*.jar` (Loom's merged
  client+server jar with Mojang names) and adds the server bundle's libraries in `META-INF/libraries.list` order
  (the server jar is still downloaded and SHA-1 checked for them); it needs Java >= 22 (multi-file source launcher;
  use 25). Runs take ~1.5 min for 26.3 and ~8-10 min for 1.21.11. The 1.21.11 output headers still say "calls the
  official Minecraft server code" (kept so the bytes stay identical).
  `BedrockOracle` generates real chunks up to the TERRAIN step without a server; that needs the static block tags
  bound (done in `loadVanillaWorldRegistries`). `RigidOracle` runs the game's structure generation in thousands of
  chunks (any biome allowed) and records how each rigid structure's main piece is placed; it fails unless every
  template was seen in every rotation and mirror. `BiomeOracle` writes `climate.json` and `biome_parameters.json`
  (version data, in that version's codec format, classic for 1.21.11, and the game's list order) and
  `fixtures/<v>/biomes.json` (noise and climate values, a lookup series with ties and whole chunks filled on fresh
  threads (1.21.11: a fresh `Climate.ParameterList` per chunk, since `RTree.lastResult` is a per-tree ThreadLocal),
  so the RTree's last-result shortcut starts empty, and the zoom).
- `.github/workflows/`: `build.yml` (every push and pull request: a `build` job, `./gradlew build`, uploading
  `seedfinder` and one artifact per mod jar (an upload step per version); an `in-game-test` matrix over `26.3` and
  `1.21.11`, fail-fast off, 45 min, its own asset cache and screenshot artifact per version) and `release.yml` (a
  published GitHub release: `version` checks the tag and outputs it without a leading v; `app` runs
  `./gradlew build -Pversion=...` and uploads `seedfinder.jar`; `mod`, a matrix per Minecraft version, runs
  `:mod-<v>:build` and then the in-game test on that same jar and uploads it; `attach`, the only job with
  `contents: write`, needs `app` and `mod`, downloads `release-*` merged and uploads `seedfinder.jar`,
  `jriks-seed-finder-mod-<version>-mc26.3.jar` and `jriks-seed-finder-mod-<version>-mc1.21.11.jar`, so nothing is
  attached unless all passed; it uploads after publishing, so it would fail if the repository's "immutable
  releases" setting were switched on). A new version goes into both matrices and gets an upload step in `build.yml`.

## Commands

```
./gradlew build            # compile (-Xlint:all -Werror) and run all tests (Gradle must run on JDK 25 for Loom)
./gradlew :cli:fatJar      # cli/build/libs/seedfinder.jar
./gradlew :mod-26.3:build  # mod/versions/26.3/build/libs/jriks-seed-finder-mod-<version>-mc26.3.jar
./gradlew :mod-1.21.11:build   # mod/versions/1.21.11/build/libs/jriks-seed-finder-mod-<version>-mc1.21.11.jar
xvfb-run -a ./gradlew :mod-26.3:runProductionClientGameTest      # in-game test, about 5-10 minutes here
xvfb-run -a ./gradlew :mod-1.21.11:runProductionClientGameTest   # in-game test, about 5-12 minutes here
JAVA_HOME=<jdk25> tools/oracle/run-oracle.sh 26.3      # ~1.5 minutes
JAVA_HOME=<jdk25> tools/oracle/run-oracle.sh 1.21.11   # ~8-10 minutes (Loom step first)
```

## Rules

- The engine must reproduce the game bit for bit. Every generation feature gets oracle fixtures from the real game
  and tests that compare against them. Do not hand-write "expected" values; compute or record them.
- Never commit Minecraft code, jars, decompiled sources or assets. Only facts (numbers, names) produced by the oracle.
- Engine and CLI target Java 21 (`options.release = 21`); Minecraft 26.x itself needs Java 25 (oracle, mod), and
  Gradle runs on JDK 25 (CI too). The 1.21.11 mod compiles main and gametest for Java 21 (its game's), unit tests
  for 25.
- Adding or changing one version must not change another version's answers; a deliberate change must be explained
  (in the commit and the docs). Version differences are chosen by the version (exhaustive switches), never guessed
  from the data.
- The mod must stay command-free and send-free; extend `NoNetworkGuard` rather than weakening it (it must scan the
  jar that ships, through the mappings for remapped jars). Only the passive Fabric API modules listed in each
  `mod/versions/<v>/build.gradle.kts` may be used (the same five in every version; the key module is
  `fabric-key-mapping-api-v1` in 26.x and `fabric-key-binding-api-v1` in 1.21.11). Keep per-version code (adapters,
  build files) as small as possible; everything else goes in `mod/src` and `mod/shared.gradle.kts`.
- The oracle copies (`tools/oracle/*.java` and `tools/oracle/<v>/*.java`) must record the same facts: a change to
  one (a new fixture, a format change, new seeds) goes into every copy by hand; nothing checks this automatically.
- Dependencies go through `gradle/libs.versions.toml` (per-version entries `minecraft-26-3`, `fabric-api-26-3`,
  `minecraft-1-21-11`, `fabric-api-1-21-11`, looked up by name in the version build files; `fabric-loom` is also
  read by `run-oracle.sh`).

## Sandbox notes

- Maven Central sometimes answers 429 (rate limit) from the cloud sandbox. Workaround used so far: a Gradle init
  script in `~/.gradle/init.d/` that swaps Maven Central for `https://maven-central.storage-download.googleapis.com/maven2/`.
  This lives outside the repo; recreate it when needed.
- JDK 25 is not preinstalled; Temurin 25 can be downloaded from `api.adoptium.net` (verify the SHA-256). In earlier
  sessions it was at `/opt/jdks/jdk-25.0.4.1+1`.
- The oracle's Loom step (obfuscated versions like 1.21.11) can still hit Maven Central's 429: plugins.gradle.org
  redirects to `repo.maven.apache.org`, which the init script doesn't rewrite. The script then stops at once with
  "The Loom project in ... failed; see .../loom.log"; retry later (once Loom and the game are in Gradle's cache, the
  step downloads nothing).
- Xvfb and Mesa (llvmpipe) are installed, so the in-game test runs here; its first run for a version downloads that
  version's assets (~480 MB for 26.3). The 1.21.11 in-game test needs
  `-Dfabric.client.gametest.disableNetworkSynchronizer` (Fabric API 0.141.6's harness never finishes loading a world
  with its packet synchronizer on); its build file already passes it.
- To read game code: decompile the server jar (Vineflower) into the scratchpad, never into the repo. 1.21.11's
  server jar has obfuscated names: decompile Loom's Mojang-named `minecraft-merged-*.jar` (from the oracle's Loom
  step, in Gradle's cache) instead.
