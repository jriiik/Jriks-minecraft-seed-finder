# Jrik's Minecraft Seed Finder

A tool that works out a Minecraft **Java Edition** world's seed from things you can see in the world,
such as where villages, temples and other structures are. Built from scratch, starting with the newest
Minecraft version (**26.3**).

> **Work in progress.** From about six structure positions the finder works out the world's *structure seed* (the
> part of the seed that decides where structures go), usually in under a second. Finding the full seed is the next
> step. See the [roadmap](docs/ROADMAP.md).

*Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.*

## What it can do today

| Feature | Status |
|---|---|
| Structure placement for Minecraft 26.3, checked against the real game | ✅ done |
| `seedfinder locate`: list structure spots for a seed | ✅ done |
| `seedfinder find`: work out the structure seed (48 bits) from structure positions | ✅ done |
| Find the full seed (64 bits) | ⏳ next |
| Fabric client mod that collects structure positions while you play | 🔜 planned |

## Trying the command-line app

1. Install **Java 21 or newer** (for example [Temurin](https://adoptium.net/)).
2. Get `seedfinder.jar`: download it from the latest successful
   [build](https://github.com/jriiik/Jriks-minecraft-seed-finder/actions) (the "seedfinder" artifact, you need
   to be signed in to GitHub), or build it yourself (see below).
3. Open a terminal in the folder with the jar and run, for example:

```
java -jar seedfinder.jar find desert_pyramid:272,80 swamp_hut:256,-144 village:336,80 shipwreck:-128,32 ruined_portal:16,0 trial_chamber:0,224
java -jar seedfinder.jar find --file my-structures.txt
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
   finder lists them all and names the structure types that would tell them apart.

**Try it on your own world:** create a world, use `/locate structure` for about six structures of different
types, put the results into a file, run `find`, and compare the answer with
`seedfinder structure-seed <the number /seed shows>`.

`locate` does the opposite: it lists the spots where each structure tries to generate for a seed you know, nearest
first, using the same positions that `/locate` reports. A structure only appears at its spot if the biome there
allows it. The finder does not check biomes yet, so some spots will turn out empty in the game.

## Ground rules

Seed finding is meant for your own worlds, friends' servers with their permission, servers whose rules allow it,
and server owners checking whether their own seed can be found. Many servers don't allow it, because the seed
reveals where ores and structures are. This project will not include anything to hide from anti-cheat or to get
around a server's seed protection.

## How the project is organized

| Folder | What it is |
|---|---|
| `engine/` | The seed-finding logic and the per-version world generation data. Plain Java, no Minecraft code. |
| `cli/` | The `seedfinder` command-line app. |
| `tools/oracle/` | Generates the version data and the test answers from the official Minecraft server, which it downloads when run (never committed). |
| `docs/` | The [roadmap](docs/ROADMAP.md). |

**Testing against the real game.** `tools/oracle` runs Minecraft's own world generation code for a list of
test seeds and records the results. The engine's tests then require our code to give exactly the same answers.
For 26.3 that covers every structure type, 10 seeds, negative coordinates and positions far from spawn. The
finder's tests feed it structure positions recorded from the game and require it to find the right seed.

## Building from source

Needs Java 21 or newer. On Windows use `gradlew.bat` instead of `./gradlew`.

```
./gradlew build                  # compile and run all tests
./gradlew :cli:fatJar            # the runnable jar: cli/build/libs/seedfinder.jar
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
