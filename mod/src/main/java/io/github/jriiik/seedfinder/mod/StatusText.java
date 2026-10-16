package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.biome.OverworldBiomes;
import io.github.jriiik.seedfinder.finder.BiomeEvidence;
import io.github.jriiik.seedfinder.finder.FullSeedFinder;
import io.github.jriiik.seedfinder.finder.NetherBedrockSearch;
import io.github.jriiik.seedfinder.finder.SeedCheck;
import io.github.jriiik.seedfinder.finder.WorldSeedSearch;
import io.github.jriiik.seedfinder.version.VersionData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Collectors;

/** The mod's status in plain words, for its screen and the one-line display. */
final class StatusText {

    private StatusText() {
    }

    /** The structures recognized from blocks in a Minecraft version, as the player reads them (camps only from 26.x). */
    static String recognizedTypes(VersionData data) {
        return "villages, outposts, trial chambers, ancient cities, trail ruins, "
            + (data.structureSet("abandoned_camp").isPresent() ? "camps, " : "")
            + "desert pyramids, jungle temples, swamp huts, igloos, shipwrecks, ocean ruins and ruined portals";
    }

    static List<String> lines(WorldSession session, SearchRunner runner) {
        List<String> lines = new ArrayList<>();
        lines.add("World: " + session.displayName());
        if (!session.collecting()) {
            lines.add("Collecting is switched off in this world, so nothing is noted here.");
            lines.add("Switch it on only for your own worlds or where the server allows looking for the seed.");
            lines.add("Each world has its own notes; worlds behind one address (a lobby and a game world, a reset world) are told");
            lines.add("apart by the hashed seed the server sends.");
            return lines;
        }
        List<WorldSession.NotedStructure> structures = session.structures();
        long recognized = structures.stream().filter(s -> s.source().equals(StructureRecognition.SOURCE)).count();
        lines.add("Structures: " + structures.size() + (structures.isEmpty() ? "" : " (" + structures.stream()
            .map(WorldSession.NotedStructure::set).distinct().collect(Collectors.joining(", ")) + ")")
            + (recognized > 0 ? ", " + recognized + " recognized automatically" : ""));
        List<String> missing = StructureRecognition.missingSets();
        if (!missing.isEmpty()) {
            lines.add("Could not prepare recognition of " + String.join(", ", missing) + " (see the game log).");
        }
        lines.addAll(session.disagreements());
        int leftOut = session.leftOutBedrockChunks(NETHER);
        lines.add("Bedrock: " + chunks(session.bedrockChunks("minecraft:overworld"), "overworld") + ", "
            + chunks(session.bedrockChunks(NETHER), "nether") + (leftOut == 0 ? "" : " (" + leftOut + " more left out: made by an older Minecraft"
                + " version, or changed" + (leftOut >= WorldSession.LEFT_OUT_BEFORE_SPACING ? "; nether chunks near them are not read, those of"
                + " newer land farther away are" : "") + ")"));
        lines.add(SeedFinderClient.biomeListFailed() ? "Biomes: not noted, because the list of biomes could not be loaded (see the game log)."
            : session.foreignBiome().isPresent()
            ? "Biomes: not noted, because this world has a biome the normal overworld doesn't have (" + session.foreignBiome().get()
                + "): a data pack or mod changes it."
            : "Biomes: " + session.biomeChunks() + " chunk" + (session.biomeChunks() == 1 ? "" : "s") + " in " + session.biomeAreas() + " area"
                + (session.biomeAreas() == 1 ? "" : "s") + (session.biomeAreas() == 1 ? " (another area is noted once you are about "
                + String.format(Locale.ROOT, "%,d", WorldSession.biomeAreaDistance(WorldSession.BIOME_AREA_SPACING)) + " blocks away)" : ""));
        lines.add("Hashed seed from the server: " + (session.hashedSeed().isPresent() ? "received" : "not yet"));
        TypedSeedRunner typedSeeds = SeedFinderClient.typedSeeds();
        if (session.worldSeed().isEmpty() && session.typedSeed().isEmpty()) {
            if (typedSeeds.runningFor(session)) {
                lines.add("Checking whether the seed was typed (as text or a short number): " + typedSeeds.percent() + "%");
            } else if (typedSeeds.failedFor(session)) {
                lines.add("The check whether the seed was typed stopped with an error (see the game log). It starts again when you come back.");
            } else if (session.typedSeedSearchDone()) {
                lines.add("No seed typed as text or a short number has the hashed seed this server sent (all 4,294,967,296 were checked):"
                    + " the seed was picked by Minecraft or typed as a longer number, or the server sends a made-up hashed seed.");
            }
        }
        lines.add("");
        WorldSeedSearch.Result result = session.result();
        OptionalLong typed = session.typedSeed();
        if (typed.isPresent() && (result == null || result.worldSeed().isEmpty()) && typedSeedLines(session, typed.getAsLong(), lines)) {
            return lines;
        }
        if (runner.running()) {
            lines.add("Searching..." + (runner.percent() >= 0 ? " " + runner.percent() + "%" : ""));
            return lines;
        }
        if (session.resultError() != null) {
            lines.add("The search stopped with an error: " + session.resultError());
            return lines;
        }
        if (result == null) {
            String types = recognizedTypes(session.minecraft().data());
            lines.add(structures.isEmpty()
                ? Character.toUpperCase(types.charAt(0)) + types.substring(1) + " are recognized when you come near. Other"
                    + " structures you can add yourself (\"Add a structure here\"). Answers to /locate (if you may use it) are noted too."
                : "About to search...");
            return lines;
        }
        if (!session.resultIsCurrent()) {
            lines.add("(New notes since this result; it will be updated.)");
        }
        switch (result.stage()) {
            case NEED_MORE_STRUCTURES -> {
                lines.add("Not enough to search yet:");
                result.plan().problems().forEach(problem -> lines.add("- " + problem));
                lines.add("- Or: the nether's bedrock floor and roof of one chunk give the structure seed without any structures (they are"
                    + " read from the chunks around you in the nether).");
                netherLines(result, lines);
            }
            case NO_STRUCTURE_SEED -> {
                if (result.hashedSeedFitsNone()) {
                    lines.add("The seeds that fit the notes don't have this world's hashed seed, so a note doesn't follow the seed: a"
                        + " structure noted in the wrong chunk, or from chunks made by an older Minecraft version, a command or a copy."
                        + " Remove the ones you doubt.");
                } else if (result.plan().canSearch()) {
                    lines.add("No seed fits these structures together. Possible reasons: a structure noted in the wrong chunk, structures");
                    lines.add("in chunks made by an older Minecraft version, structures placed by a command or copied, or a server that");
                    lines.add("places structures differently (then the seed can't be found from structures). Remove the ones you doubt.");
                } else {
                    lines.add("No structure seed fits the nether bedrock seen: its chunks may have been made by Minecraft 1.17 or older (their"
                        + " bedrock doesn't follow the seed), or the server changes bedrock.");
                }
                netherLines(result, lines);
            }
            case STRUCTURE_SEED -> structureSeedLines(result, lines);
            case WORLD_SEED -> {
                long worldSeed = result.worldSeed().getAsLong();
                lines.add("World seed: " + worldSeed);
                String source = source(result, worldSeed);
                lines.add(switch (result.proof().orElseThrow()) {
                    case HASHED_SEED -> "Certain: it is the only seed that fits " + source + " and the hashed seed the server sent.";
                    case BEDROCK -> "Certain: it is the only seed that fits " + source + " and the bedrock floor.";
                    case BIOMES -> "Certain: it is the only seed that fits " + source + " and the biomes seen, and a wrong seed would fit them"
                        + " this well only by a rare chance.";
                });
                result.leftOut().ifPresent(misfit -> {
                    lines.add("Left out: " + misfit + " doesn't fit this seed. It may come from an older Minecraft version, a command");
                    lines.add("or a copy (or was noted in the wrong place). Remove it on \"Remove a structure\".");
                });
                for (var misfit : result.misfits()) {
                    lines.add("Doesn't fit this seed: " + misfit + ". It may come from an older Minecraft version, a command or a copy (or"
                        + " was noted in the wrong place). Remove it on \"Remove a structure\".");
                }
                unusableNetherLines(result, lines);
                if (result.netherDisagrees() && !netherFits(result, worldSeed)) {
                    lines.add("Note: the nether bedrock seen doesn't fit this seed (nether chunks of another world with the same hashed seed,"
                        + " or made by an older Minecraft version?).");
                }
                if (result.hashedSeedFitsNone()) {
                    lines.add("Note: the hashed seed the server sent fits none of the seeds that fit the notes: the server may send a made-up"
                        + " value.");
                }
                if (result.bedrockDisagrees()) {
                    lines.add("Note: some of the bedrock seen doesn't fit this seed (the server may change blocks it sends).");
                }
                if (result.biomesDisagree()) {
                    lines.add("Note: the biomes seen don't fit this seed (chunks made by an older Minecraft version, or a server that changes"
                        + " biomes).");
                }
            }
        }
        return lines;
    }

