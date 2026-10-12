package io.github.jriiik.seedfinder.bedrock;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * How one dimension places its random bedrock: which generator algorithm its blocks use and its bedrock rules (the
 * floor, and in the nether also the roof). Recorded from the game by the oracle.
 *
 * @param id                 the dimension id, such as {@code minecraft:overworld}
 * @param legacyRandomSource true when the dimension's blocks use the old 48-bit generator; its bedrock then only
 *                           depends on the structure seed (the lowest 48 bits of the world seed)
 * @param minY               the lowest block height that is generated
 * @param height             how many block layers are generated
 */
public record BedrockDimension(String id, boolean legacyRandomSource, int minY, int height, List<BedrockRule> rules) {

    public BedrockDimension {
        rules = List.copyOf(rules);
    }

    /** True when bedrock here depends on the whole 64-bit seed, false when only on the structure seed. */
    public boolean dependsOnFullSeed() {
        return !legacyRandomSource;
    }

    /** True when the game generates blocks at this height (outside of it there is nothing, or only air above). */
    public boolean isGenerated(int y) {
        return y >= minY && y < minY + height;
    }

    /** True when some bedrock rule makes the block at this height random. */
    public boolean isRandomAt(int y) {
        return rules.stream().anyMatch(rule -> rule.isRandomAt(y));
    }

    /** The floor: the lowest rule that places bedrock at the bottom (not a roof). */
    public Optional<BedrockRule> floor() {
        return rules.stream().filter(rule -> !rule.inverted()).min(Comparator.comparingInt(BedrockRule::trueAtAndBelow));
    }

    /** A short name for messages: "overworld", "the_nether"... */
    public String shortName() {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** The name people use: "overworld", "nether", "end". */
    public String displayName() {
        return shortName().replaceFirst("^the_", "");
    }
}
