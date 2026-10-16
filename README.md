# Jrik's Minecraft Seed Finder

A tool that works out a Minecraft **Java Edition** world's seed from things you can see in the world,
such as where villages, temples and other structures are. Built from scratch, for the newest Minecraft version
(**26.3**) and for **1.21.11**.

> **Work in progress.** From about six structure positions the finder works out the world's *structure seed* (the
> part of the seed that decides where structures go), usually in under a second, and names the likely full seed
> (the nether's bedrock floor and roof of one chunk work instead of structures).
> With the bedrock floor of one chunk, or the biomes around you, added, it finds the full seed for certain. A Fabric mod does all of this while
> you play, and recognizes villages, outposts, trial chambers, ancient cities, trail ruins, camps (26.3 only), desert
> pyramids, jungle temples, swamp huts, igloos, shipwrecks, ocean ruins and ruined portals by itself. See the
> [roadmap](docs/ROADMAP.md).

*Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.*

## What it can do today

| Feature | Status |
|---|---|
| Structure placement for Minecraft 26.3 and 1.21.11, checked against the real game | ✅ done |
| Minecraft 1.21.11 worlds next to 26.3 (the command-line app and a mod file for each version) | ✅ done |
| `seedfinder locate`: list structure spots for a seed | ✅ done |
| `seedfinder find`: work out the structure seed (48 bits) from structure positions | ✅ done |
| Work out the structure seed from the nether's bedrock alone (one chunk's floor and roof; in `find`, or by the mod) | ✅ done |
| Name the likely full seed (64 bits) of worlds whose seed was picked by Minecraft or typed as text | ✅ done |
| Find the full seed for certain from the bedrock floor (`seedfinder full-seed`, or bedrock notes in `find`) | ✅ done |
| Find the full seed from biomes (biome notes in `find` or `full-seed`; the mod reads them by itself) | ✅ done |
| Find a seed that was typed (as text or a short number) from the "hashed seed" alone, in minutes (`seedfinder typed-seed`) | ✅ done |
| `seedfinder bedrock` and `seedfinder biomes`: show the bedrock floor or the biomes of a seed you know | ✅ done |
| Fabric client mod: notes structures and bedrock while you play and finds the seed by itself | ✅ first version |
| The mod recognizes villages, pillager outposts, trial chambers, ancient cities, trail ruins and camps (26.3 only) by itself | ✅ done |
| The mod recognizes desert pyramids, jungle temples, swamp huts, igloos, shipwrecks, ocean ruins and ruined portals by itself | ✅ done |

## Minecraft versions

