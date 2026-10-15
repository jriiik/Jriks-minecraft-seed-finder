package io.github.jriiik.seedfinder.rng;

/**
 * Minecraft's {@code XoroshiroPositionalRandomFactory}. The position or name is mixed into the low half of the state
 * and the generator starts from there directly, without further mixing.
 */
public record XoroshiroPositionalFactory(long seedLo, long seedHi) implements PositionalRandomFactory {

    @Override
    public XoroshiroRandom at(int x, int y, int z) {
        return new XoroshiroRandom(WorldgenSeeds.positional(x, y, z) ^ seedLo, seedHi);
    }

    @Override
    public XoroshiroRandom fromHashOf(String name) {
        return fromHash(RandomSupport.seedFromHashOf(name));
    }

    /** Like {@link #fromHashOf} with the name's hash ({@link RandomSupport#seedFromHashOf}) worked out beforehand. */
    public XoroshiroRandom fromHash(RandomSupport.Seed128 hash) {
        return new XoroshiroRandom(hash.lo() ^ seedLo, hash.hi() ^ seedHi);
    }
}
