# Jrik's Minecraft Seed Finder

A tool that works out a Minecraft **Java Edition** world's seed from things you can see in the world,
such as where villages, temples and other structures are. Built from scratch, starting with the newest
Minecraft version (**26.3**).

> **Work in progress.** From about six structure positions the finder works out the world's *structure seed* (the
> part of the seed that decides where structures go), usually in under a second, and names the likely full seed.
> With the bedrock floor of one chunk added, it finds the full seed for certain. A Fabric mod does all of this while
> you play, and recognizes villages, outposts, trial chambers, ancient cities, trail ruins and camps by itself. See
> the [roadmap](docs/ROADMAP.md).

*Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.*

## What it can do today

| Feature | Status |
|---|---|
| Structure placement for Minecraft 26.3, checked against the real game | ✅ done |
| `seedfinder locate`: list structure spots for a seed | ✅ done |
| `seedfinder find`: work out the structure seed (48 bits) from structure positions | ✅ done |
| Name the likely full seed (64 bits) of worlds whose seed was picked by Minecraft or typed as text | ✅ done |
| Find the full seed for certain from the bedrock floor (`seedfinder full-seed`, or bedrock notes in `find`) | ✅ done |
| Find a seed that was typed (as text or a short number) from the "hashed seed" alone, in minutes (`seedfinder typed-seed`) | ✅ done |
| `seedfinder bedrock`: show the bedrock floor of a seed you know | ✅ done |
| Fabric client mod: notes structures and bedrock while you play and finds the seed by itself | ✅ first version |
| The mod recognizes villages, pillager outposts, trial chambers, ancient cities, trail ruins and camps by itself | ✅ done |
| The mod recognizes the other structures (temples, igloos, shipwrecks, ...) by itself | ⏳ next |
| Full seed from biomes, structure seed from nether bedrock alone | 🔜 later |

## Trying the command-line app

