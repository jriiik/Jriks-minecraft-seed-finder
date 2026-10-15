# Roadmap

## Decisions so far

- **Minecraft Java Edition only**, starting with the newest version (26.3). Bedrock generates worlds completely
  differently and would be a separate project.
- **Java** for everything (the engine, the CLI and later the mod).
- **Written from scratch**, so we fully understand and control every part.
- **CLI first**, then a Fabric client mod that collects observations automatically.
- **One shared engine** with no dependency on Minecraft's code, used by both the CLI and the mod.
- **The mod adds no chat commands and sends nothing to the server.** Typed `/` commands go to the server, where
  plugins may already use names like `/locate`, and a client command with the same name would intercept them. So
  the mod opens with a key the player chooses (unbound by default), shows its own screen and an optional status
  line, and only reads what the game already received (the blocks of loaded chunks). This keeps it from clashing
  with any server and makes it obvious what it does.

## How seed finding works

1. **Structure positions** (villages, temples, outposts...) depend only on the lowest 48 bits of the seed. A
   handful of known structure positions narrows 2^48 possibilities down to one "structure seed".
2. **The remaining 16 bits** (65,536 candidates): worlds created without typing a seed get it from Java's random
   generator (`nextLong()`), and only one or two 64-bit seeds with a given structure seed can come out of it. Text
   seeds leave one. To be certain, the overworld's bedrock floor, which depends on all 64 bits, is checked for every
   one of the 65,536 candidates. Biomes would work too and are planned for later.
3. **Verify**: regenerate the structures and bedrock from the candidate seed and compare with what was observed.

## Phases

| Phase | Goal | Status |
|---|---|---|
| 0 | Project setup: Gradle build, engine + CLI modules, README, automatic builds | ✅ |
| 1 | Engine basics for 26.3: Java's RNG, Minecraft's seeding helpers, structure placement (frequency rolls and exclusion zones included), checked against the real game | ✅ |
| 2 | Find the 48-bit structure seed from a list of structure observations (`seedfinder find`) | ✅ |
| 3 | Find the full 64-bit seed: the `nextLong()` shortcut, text seeds, and the overworld bedrock floor | ✅ |
| 4 | CLI polish: a `verify` command, saving results | 🔜 |
| 5 | Fabric client mod: notes structures and bedrock while you explore, recognizes 13 structure types by itself, shows progress, turned on per server | ✅ |
| 6 | Extras: structure seed from nether bedrock alone, biomes, more data sources (End pillars), older versions | 🔜 |

### Phase 2: how the structure seed search works

Each structure tries to start once per square region of the world; which chunk it picks comes from
`nextInt(n)` on a seed made of the structure seed, the region and a per-structure "salt".

1. **Low bits first.** When n is even (temples: 24 = 8 x 3, villages: 26 = 2 x 13...), the lowest bits of the
   result only depend on the lowest 18 to 20 bits of the structure seed. All combinations of those are tried
   (about a million) and only the few that fit every observation are kept.
2. **Top bits solved, not searched.** Minecraft scrambles the seed with a 35-bit constant, so the top 13 bits pass
   straight through. For each combination of the remaining middle bits, the top 13 bits that fit one observation
   follow from a small modular equation (`TopBitsSolver`), instead of trying all 8192.
3. **Check and confirm.** Every candidate gets fast position checks for all observations on all CPU cores, and
   every hit is confirmed with the engine's complete rules (frequency rolls, exclusion zones).

Typical searches take well under a second. Villages-only searches need more structures and can take a minute.

**What we learned while testing:**

- *Look-alike seeds.* Seeds that share the true seed's low bits shift every structure's roll in related ways, so
  structures whose region sizes share odd factors (3 for temples and outposts, 13 for villages, 5 for shipwrecks)
  tend to agree on them. Six structures of those kinds still leave about 3 look-alikes on average; one ruined
  portal (25), abandoned camp (29), trial chamber (11) or ancient city (16) removes nearly all of them. The finder
  names these types when several seeds fit.
