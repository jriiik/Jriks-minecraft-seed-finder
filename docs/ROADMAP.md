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
| 5 | Fabric client mod: notes structures and bedrock while you explore, shows progress, turned on per server | ✅ first version; recognizing the remaining structure types ⏳ |
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

### Phase 5: the mod

- **Opt-in per world.** Nothing is read until the player switches collecting on for that world or server.
- **The hashed seed.** Every server sends players a SHA-256 hash of the world seed (for biome blending). Once the
  structure seed is known, checking its 65,536 full seeds against that hash settles the seed at once. If no
  candidate matches (a server may send a different value), the mod says so and uses bedrock instead.
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
- *Underground structures need real blocks.* Servers with anti-xray hide them; the mod doesn't try to get around
  that, and they are simply not found there.

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
  structures in old parts of an upgraded world on the mod's screen.

## Keeping up with new Minecraft versions

Mojang releases a new version roughly every three months. For each one:

1. Run `tools/oracle/run-oracle.sh <version>`. It writes the version data and test fixtures.
2. Add the version to `MinecraftVersion`.
3. Run the tests. If world generation code changed (not just data), update the engine until they pass.