    /**
     * What the typed seed is, judged by the notes. Returns false when the notes contradict it: then the world seed has
     * to come from the seed search, whose status follows.
     */
    private static boolean typedSeedLines(WorldSession session, long seed, List<String> lines) {
        Optional<SeedCheck> found = session.typedSeedCheck();
        if (found.isEmpty()) {
            lines.add("The hashed seed the server sent is the one of the typed seed " + seed + ". Checking it against the notes...");
            return true;
        }
        SeedCheck check = found.get();
        if (session.hashedSeedIsGenuine()) {
            lines.add("World seed: " + seed);
            lines.add("Certain: it was typed (as text or as this number), and its hash is the hashed seed of this single-player world.");
            if (!check.misfits().isEmpty()) {
                boolean one = check.misfits().size() == 1;
                lines.add("Note: " + misfits(check) + " fit this seed: " + (one ? "it" : "they") + " may come from an older Minecraft version,"
                    + " a command or a copy (or " + (one ? "was" : "were") + " noted in the wrong place).");
            }
            if (check.overworld().mismatches() > 0) {
                lines.add("Note: some of the overworld bedrock seen doesn't fit this seed (it may have been changed).");
            }
            netherNote(check, lines);
            biomeNote(check, lines);
            return true;
        }
        switch (check.verdict()) {
            case CONFIRMED -> {
                lines.add("World seed: " + seed);
                lines.add("Certain: it was typed (as text or as this number), its hash is the hashed seed the server sent, and "
                    + agreeing(check) + ".");
                if (!check.misfits().isEmpty()) {
                    boolean one = check.misfits().size() == 1;
                    lines.add("Note: " + misfits(check) + " fit this seed. The server may place structures differently, or "
                        + (one ? "it comes" : "they come") + " from an older Minecraft version, a command or a copy (or "
                        + (one ? "was" : "were") + " noted in the wrong place).");
                }
                netherNote(check, lines);
                biomeNote(check, lines);
                return true;
            }
            case UNCONFIRMED -> {
                lines.add("World seed: " + seed);
                lines.add("It was typed (as text or as this number), and its hash is the hashed seed the server sent. That is certain unless"
                    + " the server sends a made-up hashed seed; bedrock and structures noted later will confirm it.");
                return true;
            }
            case CONTRADICTED -> {
                lines.add("The hashed seed the server sent is the one of the typed seed " + seed + ", but " + disagreeing(check) + ". "
                    + contradictionReason(check));
                return false;
            }
        }
        return false;
    }

