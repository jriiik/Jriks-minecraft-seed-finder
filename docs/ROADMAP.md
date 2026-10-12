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
| 5 | Fabric client mod: notes structures and bedrock while you explore, shows progress, turned on per server | ⏳ next |
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
  notes the finder checks all 65,536 candidates (a few milliseconds) and ranks them by how many blocks they get
  wrong. The engine's bedrock matches 184,320 blocks of real chunks generated by the game.

**What we learned while testing:**

- *Bedrock is less random than it looks.* Each block's number is the first output of an unmixed generator, so the
  whole floor depends on only a few hidden numbers. The information grows like 3·log(n) instead of linearly in n.
  Measured: one chunk of top-bedrock heights (about 630 informative blocks) gives a unique answer practically always;
  an 8 x 8 area about 87% of the time.
- *A wrong structure seed can still fit.* Among the 65,536 seeds of a wrong structure seed, about 65,536·(c/n)^3 fit
  n bedrock blocks perfectly, with c between 2.3 and 3.2: about 1% for a whole chunk, 20% for 8 x 8. The finder uses
  this (with c = 3.5) to warn when the bedrock area is small, and never names a seed that gets blocks wrong: even a
  wrong structure seed has full seeds that miss only a few blocks of a chunk.
- *Nether bedrock* uses the old 48-bit generator, so it only depends on the structure seed. It can tell look-alike
  structure seeds apart, and later (Phase 6) it could find the structure seed without any structures.

## Things that can't work

- **No data, no seed**: the finder needs observations.
- **Modified world generation**: custom generators, worldgen datapacks or mods, worlds pre-generated with another
  seed, or servers set up to hide their seed. What you see then doesn't match the real seed.
- **Bedrock that isn't from world generation**: bedrock placed by players, holes left by mineshafts or strongholds,
  and superflat worlds (their bedrock is one flat layer).
- **Text seeds**: the finder gets the number, not the original text.
- **Old chunks**: chunks generated by an older Minecraft version keep that version's generation, so the finder must
  know which version made the chunks you looked at.

## Keeping up with new Minecraft versions

Mojang releases a new version roughly every three months. For each one:

1. Run `tools/oracle/run-oracle.sh <version>`. It writes the version data and test fixtures.
2. Add the version to `MinecraftVersion`.
3. Run the tests. If world generation code changed (not just data), update the engine until they pass.
