/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.LongStream;
import javax.annotation.Nullable;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/**
 * Mod-agnostic fast chunk palette NBT codec and off-thread section pre-decoder.
 *
 * <p>Avoids Mojang DFU {@code RecordCodecBuilder}/{@code KeyDispatchCodec} overhead on the hot
 * {@link ChunkSerializer#read} and {@link ChunkSerializer#write} paths while preserving exact vanilla
 * {@link PalettedContainer} representations and falling back cleanly to vanilla {@link Codec}s
 * whenever an unknown or non-standard entry is encountered.
 */
public final class FastChunkSerialization {
    private static final String TAG_PALETTE = "palette";
    private static final String TAG_DATA = "data";
    private static final String TAG_NAME = "Name";
    private static final String TAG_PROPERTIES = "Properties";

    /**
     * Thread-safe cache of immutable serialized {@link CompoundTag} templates per {@link BlockState}.
     * Entries are copied only if mutated, or inserted directly into freshly created save {@link ListTag}s
     * because {@link ChunkSerializer#write} only hands the resulting tag tree to {@code ChunkDataEvent.Save}
     * and {@code IOWorker} (which reads and compresses it). To guarantee zero aliasing hazards with
     * {@code ChunkDataEvent.Save} listeners that may mutate tags, we return {@link CompoundTag#copy()}
     * which is still ~10x faster than DFU {@code BlockState.CODEC.encodeStart}.
     */
    private static final ConcurrentHashMap<BlockState, CompoundTag> ENCODED_STATE_CACHE = new ConcurrentHashMap<>(4096);

    /**
     * Thread-safe cache from block registry id string to {@link Block} instance.
     */
    private static final ConcurrentHashMap<String, Optional<Block>> BLOCK_LOOKUP_CACHE = new ConcurrentHashMap<>(2048);

    /**
     * Thread-safe cache for {@link Property} value parsing: property -> (valueName -> Comparable).
     */
    private static final ConcurrentHashMap<Property<?>, ConcurrentHashMap<String, Optional<Comparable<?>>>> PROPERTY_VALUE_CACHE = new ConcurrentHashMap<>(512);

    /**
     * Thread-local handoff from background IO pre-decoding to {@link ChunkSerializer#read} on the server thread,
     * keyed by chunk coordinate and tag identity so it never leaks or mismatches across recursive/modded calls.
     */
    private static final ThreadLocal<PredecodedSections> PREDECODED_HANDOFF = new ThreadLocal<>();

    public record PredecodedSections(
            ChunkPos pos,
            CompoundTag chunkTag,
            LevelChunkSection[] sections) {}

    private FastChunkSerialization() {}

    /**
     * Wraps {@code originalHasMoreTime} with a per-tick wall-clock budget for {@code ChunkMap.processUnloads}
     * without introducing a synthetic lambda into {@code ChunkMap} (preserving vanilla lambda numbering for Mixins).
     */
    public static java.util.function.BooleanSupplier wrapUnloadBudget(java.util.function.BooleanSupplier originalHasMoreTime) {
        long unloadBudget = RegionThreadingConfig.chunkUnloadTickBudgetNanos();
        if (unloadBudget <= 0L) {
            return originalHasMoreTime;
        }
        long unloadStart = System.nanoTime();
        return () -> originalHasMoreTime.getAsBoolean() && (System.nanoTime() - unloadStart) < unloadBudget;
    }

    /**
     * Runs off-thread section pre-decoding on a loaded chunk tag (called from {@code ChunkMap.scheduleChunkLoad}
     * without adding any synthetic lambda to {@code ChunkMap}).
     */
    public static Optional<Map.Entry<CompoundTag, Optional<PredecodedSections>>> attachPredecoded(
            ServerLevel level, ChunkPos pos, Optional<CompoundTag> maybeTag) {
        if (maybeTag.isEmpty()) {
            return Optional.empty();
        }
        CompoundTag tag = maybeTag.get();
        PredecodedSections predecoded = predecodeSections(level, pos, tag);
        return Optional.of(Map.entry(tag, Optional.ofNullable(predecoded)));
    }

    /**
     * Installs pre-decoded sections for the current thread right before {@link ChunkSerializer#read}.
     */
    public static void pushPredecoded(@Nullable PredecodedSections predecoded) {
        if (predecoded != null) {
            PREDECODED_HANDOFF.set(predecoded);
        }
    }

    /**
     * Clears any remaining thread-local handoff after {@link ChunkSerializer#read}.
     */
    public static void clearPredecoded() {
        PREDECODED_HANDOFF.remove();
    }