    /** Why notes contradict a typed seed, and what would tell. */
    static String contradictionReason(SeedCheck check) {
        if (check.bedrockMostlyFits()) {
            return "Most of the overworld bedrock fits it, though (a wrong seed gets about a third of it wrong), so those blocks may have"
                + " been changed or read wrong; it isn't shown as the world seed while they disagree.";
        }
        if (check.overworld().mismatches() > 0 && check.structuresConfirm()) {
            return "The structures noted confirm it, though, so the server may change the bedrock it sends; it isn't shown as the world"
                + " seed while the bedrock disagrees.";
        }
        if (check.overworld().mismatches() > 0) {
            return "The server probably sends a made-up hashed seed, so the world seed has to come from structures and bedrock instead.";
        }
        List<String> otherwise = new ArrayList<>();
        if (check.nether().mismatches() > 0) {
            otherwise.add("those nether chunks were made by Minecraft 1.17 or older");
        }
        if (!check.misfits().isEmpty()) {
            boolean one = check.misfits().size() == 1;
            otherwise.add((one ? "that structure was" : "those structures were") + " noted in the wrong place or " + (one ? "doesn't" : "don't")
                + " follow the seed (older chunks, a command, a copy, or a server that places structures differently)");
        }
        if (check.biomes().misfit()) {
            otherwise.add("the biomes come from chunks made by an older Minecraft version or changed by the server");
        }
        return "Either the server sends a made-up hashed seed, or " + String.join(" and ", otherwise) + ". Bedrock from the overworld would tell which.";
    }

    private static void netherNote(SeedCheck check, List<String> lines) {
        if (check.nether().mismatches() > 0) {
            lines.add("Note: some of the nether bedrock seen doesn't fit this seed: nether chunks made by Minecraft 1.17 or older don't"
                + " follow the seed (or the blocks were changed).");
        }
    }