- *Linked temples.* Desert pyramids, igloos, jungle temples and swamp huts use the same grid with salts 1 apart,
  so two of them in the same region are nearly the same roll: the second adds about 4 bits of information
  instead of 9.
- The planner's estimate of how many seeds will fit is calibrated against hundreds of real searches.

### Phase 3: how the full seed is found

- **The `nextLong()` shortcut.** With an empty seed box (singleplayer) or an empty `level-seed` (servers), Minecraft
  26.3 uses `RandomSource.create().nextLong()`: two 32-bit outputs of Java's 48-bit generator glued together. The
  structure seed fixes all of the second output and 16 bits of the first, so stepping the generator back from the
  65,536 possible hidden bits gives every possible seed: none for 11% of structure seeds, one for 77%, two for 11%.
  For a world with a random seed, the true seed is always among them (tested with 300 seeds made by the game).
- **Text seeds** become a 32-bit `hashCode()`, so at most one fits a structure seed.
- **Bedrock.** The overworld floor uses the newer Xoroshiro128++ generator, seeded from all 64 bits. Each block
  at y=-63..-60 draws one number from its own generator and is bedrock with chance 80/60/40/20%. With bedrock
  notes the finder checks all 65,536 candidates (about a tenth of a second for one chunk of notes) and ranks them by
  how many blocks they get wrong. The engine's bedrock matches 184,320 blocks of real chunks generated by the game.

**What we learned while testing:**

- *Bedrock is less random than it looks.* Each block's number is the first output of an unmixed generator, so the
  whole floor depends on only a few hidden numbers. The information grows like 3·log(n) instead of linearly in n.
  Measured: one chunk of top-bedrock heights (about 630 informative blocks) gives a unique answer practically always
  (99.6%); an 8 x 8 area about 83% of the time.
- *A wrong structure seed can still fit.* Among the 65,536 seeds of a wrong structure seed, about 65,536·(c/n)^3 fit
  n bedrock blocks perfectly, with c between 2.3 and 3.2: about 0.4% of the time for a whole chunk, 20% for 8 x 8.
  The finder uses this (with c = 3.5) to warn when the bedrock area is small. It never reports a seed that gets
  blocks wrong as found (it names the closest one and lists the blocks to check), because even a wrong structure
  seed has full seeds that miss only a few blocks of a chunk.
- *Nether bedrock* uses the old 48-bit generator, so it only depends on the structure seed. It can tell look-alike
  structure seeds apart, and later (Phase 6) it could find the structure seed without any structures.
- *Near the middle of the world the false-fit rate is higher* (found in the review of the typed-seed check). Notes
  next to x = 0 or z = 0 are fitted by wrong seeds more often than the c = 3.5 estimate says: for a 16 x 16 single
  layer around the origin about 10 times on average and up to about 90 times in single worlds; for a chunk of
  heights about 2 times (up to about 14). The typed-seed check allows for it. The bedrock proof of the structure
  search still uses the plain estimate; it only matters there when the structure seed itself is wrong, but it
  should be re-measured and given the same margin.

### Phase 5: the mod

- **Opt-in per world.** Nothing is read until the player switches collecting on for that world or server. Notes
  are kept per address (or save name) and hashed seed, so worlds behind one address never mix.
- **The hashed seed.** Every server sends players a SHA-256 hash of the world seed (for biome blending). Once the
  structure seed is known, checking its 65,536 full seeds against that hash settles the seed at once. If no
  candidate matches (a server may send a different value), the mod says so and uses bedrock instead.