1. Install **Java 21 or newer** (for example [Temurin](https://adoptium.net/)).
2. Get `seedfinder.jar`: download it from the latest successful
   [build](https://github.com/jriiik/Jriks-minecraft-seed-finder/actions) (the "seedfinder" artifact, you need
   to be signed in to GitHub), or build it yourself (see below).
3. Open a terminal in the folder with the jar and run, for example:

```
java -jar seedfinder.jar find desert_pyramid:272,80 swamp_hut:256,-144 village:336,80 shipwreck:-128,32 ruined_portal:16,0 trial_chamber:0,224
java -jar seedfinder.jar find --file my-structures.txt
java -jar seedfinder.jar full-seed 12345 --file my-bedrock.txt
java -jar seedfinder.jar bedrock --seed 12345
java -jar seedfinder.jar structure-seed 12345
java -jar seedfinder.jar locate --seed 12345
java -jar seedfinder.jar locate --seed "my world" --at 500,-1200 --radius 2000 villages pillager_outposts
java -jar seedfinder.jar structures
java -jar seedfinder.jar --help
```

The first example uses structure positions of seed 12345 and finds exactly that.

## Finding a seed

1. **Collect about 6 structures of mixed types.** At least one of them has to be a desert pyramid, igloo, jungle
   temple, swamp hut, pillager outpost, shipwreck, ocean ruin, trail ruin, trial chamber or village, because only
   those let the search start. A ruined portal, abandoned camp, trial chamber or ancient city then rules out
   look-alike seeds. Several structures of the same kind help much less than a mix.
2. **Note where each one starts.** The finder needs the chunk where the structure starts, written as block
   coordinates of any block in that chunk. `/locate` prints exactly that spot. Without `/locate`: desert pyramids,
   jungle temples and swamp huts start in the chunk of their north-west corner (lowest x and z); other structures
   are built outwards from a corner of their start chunk, close to their center piece.
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
structures and bedrock in the notes: when they fit, it is certain; when they don't, it is not presented as the world
seed. With nothing noted yet, it is shown as "not confirmed yet". (In a single-player world the game makes the hashed
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

- **"Found the full seed"**: exactly one world seed fits all of your bedrock.
- **"N world seeds fit"**: the area was too small. The finder names a few columns where they differ; add those.
- **"No world seed fits all of your bedrock"**: the finder lists the blocks that don't fit. Usually a height was
  noted wrong; check those spots, fix them and run again.

A whole chunk is nearly always enough; an 8 x 8 area usually is too, but then a wrong structure seed could also
match by chance, and the finder says so. Bedrock can also be noted block by block (`bedrock x y z`,
`not-bedrock x y z`) or one layer at a time (`bedrock-layer x y z` followed by rows of `B` and `.`); see
`seedfinder full-seed --help`. Nether bedrock only depends on the structure seed, so it can't give the full seed.

**Try it on your own world:** create a world, use `/locate structure` for about six structures of different
types, put the results into a file, run `find`, and compare the answer with
`seedfinder structure-seed <the number /seed shows>`. `seedfinder bedrock --seed <the number /seed shows>` prints
the bedrock floor the finder expects, in the same format as the notes, to compare with what you dig out.

`locate` does the opposite of `find`: it lists the spots where each structure tries to generate for a seed you
know, nearest first, using the same positions that `/locate` reports. A structure only appears at its spot if the
biome there allows it. The finder does not check biomes yet, so some spots will turn out empty in the game.

## The Minecraft mod

The mod does the same as the command-line app, inside the game: it notes what you come across and searches by
itself. It needs Minecraft **26.3** with **Fabric Loader** (0.19.5 or newer) and **Fabric API**.

1. Install Fabric for Minecraft 26.3 (see [fabricmc.net](https://fabricmc.net/use/)), and put Fabric API and
   `jriks-seed-finder-mod-….jar` (the "jriks-seed-finder-mod" download from the latest successful
   [build](https://github.com/jriiik/Jriks-minecraft-seed-finder/actions)) into your `mods` folder.
2. In Options → Controls → Key Binds, give **Open Seed Finder** a key (it has none at first, so it can't clash with
   anything).
3. In a world where you may look for the seed, press that key and switch **Collecting here** on. In every other world
   the mod notes nothing.
4. Play. The mod then:
   - reads the bedrock floor of chunks around you (overworld and nether);
   - recognizes **villages, pillager outposts, trial chambers, ancient cities, trail ruins and camps** you come
     near, from their blocks, and notes the chunk where each one starts (see below);
   - notes the answer when you use `/locate structure ...` (if you are allowed to use it);
   - notes structures you mark: stand in the chunk where a structure starts and press **Add a structure here**;
   - uses the "hashed seed" that Minecraft sends to every player, which settles the full seed as soon as the
     structure seed is known;
   - checks in the background whether the seed was typed (as text or a number from -2147483648 to 2147483647): then
     the hashed seed alone gives it, without any structures. That takes from a few minutes to about 20 minutes of
     background work, depending on the computer (a moment for a small typed number like 12345), using a quarter of
     your CPU threads; it continues where it was when you come back. The seed found is checked against the bedrock
     and structures noted (see "The hashed seed" above).
5. The status shows in the top-left corner and on the mod's screen; **Copy seed** copies the result.

**How structures are recognized.** The mod compares the blocks around you with the game's own blueprints of each
structure's first piece (it reads them from the Minecraft you are running; nothing of Minecraft is copied into the
mod). The game puts that piece exactly on the corner of the chunk where the structure starts, so a match that
doesn't sit on a chunk corner is thrown away. A structure is only noted when at least 70% of its blueprint is
there, 70% of its built blocks (not just ground), and 70% of its built blocks other than the most common one (so a
roof of wool stairs on grass doesn't count as a camp tent). Tested against worlds made by the game's own code (204
structures, with trees, ores and other decoration): it never named a wrong chunk, and every build of the project
checks 24 more in a real game. Some structures are not recognized, which only means they don't count: a village
whose centre was overbuilt by its own farm streets or a sand patch, a rare zombie village centre, or anything
players have taken apart. Underground structures (trial chambers, ancient cities, trail ruins) can only be seen
where the server sends the real underground blocks; servers that hide them (anti-xray) simply mean they are not
found.

Some structures are real but don't follow the seed: villages and outposts in chunks made by Minecraft 1.17 or
older (an upgraded world), structures placed with `/place` or copied from elsewhere, and exact copies players
built on a chunk corner. The mod ignores structures you place with `/place` yourself. When the others make "no
seed fits", the mod tries again leaving out one structure at a time; if that proves a seed (with the hashed seed or
bedrock), it shows the seed and names the structure that doesn't fit. Structures you remove on the mod's screen
are not noted again.

Each world has its own notes, told apart by the server address (or save name) and the hashed seed, so a server
network's lobby and game world, or a world that was reset, never mix.

The notes are saved in `config/jriksseedfinder/` inside your Minecraft folder: a `.txt` file per world that the
command-line app reads too (`seedfinder find --file <that file>`).

**What the mod does not do:** it adds no chat commands (so nothing can clash with a server's own commands or plugins,
such as `/locate`), sends nothing to the server, and doesn't change the game's code. It only looks at what the game
has already received, and only in worlds where you switched it on. Every build checks this automatically: a test
fails if the mod's code could send anything or register a command.

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
| `mod/` | The Fabric client mod. It contains the engine and adds the in-game part. |
| `tools/oracle/` | Generates the version data and the test answers from the official Minecraft server, which it downloads when run (never committed). |
| `docs/` | The [roadmap](docs/ROADMAP.md). |

**Testing against the real game.** `tools/oracle` runs Minecraft's own world generation code for a list of
test seeds and records the results. The engine's tests then require our code to give exactly the same answers.
For 26.3 that covers every structure type, 10 seeds, negative coordinates and positions far from spawn, the random
number generators used for blocks, the seeds Minecraft picks by itself, and the bedrock of real chunks generated
by the game (overworld and nether, 184,320 blocks). The finder's tests feed it structure positions recorded from
the game and require it to find the right seed.

## Building from source

Needs Java 25 (the mod's tools require it; the command-line app it builds still runs on Java 21). On Windows use
`gradlew.bat` instead of `./gradlew`.

```
./gradlew build                  # compile and run all tests, including the mod's "sends nothing" check
./gradlew :cli:fatJar            # the runnable jar: cli/build/libs/seedfinder.jar
                                 # the mod: mod/build/libs/jriks-seed-finder-mod-<version>.jar
xvfb-run -a ./gradlew :mod:runProductionClientGameTest    # starts real Minecraft and tests the mod in it (Linux)
```

To add or refresh a Minecraft version (needs the Java version that Minecraft version needs, Java 25 for 26.x):

```
tools/oracle/run-oracle.sh 26.3
```

## License

This project is licensed under the GNU General Public License v3.0, see [LICENSE](LICENSE).
It contains no Minecraft code or assets. The files under `engine/src/main/resources/.../versions/` contain only
facts about world generation (numbers and names) recorded from the game.

The runnable jar also includes [Gson](https://github.com/google/gson) and [picocli](https://picocli.info/),
both under the Apache License 2.0.