    private static void biomeNote(SeedCheck check, List<String> lines) {
        if (check.biomes().misfit()) {
            lines.add("Note: the biomes seen don't fit this seed (chunks made by an older Minecraft version, or a server that changes"
                + " biomes).");
        }
    }

    /** What fits the seed: "the overworld bedrock seen fits it", "the overworld bedrock seen and 2 of the 3 structures noted fit it", ... */
    static String agreeing(SeedCheck check) {
        List<String> parts = new ArrayList<>();
        if (check.overworld().fits()) {
            parts.add("the overworld bedrock seen");
        }
        if (check.nether().fits()) {
            parts.add("the nether bedrock seen");
        }
        if (!check.fitting().isEmpty()) {
            parts.add(structures(check.fitting().size(), check.fitting().size() + check.misfits().size()));
        }
        if (check.biomes().fitsUnder().isPresent()) {
            parts.add("the biomes seen");
        }
        return String.join(" and ", parts) + (parts.size() > 1 || check.fitting().size() > 1 ? " fit it" : " fits it");
    }

    /** What doesn't fit the seed: "the structure noted doesn't fit it", "1 of the 2 structures noted and the nether bedrock seen don't fit it", ... */
    static String disagreeing(SeedCheck check) {
        List<String> parts = new ArrayList<>();
        if (!check.misfits().isEmpty()) {
            parts.add(structures(check.misfits().size(), check.fitting().size() + check.misfits().size()));
        }
        if (check.overworld().mismatches() > 0) {
            parts.add("the overworld bedrock seen");
        }
        if (check.nether().mismatches() > 0) {
            parts.add("the nether bedrock seen");
        }
        if (check.biomes().misfit()) {
            parts.add("the biomes seen");
        }
        return String.join(" and ", parts) + (parts.size() > 1 || check.misfits().size() > 1 || check.biomes().misfit() && parts.size() == 1
            ? " don't fit it" : " doesn't fit it");
    }

    /** "the structure noted", "the 3 structures noted", "2 of the 3 structures noted". */
    private static String structures(int some, int all) {
        return some < all ? some + " of the " + all + " structures noted" : all == 1 ? "the structure noted" : "the " + all + " structures noted";
    }

    /** The structures that don't fit, with the verb: "villages at block (352, 80) doesn't", "2 of the structures noted don't". */
    private static String misfits(SeedCheck check) {
        return check.misfits().size() == 1 ? check.misfits().get(0) + " doesn't" : check.misfits().size() + " of the structures noted don't";
    }

    private static final String NETHER = "minecraft:the_nether";

    /** What the nether bedrock said, when it was searched. */
    static void netherLines(WorldSeedSearch.Result result, List<String> lines) {
        unusableNetherLines(result, lines);
        result.nether().ifPresent(finding -> {
            if (finding.capped()) {
                lines.add("Too many structure seeds fit the nether bedrock seen to go on with; the floor and the roof of a whole chunk"
                    + " narrow them down.");
            } else if (result.netherDisagrees() && finding.proven() && !result.structuresOnlySeeds().isEmpty()) {
                lines.add("The structures and the nether bedrock disagree: the structures fit " + seeds(result.structuresOnlySeeds())
                    + ", the nether bedrock " + seeds(finding.structureSeeds()) + ". One side is wrong (a structure noted in the wrong"
                    + " place or from older chunks, or nether chunks of another world); the hashed seed, overworld bedrock or biomes"
                    + " tell which.");
            } else if (result.netherDisagrees() && result.structuresOnlySeeds().isEmpty()) {
                lines.add("No seed fits all the structures, so one of them doesn't follow the seed (noted in the wrong place, from older"
                    + " chunks, or placed by a command; remove the ones you doubt); the structure seed"
                    + (finding.proven() ? " comes from the nether bedrock." : "s the nether bedrock allows are used instead."));
            } else if (result.netherDisagrees()) {
                lines.add("Note: none of the structures' seeds fits the nether bedrock seen (nether chunks made by an older Minecraft"
                    + " version, or changed?).");
            }
        });
    }