- **Typed seeds from the hashed seed alone.** A seed typed as text becomes its 32-bit `hashCode()`, and short typed
  numbers are 32-bit too, so all typed seeds are among the 2^32 `int` values. Checking all of them against the hashed
  seed takes 2^32 SHA-256 hashes: about 60 ns each per CPU thread with SHA instructions (4.5 minutes on one thread,
  a little over a minute on four), about 290 ns without them (about 21 minutes on one thread), and about 430 ns on
  CPUs that also lack AVX2 (about 31 minutes). The values are
  checked smallest numbers first, so a typed number like 12345 comes out at once. The search runs in 1024 pieces and
  saves where it can resume: the first piece not yet fully checked, so a slow thread never leaves a gap. The mod runs
  it in the background with a quarter of the CPU threads until the seed is known.
- **Checking a seed found from the hashed seed alone.** A server may send a made-up hashed seed; if that value is the
  hash of some typed seed, the typed search finds the wrong seed. So the seed is checked against the notes: whether
  it puts each noted structure's start in its chunk, and whether it gives the noted bedrock. It is confirmed when a
  wrong seed would fit the notes at least as well with a chance of at most 1 in 1,000:
  - Structures: a wrong seed has a start in a given chunk about 1 time in 150 to 900, depending on the kind (end
    cities 1 in 23; mineshafts, buried treasure and nether fossils don't count). What counts is the chance of
    fitting at least as many of all the notes as this seed does, so with many notes a few chance fits confirm
    nothing. Notes whose random numbers are related (the same region of sets with nearly the same salt: the four
    temples, ocean monuments and end cities, or one set twice) count once. Measured: random typed seeds checked
    against 30, 60 and 100 notes of one world were confirmed 5, 60 and 26 times in 100,000.
  - Overworld bedrock that fits completely: the measured false-fit rate with a safety factor, so that bedrock alone
    confirms a seed only from about 300 informative blocks (a chunk of heights has about 600, and the mod notes about
    1,000 per chunk). The measured rate is an average: for small notes single worlds were measured up to about 10
    times higher, and next to x = 0 or z = 0, where the game's random numbers for neighbouring blocks are alike, up to
    about 90 times (a single layer of 16 x 16 blocks around the middle of the world). Less bedrock still helps
    together with structures.
  Any overworld bedrock that doesn't fit keeps the seed from being shown as the world's seed, and the message says
  why: a few wrong blocks out of many were probably noted wrong (a wrong seed gets about a third of them wrong);
  bedrock that doesn't fit while the structures confirm the seed was probably noted in the wrong place (or the
  server changes the bedrock it sends); otherwise the server probably sends a made-up hashed seed. Nether bedrock and structures that don't
  fit only count against it when nothing confirms it: nether chunks made by Minecraft 1.17 or older have bedrock that
  doesn't follow the seed, a server may place structures differently, and older chunks or commands leave structures
  that don't follow it either. Bedrock notes that break the game's rules are left out and reported.
- **Bedrock from loaded chunks.** The mod reads the random bedrock layers of up to four chunks per dimension and
  skips anything that isn't natural stone, so player-made holes and structures count as unknown, not as "no
  bedrock". The in-game test checks thousands of blocks read this way against the engine's prediction.
- **Structures recognized from blocks** (villages, pillager outposts, trial chambers, ancient cities, trail ruins,
  abandoned camps). These are built from pieces, and the game puts one point of the first piece exactly on the
  north-west corner of the start chunk, turned by a random multiple of 90 degrees around that point. The mod reads
  the blueprints of all first pieces from the running game (220 of them), keeps the blocks that are sure to be
  there, and searches loaded chunks for a few rare blocks of each (a bell for villages, reinforced deepslate for
  ancient cities). A placement is only accepted when its reference point lands on a chunk corner, the chunk is one
  the structure can start in at all, and at least 70% of the blueprint and 70% of its built blocks match. Matches
  are grouped per placement region; a region whose matches disagree reports nothing.