    /**
     * Consumes pre-decoded sections if they match the exact chunk pos and root tag.
     */
    @Nullable
    public static LevelChunkSection[] consumePredecoded(ChunkPos pos, CompoundTag chunkTag) {
        PredecodedSections entry = PREDECODED_HANDOFF.get();
        if (entry != null && entry.chunkTag() == chunkTag && entry.pos().equals(pos)) {
            PREDECODED_HANDOFF.remove();
            return entry.sections();
        }
        return null;
    }

    /**
     * Pre-decodes all chunk sections (block states + biomes + {@link LevelChunkSection} block counts)
     * off the server thread. Safe to run on IO/background threads because it only reads the chunk NBT
     * and immutable registries.
     *
     * @return pre-decoded sections, or {@code null} if disabled or if any section fails fast decode
     *         (in which case the server thread falls back seamlessly to standard {@link ChunkSerializer#read}).
     */
    @Nullable
    public static PredecodedSections predecodeSections(ServerLevel level, ChunkPos pos, CompoundTag chunkTag) {
        if (!RegionThreadingConfig.asyncChunkLoadDeserializeEnabled()) {
            return null;
        }
        try {
            ListTag sectionsTag = chunkTag.getList("sections", Tag.TAG_COMPOUND);
            int sectionCount = level.getSectionsCount();
            LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
            Registry<Biome> biomeRegistry = level.registryAccess().registryOrThrow(Registries.BIOME);
            Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = null;
            boolean fastPalette = RegionThreadingConfig.fastChunkPaletteSerializationEnabled();

            for (int j = 0; j < sectionsTag.size(); j++) {
                CompoundTag sectionTag = sectionsTag.getCompound(j);
                int sectionY = sectionTag.getByte("Y");
                int index = level.getSectionIndexFromSectionY(sectionY);
                if (index < 0 || index >= sections.length) {
                    continue;
                }

                PalettedContainer<BlockState> blockStates = null;
                if (sectionTag.contains("block_states", Tag.TAG_COMPOUND)) {
                    CompoundTag bsTag = sectionTag.getCompound("block_states");
                    if (fastPalette) {
                        blockStates = readBlockStatesFast(bsTag);
                    }
                    if (blockStates == null) {
                        // Fall back to server thread so any partial error logging / custom codec stays exact.
                        return null;
                    }
                } else {
                    blockStates = new PalettedContainer<>(
                            Block.BLOCK_STATE_REGISTRY,
                            Blocks.AIR.defaultBlockState(),
                            PalettedContainer.Strategy.SECTION_STATES);
                }

                PalettedContainerRO<Holder<Biome>> biomes;
                if (sectionTag.contains("biomes", Tag.TAG_COMPOUND)) {
                    if (biomeCodec == null) {
                        biomeCodec = PalettedContainer.codecRO(
                                biomeRegistry.asHolderIdMap(),
                                biomeRegistry.holderByNameCodec(),
                                PalettedContainer.Strategy.SECTION_BIOMES,
                                biomeRegistry.getHolderOrThrow(Biomes.PLAINS));
                    }
                    var decoded = biomeCodec.parse(NbtOps.INSTANCE, sectionTag.getCompound("biomes")).result();
                    if (decoded.isEmpty()) {
                        return null;
                    }
                    biomes = decoded.get();
                } else {
                    biomes = new PalettedContainer<>(
                            biomeRegistry.asHolderIdMap(),
                            biomeRegistry.getHolderOrThrow(Biomes.PLAINS),
                            PalettedContainer.Strategy.SECTION_BIOMES);
                }

                sections[index] = new LevelChunkSection(blockStates, biomes);
            }
            return new PredecodedSections(pos, chunkTag, sections);
        } catch (Throwable ignored) {
            // Never fail chunk loading from background pre-decode; let vanilla ChunkSerializer.read handle it.
            return null;
        }
    }

    /**
     * Fast direct NBT reader for standard vanilla {@code block_states} {@link CompoundTag}.
     * Returns {@code null} on any unrecognized block/property/storage format so caller falls back to vanilla Codec.
     */
    @Nullable
    public static PalettedContainer<BlockState> readBlockStatesFast(CompoundTag blockStatesTag) {
        if (!blockStatesTag.contains(TAG_PALETTE, Tag.TAG_LIST)) {
            return null;
        }
        ListTag paletteTag = blockStatesTag.getList(TAG_PALETTE, Tag.TAG_COMPOUND);
        int paletteSize = paletteTag.size();
        if (paletteSize <= 0) {
            return null;
        }

        List<BlockState> paletteEntries = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            BlockState state = readBlockState(paletteTag.getCompound(i));
            if (state == null) {
                return null;
            }
            paletteEntries.add(state);
        }

        Optional<LongStream> storage;
        if (blockStatesTag.contains(TAG_DATA, Tag.TAG_LONG_ARRAY)) {
            long[] raw = blockStatesTag.getLongArray(TAG_DATA);
            storage = Optional.of(LongStream.of(raw));
        } else {
            storage = Optional.empty();
        }