    /** The nether chunks the search found unusable, and those that don't fit the candidates the others allow. */
    private static void unusableNetherLines(WorldSeedSearch.Result result, List<String> lines) {
        result.nether().ifPresent(finding -> {
            List<io.github.jriiik.seedfinder.world.ChunkPos> leftOut = WorldSeedSearch.unusableNetherChunks(finding);
            for (io.github.jriiik.seedfinder.world.ChunkPos chunk : finding.unusableChunks()) {
                lines.add("The nether bedrock of the chunk at " + chunk.minBlockX() + ", " + chunk.minBlockZ() + (leftOut.contains(chunk)
                    ? " fits no seed that fits the rest: made by Minecraft 1.17 or older, or changed. It is left out."
                    : " doesn't fit the seeds the other nether chunks allow: one of them was made by Minecraft 1.17 or older, or changed."));
            }
        });
    }

    /** What the world seed was found from besides its proof: "the structures", "the nether bedrock", or both. */
    private static String source(WorldSeedSearch.Result result, long worldSeed) {
        boolean structures = result.plan().canSearch() && result.misfits().isEmpty() && !result.plan().observations().isEmpty()
            && !(result.netherDisagrees() && netherFits(result, worldSeed));
        boolean nether = netherFits(result, worldSeed);
        return structures && nether ? "the structures and the nether bedrock" : structures ? "the structures" : nether ? "the nether bedrock"
            : "the notes";
    }

    private static boolean netherFits(WorldSeedSearch.Result result, long worldSeed) {
        long structureSeed = worldSeed & 0xFFFF_FFFF_FFFFL;
        return result.nether().map(finding -> finding.structureSeeds().contains(structureSeed)).orElse(false);
    }

    private static String seeds(List<Long> seeds) {
        return seeds.size() <= 3 ? seeds.stream().map(String::valueOf).collect(Collectors.joining(" or ")) : seeds.size() + " seeds";
    }

    private static void structureSeedLines(WorldSeedSearch.Result result, List<String> lines) {
        List<Long> seeds = result.structureSeeds();
        boolean fromNether = result.fromNetherAlone();
        boolean bothSides = result.netherDisagrees() && !result.structuresOnlySeeds().isEmpty()
            && result.nether().map(NetherBedrockSearch.Finding::proven).orElse(false);
        if (bothSides) {
            // said by netherLines below
        } else if (seeds.size() == 1) {
            lines.add("Structure seed: " + seeds.get(0) + " (the last 48 bits of the world seed" + (fromNether ? ", from the nether bedrock)" : ")"));
        } else if (fromNether && result.nether().get().proven() && seeds.size() == 2) {
            lines.add("Structure seed: " + seeds.get(0) + " or " + seeds.get(1) + ". These two have the same nether bedrock everywhere; the"
                + " hashed seed, structures, overworld bedrock or biomes tell them apart.");
        } else if (fromNether) {
            lines.add(seeds.size() + " structure seeds fit the nether bedrock seen; more of it (the floor and the roof of a chunk) or"
                + " structures narrow them down.");
        } else {
            lines.add(seeds.size() + " structure seeds fit. More structures tell them apart"
                + (result.plan().suggestedStructures().isEmpty() ? "." : ", best: " + String.join(", ", result.plan().suggestedStructures()) + "."));
        }
        netherLines(result, lines);
        if (result.hashedSeedFitsNone()) {
            lines.add("The hashed seed from the server fits none of them: this server may send a different value.");
        }
        if (!result.bedrockProblems().isEmpty()) {
            lines.add("Some bedrock seen breaks the game's rules, so bedrock isn't used: " + result.bedrockProblems().get(0));
        } else if (result.bedrock().isPresent()) {
            FullSeedFinder.Result bedrock = result.bedrock().get();
            List<FullSeedFinder.Candidate> best = bedrock.best();
            if (best.isEmpty() || best.get(0).mismatches() > 0) {
                lines.add("No world seed fits all of the bedrock seen.");
            } else if (bedrock.perfectFits() > 1) {
                lines.add(String.format(Locale.ROOT, "%,d world seeds fit the bedrock so far; more bedrock is noted as you explore.", bedrock.perfectFits()));
            } else {
                lines.add("One world seed fits the bedrock, " + best.get(0).worldSeed() + ", but more bedrock is needed to be sure.");
            }
        } else if (result.biomes().isEmpty()) {
            lines.add("The world seed follows from bedrock (noted when you are near the bottom of the world) or from biomes (noted"
                + " around you).");
        }
        result.biomes().ifPresent(decision -> {
            if (decision.proven() && result.bedrockDisagrees()) {
                lines.add("Only " + decision.seed().getAsLong() + " fits the biomes seen, but some bedrock seen doesn't fit it while another"
                    + " seed fits all of it: notes from two worlds, or chunks changed or made by an older version on one side?");
            } else {
                biomeLines(decision, lines);
            }
        });
        List<Long> likely = result.likelySeeds();
        if (!likely.isEmpty()) {
            lines.add("If nobody typed a seed, it is probably " + likely.stream().map(String::valueOf).collect(Collectors.joining(" or ")) + ".");
        } else if (result.likelyRuledOut()) {
            lines.add("The biomes or the bedrock seen rule out the seeds Minecraft picks itself and those typed text gives: the seed was"
                + " typed as a number.");
        }
    }