- **Rigid structures recognized from blocks** (desert pyramids, jungle temples, swamp huts, igloos, shipwrecks,
  ocean ruins, ruined portals, also in the nether). Each is one fixed piece: the game puts a code-built piece's box
  corner, or a template's origin, exactly on the start chunk's north-west corner, and turns templates about a fixed
  pivot (portals are also mirrored). How each one is placed (templates, turns, mirrors, pivots) comes from the
  oracle, which runs the game's own structure generation in thousands of chunks; the mod builds every variant in
  every pose at runtime (440 layouts) from the templates in the game jar and, for pyramids, temples and huts, by
  running the game's own building code in a made-up world in memory. Blocks typical of each structure vote for the
  placements they could belong to, but only those with their origin on a chunk corner; the placements with at least
  3 votes are compared block by block. A fit is accepted when at least 12 telling blocks match (fewer for tiny
  layouts) and at least half of the compared ones (55% for ocean ruins), also when every kind of block counts the
  same; when the spots the structure leaves empty are not filled with its own material (for ruined portals not a
  single one: a complete frame or a floor is a player's portal); when an ocean ruin lies in open water; when the
  biome is one the structure starts in; and when a small ocean ruin doesn't fit better off the chunk grid (the
  cluster ruins around a big one). Fits are then taken strongest first: two starts of one set closer than the set's
  separation can't both exist, a fit that mostly explains blocks already explained is dropped, and when another
  start chunk fits about as well nothing is noted. A chunk where none of its own fits passes is done once its
  neighbours are read; otherwise the mod judges it with the fits of the chunks within 2 as rivals, once everything
  they and the cluster test read is loaded (4 chunks around it in the overworld, 3 in the nether), so a wrong fit
  next to a real structure always meets the real one whole.

**What we learned while testing:**

- *"The chunk the structure is in" is usually wrong.* Because of the rotation, the first piece only lies inside the
  start chunk about a quarter of the time. The corner rule was checked on 13,476 starts made by the game's code.
- *Ground fools a blueprint-only check.* A tent blueprint that is mostly floor matched plain savanna ground well
  enough to pass a 60% threshold. Scoring the built blocks separately fixed it: wrong placements reached at most
  46% of their built blocks, real ones at least 73%.
- *Measured with the real code.* On 175 areas generated by the game's own code (with trees, ores and other
  decoration) the mod's recognizer found 204 starts (72 camps, 41 villages, 29 trial chambers, 24 outposts,
  23 trail ruins, 15 ancient cities) and never named a wrong chunk. It missed 5 villages: two zombie plains fountains
  (left out on purpose, since only the bell survives the zombie processor), a zombie taiga centre covered by a sand
  patch, and two savanna centres over which the game laid its own terrain-following farm streets. Recognition took
  at most 0.11 s for 1,024 chunks. The in-game test then recognizes one of each type in a real client and checks
  every recognized start against the game's records.
- *Player builds.* A review found that a plain roof of white wool stairs on grass matched camp tents (their built
  blocks are mostly those stairs). Now 70% of the built blocks other than the most common one must match too, and
  the most common block is only used to search when nothing else can be.