The finder works for worlds of Minecraft Java Edition **26.3** and **1.21.11**. The structures the finder uses,
bedrock and the seeds Minecraft picks work the same in both (strongholds don't: they follow the biomes, and the finder
doesn't use them); biomes are worked out exactly the way each version does it. For a player the
differences are small: 1.21.11 has no abandoned camps, and it lacks the two biomes 26.3 added (dappled forest and
sulfur caves).

- **The command-line app** reads notes as 26.3 unless told otherwise: add `-m 1.21.11` (or `--minecraft 1.21.11`)
  to a command, or put a line `minecraft 1.21.11` into the notes file. The mod writes that line itself, as the first
  line of its notes. If the option and the line disagree, the app stops and says so instead of guessing. The version
  matters for biome notes: when notes that name no version have biomes that don't fit, the app reminds you that it
  read them as 26.3. `seedfinder versions` lists the versions it knows.
- **The mod** comes as one file per Minecraft version: `jriks-seed-finder-mod-<version>-mc26.3.jar` for 26.3 and
  `jriks-seed-finder-mod-<version>-mc1.21.11.jar` for 1.21.11 (see [The Minecraft mod](#the-minecraft-mod)).

## Trying the command-line app

1. Install **Java 21 or newer** (for example [Temurin](https://adoptium.net/)).
2. Get `seedfinder.jar`: download it from the
   [latest release](https://github.com/jriiik/Jriks-minecraft-seed-finder/releases/latest) (under "Assets"), or
   build it yourself (see below). Changes made since that release are in the latest successful
   [build](https://github.com/jriiik/Jriks-minecraft-seed-finder/actions) (the "seedfinder" artifact, you need to
   be signed in to GitHub).
3. Open a terminal in the folder with the jar and run, for example:

```
java -jar seedfinder.jar find desert_pyramid:272,80 swamp_hut:256,-144 village:336,80 shipwreck:-128,32 ruined_portal:16,0 trial_chamber:0,224
java -jar seedfinder.jar find --file my-structures.txt
java -jar seedfinder.jar find --minecraft 1.21.11 --file my-old-world.txt
java -jar seedfinder.jar full-seed 12345 --file my-bedrock.txt
java -jar seedfinder.jar bedrock --seed 12345
java -jar seedfinder.jar biomes --seed 12345
java -jar seedfinder.jar structure-seed 12345
java -jar seedfinder.jar locate --seed 12345
java -jar seedfinder.jar locate --seed "my world" --at 500,-1200 --radius 2000 villages pillager_outposts
java -jar seedfinder.jar structures
java -jar seedfinder.jar versions
java -jar seedfinder.jar --help
```

The first example uses structure positions of seed 12345 and finds exactly that.

## Finding a seed

1. **Collect about 6 structures of mixed types.** At least one of them has to be a desert pyramid, igloo, jungle
   temple, swamp hut, pillager outpost, shipwreck, ocean ruin, trail ruin, trial chamber or village, because only
   those let the search start. A ruined portal, abandoned camp (26.3 only), trial chamber or ancient city then rules
   out look-alike seeds. Several structures of the same kind help much less than a mix.
2. **Note where each one starts.** The finder needs the chunk where the structure starts, written as block
   coordinates of any block in that chunk. `/locate` prints exactly that spot. Without `/locate`: desert pyramids,
   jungle temples and swamp huts start in the chunk of their north-west corner (lowest x and z); a desert pyramid's
   blue terracotta in the middle of the main floor is always 10 blocks east and 10 south of its start chunk's
   corner, and the igloo's white carpet next to the middle light-gray carpet 3 east and 5 south (whichever way the
   igloo faces); other structures are built outwards from a corner of their start chunk, close to their center
   piece.
3. **Run the finder**, either with the structures on the command line (`igloo:48,208`) or from a text file with
   one structure per line. Lines copied from `/locate` work as they are:

   ```
   # my-structures.txt
   desert_pyramid 272 80
   swamp_hut 256 -144
   The nearest minecraft:village_plains is at [336, ~, 80] (345 blocks away)
   ```
4. **Read the answer.** The *structure seed* is the last 48 bits of the world seed. If several seeds fit, the
   finder lists them all and names the structure types that would tell them apart. Then it goes on to the full
   seed (next section).

## Finding the full seed

65,536 world seeds share each structure seed. Two things narrow them down.

**How the seed was made.** When nobody types a seed, Minecraft picks one with Java's random number generator, and
only one (sometimes two) of the 65,536 can come out of it. A seed typed as text, like "My World", also leaves only one.
`find` names these right away:

```
If nobody typed a seed when the world was created (Minecraft then picks one), it is 5794443870565576761.
If the seed was typed as text (like "My World") or as a short number, it is 12345.
```

That is a good guess for most worlds and servers, but only a guess: if the seed was typed, the "random" answer is
a different seed that happens to share the structure seed.

**The hashed seed.** Minecraft sends every player a "hashed seed", a fingerprint of the world seed (the mod notes it
by itself, as a `hashed-seed` line in its notes file). With it, the full seed is certain as soon as the structure
seed is known. And if the seed was typed (as text or as a number from -2147483648 to 2147483647), the hashed seed
alone gives it, without any structures: `seedfinder typed-seed --file <notes file>` (or `seedfinder typed-seed
<hashed seed>`) checks all 4,294,967,296 seeds that can be typed. That takes about a minute on a recent computer
and up to 20 minutes on an older one with few CPU threads; a small typed number like 12345 comes out at once. For
text seeds you get the number Minecraft made of the text, not the text itself.

Some servers send a made-up hashed seed to protect their seed. If that made-up value happens to be the fingerprint of
a typed seed, the search finds that seed, but it isn't the world's. So the seed found is checked against the
structures and bedrock in the notes: when they fit (and a wrong seed would fit them only by a chance below 1 in
1,000), it is certain; when they don't, it is not presented as the world seed. A few structures that don't fit, or
nether bedrock from chunks made by Minecraft 1.17 or older, are listed but don't stop a seed that the rest confirms;
overworld bedrock always has to fit. With nothing noted yet, the seed is shown as "not confirmed yet". (In a single-player world the game makes the hashed
seed itself, so there the seed found is always the world's.)

**Bedrock, to be sure.** The bottom of the overworld (y = -64 to -60) is a random-looking bedrock pattern that
depends on the whole seed. With bedrock notes, the finder checks every one of the 65,536 seeds.

1. Go to the bottom of the overworld, outside any mineshaft or stronghold, and pick a chunk (F3+G shows chunk
   borders).
2. Clear the whole chunk down to the bedrock: mine every block that isn't bedrock, until bedrock stops you in every
   column. That is about 900 blocks of deepslate.
3. For each of the 16 x 16 columns, note how high the **highest bedrock block** is, as the last digit of its height:
   `0` for y=-60, `1` for -61, `2` for -62, `3` for -63, `4` for -64. F3 shows the height of the block you look at
   ("Targeted Block"). Write one row of 16 digits per z, from north to south, each row from west to east, below a line
   with the x and z of the north-west column (the corner with the lowest x and z). Use `?` for columns you skipped.
   Also write `?` for a column where you met lava in the bedrock layers (y=-63 to -60), in any version: the game
   skips lava when it places bedrock, so now and then lava sits where bedrock would be. In a 1.21.11 world, do the
   same for a column where you mined tuff, a raw iron block or deepslate iron ore at y=-60 (part of an iron ore
   vein): 1.21.11 never rolls for bedrock where such a block lies. Both cases are very rare (each about once in a few
   thousand chunks when measured), and the mod leaves such columns out by itself.

   ```
   # my-bedrock.txt
   bedrock-heights 0 0
   1123131433440202
   2040332322041110
   ...                (16 rows in total)
   ```
4. Run `seedfinder full-seed <structure seed> --file my-bedrock.txt`, or put the bedrock lines in the same file as your
   structures and run `find`.

What the answers mean:

- **"Found the full seed"**: exactly one world seed fits all of your bedrock, and there is enough of it that a wrong
  structure seed would have a fitting seed only by a rare chance. With less (or with many structure seeds to choose
  from) it says **"One world seed fits all of your bedrock"** instead, and how far to trust it.
- **"N world seeds fit"**: the area was too small. The finder names a few columns where they differ; add those.
- **"No world seed fits all of your bedrock"**: the finder lists the blocks that don't fit. Usually a height was
  noted wrong; check those spots, fix them and run again.

A whole chunk is nearly always enough; an 8 x 8 area usually is too, but then a wrong structure seed could also
match by chance, and the finder says so. Bedrock can also be noted block by block (`bedrock x y z`,
`not-bedrock x y z`) or one layer at a time (`bedrock-layer x y z` followed by rows of `B` and `.`); see
`seedfinder full-seed --help`. Nether bedrock only depends on the structure seed, so it can't give the full seed,
but it can replace the structures (next section).

**Nether bedrock instead of structures.** The nether's bedrock floor (y 1 to 4) and roof (y 123 to 126) depend only on
the structure seed, and one chunk of each gives it, in about a second, without any structures: note them as
`bedrock-layer nether x y z` lines (rows of `B` for bedrock and `.` for netherrack, see `seedfinder full-seed --help`;
`seedfinder bedrock --seed <seed> --dimension nether --layers` shows what they look like) and run `find` on the file.
Structures in the same file are used too: the answer must fit both. When they disagree (a structure noted in the
wrong place or from older chunks, or nether chunks of another world), the seeds of both sides are checked against the
hashed seed, the overworld bedrock and the biomes, and a structure that doesn't fit the seed found is named. A few
nether blocks, too few to search on their own, still rule out structure seeds that don't fit them (when so many are
noted that a wrong seed would fit them all only by a rare chance). When no seed fits all the structures, `find` also
tries again without each one in turn and names the one that doesn't fit, as the mod does.
Both the floor and the roof are needed (each alone leaves several possible seeds); bedrock at y 4 or y 123, and
gaps at y 1 or y 126, tell the most. About one structure seed in four has a "twin" with exactly the same nether
bedrock, so the answer may be two structure seeds; the hashed seed, structures, overworld bedrock or biomes then tell
them apart. Nether chunks made by Minecraft 1.17 or older have other bedrock: the finder leaves such a chunk out and
says which one.

**Biomes.** The overworld's biomes also depend on the whole seed, and the finder repeats exactly how Minecraft picks
them, in 26.3 and in 1.21.11, which does the arithmetic a little differently (checked against each game on millions
of spots and on whole generated chunks). Biomes are the easiest notes to take: stand somewhere, press F3, and write
down the biome it shows with your position:

```
biome dark_forest 120 64 -35
biome river 300 62 -10
```

Note places where different biomes meet; a place that is all ocean tells little. The finder checks all 65,536 world
seeds against the notes and only presents a seed when it is the only one that fits **and** a wrong seed would fit the
notes this well with a chance below 1 in 1,000. How often a wrong seed fits is measured on the other 65,535
candidates, place by place:

- The biomes of **one area** (what you see around you, a few hundred blocks across) prove a seed that Minecraft
  picked by itself or that came from text, because only one or two of the 65,536 seeds could be such a seed.
- **Any other seed** needs biomes from a **second area whose notes are at least 4,096 blocks from the first one's**
  (16,384 in a "large biomes" world): biomes close together are alike in every world, so near places don't count
  twice. Which places count is decided from the notes alone: as many as can all be that far apart, places with more
  biomes first.
- Seeds that Minecraft picks itself or that text gives are only named as "likely" when the biome notes don't rule
  them out. If the overworld bedrock fits another seed perfectly while the biomes point elsewhere, neither is
  presented as found.
- Large-biome and amplified worlds are recognized (they are tried after normal ones); a line `preset large_biomes`
  in the notes says it outright.

Chunks made by an older Minecraft version (also land made by 1.21.11 in a world now played in 26.3), or changed by
data packs, mods or `/fillbiome`, have other biomes; then no seed fits, and the finder says so rather than guessing.
The mod notes the exact biome of every 4 x 4 x 4 block cell the game stored (F3 shows a blend of the cells around a
block), as `biome-grid` lines in its notes file.
`seedfinder biomes --seed <seed>` prints the biomes the finder expects for a seed you know, in that format.

**Try it on your own world:** create a world, use `/locate structure` for about six structures of different
types, put the results into a file, run `find`, and compare the answer with
`seedfinder structure-seed <the number /seed shows>`. `seedfinder bedrock --seed <the number /seed shows>` prints
the bedrock floor the finder expects, in the same format as the notes, to compare with what you dig out.

`locate` does the opposite of `find`: it lists the spots where each structure tries to generate for a seed you
know, nearest first, using the same positions that `/locate` reports. A structure only appears at its spot if the
biome there allows it. The finder does not check biomes yet, so some spots will turn out empty in the game.

## The Minecraft mod

The mod does the same as the command-line app, inside the game: it notes what you come across and searches by
itself. It works with Minecraft **26.3** and **1.21.11**, with **Fabric Loader** (0.19.5 or newer) and **Fabric API**
for that Minecraft version. Each Minecraft version has its own mod file; pick the one whose name ends in your
version:

| Your Minecraft | Mod file |
|---|---|
| 26.3 | `jriks-seed-finder-mod-<version>-mc26.3.jar` |
| 1.21.11 | `jriks-seed-finder-mod-<version>-mc1.21.11.jar` |

1. Install Fabric for your Minecraft version (see [fabricmc.net](https://fabricmc.net/use/)), and put Fabric API and
   the mod file for that version (from the
   [latest release](https://github.com/jriiik/Jriks-minecraft-seed-finder/releases/latest), under "Assets") into
   your `mods` folder.
2. In Options → Controls → Key Binds, give **Open Seed Finder** a key (it has none at first, so it can't clash with
   anything).
3. In a world where you may look for the seed, press that key and switch **Collecting here** on. In every other world
   the mod notes nothing.
4. Play. The mod then:
   - reads the bedrock floor of chunks around you (overworld and nether). In the nether, one chunk's floor and roof
     give the structure seed by themselves, without any structures. Nether chunks made by Minecraft 1.17 or older
     (their bedrock doesn't follow the seed) are left out and make room for others; once several are, chunks within
     8 chunks of them are skipped, so in an upgraded world it reads the newer land farther away;
   - reads the biomes the game stored around you (about 100 chunks, at the surface and three cave depths), and again
     when you are about 4,500 blocks from every area noted (up to four areas, plus one far enough away for
     large-biome worlds). One area proves a seed Minecraft picked itself or a text seed; a second area proves any
     seed. In a world whose biomes come from a data pack or mod, biomes are not used;
   - recognizes **villages, pillager outposts, trial chambers, ancient cities, trail ruins, camps (26.3 only), desert
     pyramids, jungle temples, swamp huts, igloos, shipwrecks, ocean ruins and ruined portals** you come near, from
     their blocks, and notes the chunk where each one starts (see below). Ruined portals in the nether are only noted
     after you switch **Ruined portals in the nether** on, which you should only do if the world's nether was made by
     Minecraft 1.18.2 or newer: in nether chunks made by 1.16 to 1.18.1 they sit on another grid;
   - notes the answer when you use `/locate structure ...` (if you are allowed to use it);
   - notes structures you mark: stand in the chunk where a structure starts and press **Add a structure here**;
   - uses the "hashed seed" that Minecraft sends to every player, which settles the full seed as soon as the
     structure seed is known;
   - checks in the background whether the seed was typed (as text or a number from -2147483648 to 2147483647): then
     the hashed seed alone gives it, without any structures. That takes from a few minutes to half an hour or more
     of background work on older computers (a moment for a small typed number like 12345), using a quarter of your CPU
     threads; it continues where it was when you come back. The seed found is checked against the bedrock
     and structures noted (see "The hashed seed" above).
5. The status shows in the top-left corner and on the mod's screen; **Copy seed** copies the result.

**How structures are recognized.** The mod compares the blocks around you with the game's own blueprints of each
structure's first piece (it reads them from the Minecraft you are running; nothing of Minecraft is copied into the
mod). The game puts that piece exactly on the corner of the chunk where the structure starts, so a match that
doesn't sit on a chunk corner is thrown away. A structure is only noted when at least 70% of its blueprint is
there, 70% of its built blocks (not just ground), and 70% of its built blocks other than the most common one (so a
roof of wool stairs on grass doesn't count as a camp tent). Tested against worlds made by the game's own code (204
structures, with trees, ores and other decoration): it never named a wrong chunk, and every build of the project
checks more in a real game (24 in 26.3, 20 in 1.21.11, which has no camps). Some structures are not recognized,
which only means they don't count: a village whose centre was overbuilt by its own farm streets or a sand patch, a
rare zombie village centre, or anything players have taken apart. Underground structures (trial chambers, ancient
cities, trail ruins) can only be seen where the server sends the real underground blocks; servers that hide them
(anti-xray) simply mean they are not found.

Desert pyramids, jungle temples, swamp huts, igloos, shipwrecks, ocean ruins and ruined portals are each one fixed
piece, which the game puts on the corner of the start chunk and turns (ruined portals are also mirrored). The mod
builds every variant in every turn from the game itself (the templates from the Minecraft you are running; pyramids,
jungle temples and swamp huts by letting the game's own building code draw them in memory) and lets blocks typical
of each structure point at the places where it could stand with its corner on a chunk corner. The likeliest places
are compared block by block. A structure is only noted when enough of its own blocks are there (also when every
kind of block counts the same), its rooms aren't filled with its own material, an ocean ruin lies in open water,
the biome is one the structure can start in, and no other start chunk fits about as well. Tested on worlds made by
the game's own code: on worlds not used to tune it, it found 388 of 392 structures (it missed three tiny warm ocean
ruins and one ruined portal) and never named a wrong chunk. A separate check found two kinds of false alarm (a
player's nether portal standing on a stone-brick floor, and a desert village by a warm ocean that looked like an
ocean ruin); both were fixed, and after that the tests gave no false alarm (random areas, villages, and thousands of
portal frames built the way players build them). The mod's own code then gave exactly the same answers as that
version on 188 more structures (overworld and nether), and every build checks real structures again in a real game.
It misses some: tiny warm ocean ruins (only 9 telling blocks), ruined portals mostly covered by netherrack or (in the
nether) lava, ocean ruins buried in coastal rock, and anything players have repaired or taken apart (a ruined portal
someone completed or lit no longer counts as a ruin).

Some structures are real but don't follow the seed: in an upgraded world, villages and outposts in chunks made by
Minecraft 1.17 or older, jungle temples, swamp huts and igloos in chunks made by 1.12 or older, and ruined portals
in nether chunks made by 1.16 to 1.18.1; structures placed with `/place` or copied from elsewhere; and exact copies
players built on a chunk corner. The mod ignores structures placed with `/place structure` or `/place template`
when it sees the game's answer in your chat (also while collecting is off, for as long as you play), but not when
the answers are hidden (the `sendCommandFeedback` rule is off, or chat is hidden) or the command came from a
command block. When the others make "no seed fits", the mod tries again leaving out one structure at a time; if
that proves a seed (with the hashed seed or bedrock), it shows the seed and names the structure that doesn't fit.
Structures you remove on the mod's screen are not noted again, also after **Remove all notes**.

Each world has its own notes, told apart by the server address (or save name) and the hashed seed, so a server
network's lobby and game world, or a world that was reset, never mix.

The notes are saved in `config/jriksseedfinder/` inside your Minecraft folder: a `.txt` file per world that the
command-line app reads too (`seedfinder find --file <that file>`). Its first line, such as `minecraft 1.21.11`, tells
the app which Minecraft version the world is from; **Copy notes** on the mod's screen copies the same text.

**What the mod does not do:** it adds no chat commands (so nothing can clash with a server's own commands or plugins,
such as `/locate`), sends nothing to the server, and doesn't change the game's code. It only looks at what the game
has already received, and only in worlds where you switched it on. Every build checks the mod file of each Minecraft
version automatically. A test fails if the mod's code refers to the game's connection, packets or command system,
to Fabric's networking, command or message-sending features, to changing the game's code, or to Java's networking,
reflection, other programs or native code. And because some ordinary game functions send something by themselves
(the chat box sends commands, closing a container tells the server), every function of the game, of Fabric and of
the libraries the game comes with that the mod uses must be on a list that was checked against the game's code;
anything new fails the build until it has been checked too.

## Ground rules

Seed finding is meant for your own worlds, friends' servers with their permission, servers whose rules allow it,
and server owners checking whether their own seed can be found. Many servers don't allow it, because the seed
reveals where ores and structures are. This project will not include anything to hide from anti-cheat or to get
around a server's seed protection.

The command-line app never connects to a server. The mod adds no chat commands and sends nothing to the server (see
above), and it collects nothing until you switch it on for a world.

## How the project is organized

| Folder | What it is |
|---|---|
| `engine/` | The seed-finding logic and the per-version world generation data. Plain Java, no Minecraft code. |
| `cli/` | The `seedfinder` command-line app. |
| `mod/` | The Fabric client mod. It contains the engine and adds the in-game part. Its code (`mod/src`) is shared; `mod/versions/<version>` holds the few lines that differ for each Minecraft version and builds that version's mod file. |
| `tools/oracle/` | Generates the version data and the test answers from the official Minecraft server, which it downloads when run (never committed). Versions whose game code differs have their own copy in `tools/oracle/<version>/`. |
| `docs/` | The [roadmap](docs/ROADMAP.md). |

**Testing against the real game.** `tools/oracle` runs Minecraft's own world generation code for a list of
test seeds and records the results. The engine's tests then require our code to give exactly the same answers.
For each version (26.3 and 1.21.11) that covers every structure type, 10 seeds, negative coordinates and positions
far from spawn, the random number generators used for blocks, the seeds Minecraft picks by itself, the bedrock of
real chunks generated by the game (overworld and nether, 184,320 blocks), and the overworld's biomes: the noises and
climate values bit for bit, the biome search including its ties, every cell of whole generated chunks, and the F3
zoom, for the normal, large-biome and amplified presets. The in-game test, run in both versions, also compares the biomes the real
client receives with the finder's (6,400 cells) and with F3. The finder's tests feed it structure positions recorded
from the game and require it to find the right seed.

## Building from source

Needs Java 25 (the mod's tools require it; the command-line app it builds still runs on Java 21). On Windows use
`gradlew.bat` instead of `./gradlew`.

```
./gradlew build                  # compile and run all tests, including each mod file's "sends nothing" check
./gradlew :cli:fatJar            # the runnable jar: cli/build/libs/seedfinder.jar
./gradlew :mod-1.21.11:build     # only one Minecraft version's mod file (and its tests)
xvfb-run -a ./gradlew :mod-26.3:runProductionClientGameTest     # starts real Minecraft 26.3 and tests the mod in it
xvfb-run -a ./gradlew :mod-1.21.11:runProductionClientGameTest  # the same in Minecraft 1.21.11 (both Linux only)
```

The mod files end up in `mod/versions/26.3/build/libs/jriks-seed-finder-mod-<version>-mc26.3.jar` and
`mod/versions/1.21.11/build/libs/jriks-seed-finder-mod-<version>-mc1.21.11.jar`.

When a release is published on GitHub, the "Release files" workflow builds the files from the release's tag,
labelled with its version (a tag `v1.01` gives 1.01), runs all tests and, for each Minecraft version, the in-game
test with that version's mod file, and only then attaches `seedfinder.jar`,
`jriks-seed-finder-mod-<version>-mc26.3.jar` and `jriks-seed-finder-mod-<version>-mc1.21.11.jar` to the release. If a
test fails, nothing is attached; the failed run is listed under Actions.

To add or refresh a Minecraft version (needs Java 25). Versions whose game code comes with scrambled names, like
1.21.11, first get it with Mojang's own names through Fabric Loom, so they take about 10 minutes instead of 2:

```
tools/oracle/run-oracle.sh 26.3
tools/oracle/run-oracle.sh 1.21.11
```

## License

This project is licensed under the GNU General Public License v3.0, see [LICENSE](LICENSE).
It contains no Minecraft code or assets. The files under `engine/src/main/resources/.../versions/` contain only
facts about world generation (numbers and names) recorded from the game.

The runnable jar also includes [Gson](https://github.com/google/gson) and [picocli](https://picocli.info/),
both under the Apache License 2.0.
