package io.github.jriiik.seedfinder.mod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.attribute.EnvironmentAttributeReader;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.TickPriority;

/**
 * A made-up flat world in memory for the game's own building code to write into: stone up to height 63, air above,
 * and every block the code places is remembered ({@link #written}). The structures built by code (desert pyramids,
 * jungle temples, swamp huts) are laid out this way at runtime, so the mod never needs a copy of them.
 *
 * <p>Only what that code needs works; anything else (entities, the server, light, sounds) throws, which stops the
 * building code. The swamp hut spawns its witch last, after all its blocks are placed.
 */
final class SyntheticLevel implements WorldGenLevel {

    static final int GROUND = 64;
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;

    private final RegistryAccess registries;
    private final Map<BlockPos, BlockState> written = new LinkedHashMap<>();
    private final ProtoChunk chunk;

    SyntheticLevel(RegistryAccess registries) {
        this.registries = registries;
        this.chunk = new ProtoChunk(new ChunkPos(0, 0), UpgradeData.EMPTY, LevelHeightAccessor.create(MIN_Y, HEIGHT),
            PalettedContainerFactory.create(registries), null);
    }

    /** Every block the code placed, by position, in the order first placed (later placements replace earlier ones). */
    Map<BlockPos, BlockState> written() {
        return written;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState state = written.get(pos);
        if (state != null) {
            return state;
        }
        return pos.getY() < GROUND ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
        written.put(pos.immutable(), state);
        return true;
    }

    @Override
    public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
        return predicate.test(getBlockState(pos));
    }

    @Override
    public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
        return predicate.test(getFluidState(pos));
    }

    @Override
    public int getHeight(Heightmap.Types type, int x, int z) {
        return GROUND;
    }

    @Override
    public int getMinY() {
        return MIN_Y;
    }

    @Override
    public int getHeight() {
        return HEIGHT;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean loadOrGenerate) {
        // Only asked for to mark blocks for later shape updates, which this world never makes.
        return chunk;
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay, TickPriority priority) {
    }

    @Override
    public void scheduleTick(BlockPos pos, Block block, int delay) {
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay, TickPriority priority) {
    }

    @Override
    public void scheduleTick(BlockPos pos, Fluid fluid, int delay) {
    }

    @Override
    public RandomSource getRandom() {
        return RandomSource.create(7);
    }

    @Override
    public long getSeed() {
        return 0;
    }

    @Override
    public RegistryAccess registryAccess() {
        return registries;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    private static UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException("not available while laying out a structure: " + what);
    }

    @Override
    public void addParticle(ParticleOptions particle, double x, double y, double z, double xd, double yd, double zd) {
        throw unsupported("particles");
    }

    @Override
    public boolean destroyBlock(BlockPos pos, boolean dropResources, Entity breaker, int updateLimit) {
        throw unsupported("destroyBlock");
    }

    @Override
    public DimensionType dimensionType() {
        throw unsupported("dimensionType");
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        throw unsupported("enabledFeatures");
    }

    @Override
    public EnvironmentAttributeReader environmentAttributes() {
        throw unsupported("environmentAttributes");
    }

    @Override
    public void gameEvent(Holder<GameEvent> event, Vec3 position, GameEvent.Context context) {
        throw unsupported("gameEvent");
    }

    @Override
    public BiomeManager getBiomeManager() {
        throw unsupported("getBiomeManager");
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        throw unsupported("getBlockTicks");
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        throw unsupported("getFluidTicks");
    }

    @Override
    public ChunkSource getChunkSource() {
        throw unsupported("getChunkSource");
    }

    @Override
    public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
        throw unsupported("getCurrentDifficultyAt");
    }

    @Override
    public List<Entity> getEntities(Entity except, AABB bb, Predicate<? super Entity> selector) {
        throw unsupported("entities");
    }

    @Override
    public <T extends Entity> List<T> getEntities(EntityTypeTest<Entity, T> type, AABB bb, Predicate<? super T> selector) {
        throw unsupported("entities");
    }

    @Override
    public LevelData getLevelData() {
        throw unsupported("getLevelData");
    }

    @Override
    public ServerLevel getLevel() {
        throw unsupported("getLevel");
    }

    @Override
    public LevelLightEngine getLightEngine() {
        throw unsupported("getLightEngine");
    }

    @Override
    public int getSeaLevel() {
        throw unsupported("getSeaLevel");
    }

    @Override
    public MinecraftServer getServer() {
        throw unsupported("getServer");
    }

    @Override
    public int getSkyDarken() {
        throw unsupported("getSkyDarken");
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        throw unsupported("getUncachedNoiseBiome");
    }

    @Override
    public WorldBorder getWorldBorder() {
        throw unsupported("getWorldBorder");
    }

    @Override
    public void levelEvent(Entity source, int type, BlockPos pos, int data) {
        throw unsupported("levelEvent");
    }

    @Override
    public long nextSubTickCount() {
        throw unsupported("nextSubTickCount");
    }

    @Override
    public void playSound(Entity except, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch) {
        throw unsupported("playSound");
    }

    @Override
    public List<? extends Player> players() {
        throw unsupported("players");
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean movedByPiston) {
        throw unsupported("removeBlock");
    }
}