        PalettedContainerRO.PackedData<BlockState> packed = new PalettedContainerRO.PackedData<>(paletteEntries, storage);
        var result = PalettedContainer.unpack(Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES, packed);
        return result.result().orElse(null);
    }

    /**
     * Fast direct NBT writer for {@link PalettedContainer}{@code <BlockState>}.
     * Uses cached per-{@link BlockState} NBT templates to bypass DFU {@code Codec.encodeStart}.
     */
    @Nullable
    public static CompoundTag writeBlockStatesFast(PalettedContainer<BlockState> container) {
        PalettedContainerRO.PackedData<BlockState> packed = container.pack(
                Block.BLOCK_STATE_REGISTRY, PalettedContainer.Strategy.SECTION_STATES);
        List<BlockState> entries = packed.paletteEntries();
        if (entries.isEmpty()) {
            return null;
        }

        ListTag paletteList = new ListTag();
        for (int i = 0; i < entries.size(); i++) {
            paletteList.add(encodeBlockStateCached(entries.get(i)));
        }

        CompoundTag out = new CompoundTag();
        out.put(TAG_PALETTE, paletteList);
        Optional<LongStream> storage = packed.storage();
        if (storage.isPresent()) {
            out.putLongArray(TAG_DATA, storage.get().toArray());
        }
        return out;
    }

    private static CompoundTag encodeBlockStateCached(BlockState state) {
        CompoundTag template = ENCODED_STATE_CACHE.get(state);
        if (template == null) {
            template = buildBlockStateTag(state);
            ENCODED_STATE_CACHE.putIfAbsent(state, template);
        }
        return template.copy();
    }

    private static CompoundTag buildBlockStateTag(BlockState state) {
        CompoundTag tag = new CompoundTag();
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        tag.putString(TAG_NAME, key.toString());
        Map<Property<?>, Comparable<?>> values = state.getValues();
        if (!values.isEmpty()) {
            CompoundTag propsTag = new CompoundTag();
            for (Map.Entry<Property<?>, Comparable<?>> entry : values.entrySet()) {
                Property<?> prop = entry.getKey();
                propsTag.putString(prop.getName(), getPropertyValueName(prop, entry.getValue()));
            }
            tag.put(TAG_PROPERTIES, propsTag);
        }
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String getPropertyValueName(Property<T> property, Comparable<?> value) {
        return property.getName((T) value);
    }

    @Nullable
    private static BlockState readBlockState(CompoundTag stateTag) {
        String name = stateTag.getString(TAG_NAME);
        if (name.isEmpty()) {
            return null;
        }
        Block block = lookupBlock(name);
        if (block == null) {
            return null;
        }
        BlockState state = block.defaultBlockState();
        if (!stateTag.contains(TAG_PROPERTIES, Tag.TAG_COMPOUND)) {
            return state;
        }
        CompoundTag propsTag = stateTag.getCompound(TAG_PROPERTIES);
        StateDefinition<Block, BlockState> definition = block.getStateDefinition();
        for (String key : propsTag.getAllKeys()) {
            Property<?> prop = definition.getProperty(key);
            if (prop == null) {
                // Unknown property: let vanilla codec handle (e.g. ignore/log)
                return null;
            }
            String valueStr = propsTag.getString(key);
            Comparable<?> val = lookupPropertyValue(prop, valueStr);
            if (val == null) {
                return null;
            }
            state = setStateValue(state, prop, val);
        }
        return state;
    }

    @Nullable
    private static Block lookupBlock(String name) {
        Optional<Block> cached = BLOCK_LOOKUP_CACHE.get(name);
        if (cached != null) {
            return cached.orElse(null);
        }
        ResourceLocation rl = ResourceLocation.tryParse(name);
        if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
            // Do not cache missing keys permanently in case registries reload/sync.
            return null;
        }
        Block block = BuiltInRegistries.BLOCK.get(rl);
        BLOCK_LOOKUP_CACHE.putIfAbsent(name, Optional.of(block));
        return block;
    }

    @Nullable
    private static Comparable<?> lookupPropertyValue(Property<?> property, String valueName) {
        ConcurrentHashMap<String, Optional<Comparable<?>>> map = PROPERTY_VALUE_CACHE.computeIfAbsent(
                property, k -> new ConcurrentHashMap<>(8));
        Optional<Comparable<?>> cached = map.get(valueName);
        if (cached != null) {
            return cached.orElse(null);
        }
        Optional<? extends Comparable<?>> parsed = property.getValue(valueName);
        Optional<Comparable<?>> boxed = parsed.map(v -> v);
        map.putIfAbsent(valueName, boxed);
        return boxed.orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setStateValue(BlockState state, Property property, Comparable value) {
        return state.setValue(property, value);
    }
}