- *Real structures that don't follow the seed:* chunks made by older versions (before 1.18 villages used a 32-chunk
  grid; up to 1.12 jungle temples, swamp huts and igloos shared the desert pyramids' grid; from 1.16 to 1.18.1
  nether ruined portals used a grid of 25 x 25 chunks), `/place structure` (it puts the start piece on the corner of
  the chunk you name), `/place template` (a structure's own template, put anywhere) and copied areas. The mod reads
  the answers to both `/place` commands and ignores those spots (a template's answer doesn't say how it was turned,
  so every turn is left out), also while collecting is off (then only for the session) and after "Remove all notes";
  such a spot doesn't count against the real start of its region either. Ruined portals in the nether are only
  noted when the player says the nether is from 1.18.2 or newer: an old nether hub would otherwise fill the notes
  with portals on the old grid. For the rest, "no seed fits" triggers a search that leaves out one structure at a
  time and only reports a result that the hashed seed or bedrock proves.
- *Every build checks real structures.* The in-game test generates the chunks around the nearest starts of each
  recognized type with the game itself (up to 4 per type and at least one, looking farther for types of rare biomes;
  ruined portals also in the nether) and requires the recognizers to find them with no wrong or contradicting
  start; it visits one start of each type, and a ruined portal in the nether, as a player would, and checks that a
  camp and a ruined portal placed with `/place` are not noted.
- *Underground structures need real blocks.* Servers with anti-xray hide them; the mod doesn't try to get around
  that, and they are simply not found there.
- *Rigid structures: research first, then a port checked against it.* The recognizer was worked out on worlds made
  by the game's own code (tuned on some seeds, then measured on fresh ones: 56 of each type, 0 wrong start chunks,
  0 false alarms in 238 areas and on 3,150 player-style portal frames). A separate check found two gaps, both
  closed: a player's portal on a stone-brick floor passed as a ruined portal about 1 time in 12, and a desert village
  by a warm ocean once looked like an ocean ruin on dry land. The look-alikes that needed rules: a mansion's
  cobblestone foundation (95% of a jungle temple's cobblestone), underground stone-brick structures that look like
  small cold ruins, the cluster ruins around a big ocean ruin, and an intact portal fitting another pose one chunk
  away at 0.70 (only the stronger true fit next to it rules that out). The mod's code was then compared with the
  research version on worlds made by the game's code: the same 440 layouts, and the same answers in every area,
  also from the mod's chunk-by-chunk tracker reading chunks in random order: 160 structures of the seven kinds in
  the overworld and 28 nether portals, all found; no wrong start chunk; no false alarm in 30 random areas or on
  2,400 player-built portal frames (many on stone-brick, slab or blackstone floors).
- *Rigid recognition never holds up the game for long.* It works in small steps (at most 512 blocks read per step,
  1 ms per tick plus whatever the chunk scan leaves of its 2 ms). A chunk where nothing fits is done as soon as its
  neighbours are read; one with a fit waits until everything its rivals and the ocean-ruin cluster test read is
  loaded (4 chunks around it in the overworld), so the answer never depends on what happens to be loaded. Checked
  against the first version on worlds made by the game's code: the same answer for every one of about 1.3 million
  placements compared (votes, fit, cluster test) and in every area, also with anti-xray-style fake blocks. In busy
  areas (villages, temples, ocean ruins) judging takes about 0.4 ms of work per chunk; on an anti-xray server that
  sends 1 in 21 hidden blocks as obsidian and 1 in 21 as mossy cobblestone, about 7 ms, which only means it catches
  up more slowly there.
- *What rigid recognition misses:* tiny warm ocean ruins (only 9 telling blocks), portals mostly covered by
  netherrack or, in the nether, by lava, ocean ruins buried in coastal rock, and anything players have repaired or
  taken apart (a ruined portal someone completed or lit no longer counts as a ruin).

## Things that can't work

- **No data, no seed**: the finder needs observations.
- **Modified world generation**: custom generators, worldgen datapacks or mods, worlds pre-generated with another
  seed, or servers set up to hide their seed. What you see then doesn't match the real seed.
- **Bedrock that isn't from world generation**: bedrock placed by players, holes left by mineshafts or strongholds,
  and superflat worlds (their bedrock is one flat layer).
- **Text seeds**: the finder gets the number, not the original text.
- **Old chunks**: chunks generated by an older Minecraft version keep that version's generation, so the finder must
  know which version made the chunks you looked at. This includes structures the mod recognizes by itself: a
  village from an older version can look the same but follow older placement rules, and then no seed fits. Remove
  structures in old parts of an upgraded world on the mod's screen. The client can't tell old chunks from new ones,
  so the mod can't do this by itself.

## Keeping up with new Minecraft versions

Mojang releases a new version roughly every three months. For each one:

1. Run `tools/oracle/run-oracle.sh <version>`. It writes the version data and test fixtures.
2. Add the version to `MinecraftVersion`.
3. Run the tests. If world generation code changed (not just data), update the engine until they pass.