    /** What the biomes say when they didn't prove a seed. */
    static void biomeLines(BiomeEvidence.Decision decision, List<String> lines) {
        if (decision.proven()) {
            return;
        }
        if (!decision.complete()) {
            lines.add("The biome check didn't finish.");
        } else if (decision.fitting().isEmpty()) {
            lines.add("No world seed fits the biomes seen: chunks made by an older Minecraft version, or a server that changes biomes?");
        } else if (decision.fitting().size() == 1) {
            lines.add("One world seed fits the biomes seen, " + decision.fitting().get(0) + ", but that isn't proof yet: "
                + (decision.groups() < 2 && !decision.likely()
                    ? "the biomes of one area only prove a seed Minecraft picked itself or a text seed. Biomes of another area about "
                        + String.format(Locale.ROOT, "%,d", WorldSession.biomeAreaDistance(
                            BiomeEvidence.groupSpacing(decision.preset().orElse(OverworldBiomes.Preset.NORMAL))))
                        + " blocks from the others prove any seed."
                    : "the areas noted fit many seeds (all one biome, like ocean?). Areas where several biomes meet tell more."));
        } else {
            lines.add(String.format(Locale.ROOT, "%,d world seeds fit the biomes seen so far; more areas tell them apart.", decision.fitting().size()));
        }
    }

    /** One short line for the corner of the screen. */
    static String statusLine(WorldSession session, SearchRunner runner) {
        OptionalLong seed = session.worldSeed();
        if (seed.isPresent()) {
            boolean unconfirmed = (session.result() == null || session.result().worldSeed().isEmpty()) && !session.hashedSeedIsGenuine()
                && session.typedSeedCheck().map(check -> check.verdict() == SeedCheck.Verdict.UNCONFIRMED).orElse(false);
            return "Seed Finder: world seed " + seed.getAsLong() + (unconfirmed ? " (not confirmed yet)" : "");
        }
        if (runner.running()) {
            return "Seed Finder: searching" + (runner.percent() >= 0 ? " " + runner.percent() + "%" : "...");
        }
        WorldSeedSearch.Result result = session.result();
        String notes = session.structures().size() + " structures, " + (session.bedrockChunks("minecraft:overworld") + session.bedrockChunks(NETHER))
            + " bedrock chunks"
            + (session.biomeAreas() > 0 ? ", " + session.biomeAreas() + " biome area" + (session.biomeAreas() == 1 ? "" : "s") : "")
            + (SeedFinderClient.typedSeeds().runningFor(session) ? ", checking typed seeds " + SeedFinderClient.typedSeeds().percent() + "%" : "");
        if (result == null) {
            return "Seed Finder: " + notes;
        }
        return switch (result.stage()) {
            case NEED_MORE_STRUCTURES -> "Seed Finder: " + notes + ", need more structures or nether bedrock";
            case NO_STRUCTURE_SEED -> "Seed Finder: " + (result.hashedSeedFitsNone() ? "the notes don't fit this world's hashed seed"
                : result.plan().canSearch() ? "no seed fits the structures" : "no seed fits the notes");
            case STRUCTURE_SEED -> "Seed Finder: " + (result.structureSeeds().size() == 1
                ? "structure seed " + result.structureSeeds().get(0) : result.structureSeeds().size() + " structure seeds") + ", " + notes;
            case WORLD_SEED -> "Seed Finder: world seed " + result.worldSeed().getAsLong();
        };
    }

    /** The best seed to copy: the world seed, else a single structure seed. */
    static OptionalLong seedToCopy(WorldSession session) {
        if (session.worldSeed().isPresent()) {
            return session.worldSeed();
        }
        WorldSeedSearch.Result result = session.result();
        return result != null && result.stage() == WorldSeedSearch.Stage.STRUCTURE_SEED && result.structureSeeds().size() == 1
            ? OptionalLong.of(result.structureSeeds().get(0)) : OptionalLong.empty();
    }

    private static String chunks(int count, String dimension) {
        return count + " " + dimension + " chunk" + (count == 1 ? "" : "s");
    }
}
