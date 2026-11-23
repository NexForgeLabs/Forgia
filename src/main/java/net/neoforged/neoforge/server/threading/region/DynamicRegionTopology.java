/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.ApiStatus;

/**
 * Folia-style dynamic region topology for a single level.
 * <p>
 * The world is partitioned into fixed {@code (1 << sectionShift) x (1 << sectionShift)}-chunk sections.
 * Loaded chunks activate their containing section. During the server-thread {@code GLOBAL} safe point,
 * {@link #reconcile(long)} maintains the core isolation invariant:
 * <ul>
 *     <li>Every non-empty section belongs to exactly one dynamic region (identified by a positive {@code int} ID).</li>
 *     <li>No two distinct regions own sections within Chebyshev distance {@code <= mergeRadius} (default 1,
 *     8-neighborhood). Consequently, any two independent regions are separated by at least one full ring of
 *     empty sections ({@code 1 << sectionShift} chunks wide).</li>
 *     <li>Emptied sections wait {@code splitDelayTicks} before being detached from their region and triggering
 *     a connected-component split check, preventing thrashing when players cross section borders.</li>
 * </ul>
 * <p>
 * All mutations ({@link #addChunk(int, int)}, {@link #removeChunk(int, int)}, {@link #reconcile(long)},
 * {@link #clear()}) run on the server thread during quiescent/global phases. Worker threads perform lock-free,
 * zero-allocation O(1) ownership lookups against the volatile published snapshot via
 * {@link #regionIdAtSection(int, int)}, {@link #regionIdAtChunk(int, int)}, and {@link #regionIdAtBlock(BlockPos)}.
 */
@ApiStatus.Internal
public final class DynamicRegionTopology {
    /** Sentinel returned when a section does not belong to any active region. */
    public static final int NO_REGION = 0;

    /** Smallest supported section shift: 2 -> 4x4 chunks (64x64 blocks). */
    public static final int MIN_SECTION_SHIFT = 1;
    /** Largest supported section shift: 4 -> 16x16 chunks (256x256 blocks, Folia default). */
    public static final int MAX_SECTION_SHIFT = 4;
    /** Default section shift: 3 -> 8x8 chunks (128x128 blocks). */
    public static final int DEFAULT_SECTION_SHIFT = 3;

    /** Default merge radius in sections (1 = 8-neighborhood, guaranteeing a 1-section empty buffer). */
    public static final int DEFAULT_MERGE_RADIUS = 1;
    /** Default hysteresis delay in ticks before an emptied section is detached and checked for splitting. */
    public static final long DEFAULT_SPLIT_DELAY_TICKS = 40L;

    private static final int CHUNK_BLOCK_SHIFT = 4;

    private final int sectionShift;
    private final int sectionChunkSize;
    private final int mergeRadius;
    private final long splitDelayTicks;

    /** Exact set of currently loaded chunk keys to keep add/remove strictly idempotent. */
    private final LongOpenHashSet loadedChunks = new LongOpenHashSet();
    /** Section key -> count of loaded chunks in that section. */
    private final Long2IntOpenHashMap sectionChunkCounts = new Long2IntOpenHashMap();
    /** Region ID -> mutable region entry (server thread only). */
    private final Int2ObjectOpenHashMap<RegionEntry> regions = new Int2ObjectOpenHashMap<>();
    /** Mutable section key -> region ID map (server thread only). */
    private final Long2IntOpenHashMap mutableSectionToRegion = newSectionRegionMap();

    /** Sections that transitioned 0 -> >0 and need placement/merge at the next safe point. */
    private final LongOpenHashSet pendingActivations = new LongOpenHashSet();
    /** Sections that transitioned >0 -> 0 and need an emptied timestamp at the next safe point. */
    private final LongOpenHashSet newlyEmptiedSections = new LongOpenHashSet();
    /** Section key -> tick when it became empty (hysteresis queue). */
    private final Long2LongOpenHashMap pendingEmptySinceTick = new Long2LongOpenHashMap();

    /** Reusable scratch collections for reconcile() to avoid steady-state allocations. */
    private final IntOpenHashSet neighborRegionScratch = new IntOpenHashSet();
    private final IntOpenHashSet splitCandidateRegionsScratch = new IntOpenHashSet();
    private final LongArrayList expiredSectionsScratch = new LongArrayList();
    private final LongArrayList bfsQueueScratch = new LongArrayList();
    private final LongOpenHashSet bfsVisitedScratch = new LongOpenHashSet();

    private int nextRegionId = 1;
    private volatile long topologyVersion;
    private long mergeCount;
    private long splitCount;
    private final LongAdder coalescedSubmissions = new LongAdder();

    /**
     * Lock-free read-only snapshot published after each structural change in {@link #reconcile(long)}.
     * Never mutated after publication.
     */
    private volatile Long2IntOpenHashMap publishedSectionToRegion = newSectionRegionMap();

    public DynamicRegionTopology() {
        this(DEFAULT_SECTION_SHIFT, DEFAULT_MERGE_RADIUS, DEFAULT_SPLIT_DELAY_TICKS);
    }

    public DynamicRegionTopology(int sectionShift, int mergeRadius, long splitDelayTicks) {
        if (sectionShift < MIN_SECTION_SHIFT || sectionShift > MAX_SECTION_SHIFT) {
            throw new IllegalArgumentException(
                    "Section shift must be in " + MIN_SECTION_SHIFT + ".." + MAX_SECTION_SHIFT + ": " + sectionShift);
        }
        if (mergeRadius < 1) {
            throw new IllegalArgumentException("Merge radius must be >= 1: " + mergeRadius);
        }
        if (splitDelayTicks < 0L) {
            throw new IllegalArgumentException("Split delay ticks must be >= 0: " + splitDelayTicks);
        }
        this.sectionShift = sectionShift;
        this.sectionChunkSize = 1 << sectionShift;
        this.mergeRadius = mergeRadius;
        this.splitDelayTicks = splitDelayTicks;
        this.sectionChunkCounts.defaultReturnValue(0);
        this.pendingEmptySinceTick.defaultReturnValue(Long.MIN_VALUE);
    }

    private static Long2IntOpenHashMap newSectionRegionMap() {
        final Long2IntOpenHashMap map = new Long2IntOpenHashMap();
        map.defaultReturnValue(NO_REGION);
        return map;
    }

    public int sectionShift() {
        return this.sectionShift;
    }

    public int sectionChunkSize() {
        return this.sectionChunkSize;
    }

    public int mergeRadius() {
        return this.mergeRadius;
    }

    public long splitDelayTicks() {
        return this.splitDelayTicks;
    }

    public int sectionX(int chunkX) {
        return chunkX >> this.sectionShift;
    }

    public int sectionZ(int chunkZ) {
        return chunkZ >> this.sectionShift;
    }

    public static long sectionKey(int sectionX, int sectionZ) {
        return ChunkPos.asLong(sectionX, sectionZ);
    }

    public static int sectionXFromKey(long sectionKey) {
        return ChunkPos.getX(sectionKey);
    }

    public static int sectionZFromKey(long sectionKey) {
        return ChunkPos.getZ(sectionKey);
    }

    /**
     * Records a loaded chunk. O(1), server-thread only. Idempotent if the chunk is already tracked.
     *
     * @return {@code true} if the chunk was newly added
     */
    public boolean addChunk(int chunkX, int chunkZ) {
        final long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        if (!this.loadedChunks.add(chunkKey)) {
            return false;
        }
        final long secKey = sectionKey(chunkX >> this.sectionShift, chunkZ >> this.sectionShift);
        final int prevCount = this.sectionChunkCounts.get(secKey);
        this.sectionChunkCounts.put(secKey, prevCount + 1);
        if (prevCount == 0) {
            // Cancel any pending empty/split timer on this section.
            this.newlyEmptiedSections.remove(secKey);
            this.pendingEmptySinceTick.remove(secKey);
            if (this.mutableSectionToRegion.get(secKey) == NO_REGION) {
                this.pendingActivations.add(secKey);
            }
        }
        return true;
    }

    /**
     * Records an unloaded chunk. O(1), server-thread only. Idempotent if the chunk is not tracked.
     *
     * @return {@code true} if the chunk was previously tracked and removed
     */
    public boolean removeChunk(int chunkX, int chunkZ) {
        final long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        if (!this.loadedChunks.remove(chunkKey)) {
            return false;
        }
        final long secKey = sectionKey(chunkX >> this.sectionShift, chunkZ >> this.sectionShift);
        final int nextCount = this.sectionChunkCounts.get(secKey) - 1;
        if (nextCount <= 0) {
            this.sectionChunkCounts.remove(secKey);
            if (!this.pendingActivations.remove(secKey) && this.mutableSectionToRegion.get(secKey) != NO_REGION) {
                this.newlyEmptiedSections.add(secKey);
            }
        } else {
            this.sectionChunkCounts.put(secKey, nextCount);
        }
        return true;
    }

    boolean hasPendingChanges() {
        return !this.pendingActivations.isEmpty()
                || !this.newlyEmptiedSections.isEmpty()
                || !this.pendingEmptySinceTick.isEmpty();
    }

    /**
     * Applies pending section expirations/splits and activations/merges at the server-thread {@code GLOBAL}
     * safe point.
     * <p>
     * Steady-state ticks with no pending changes perform zero allocations and return
     * {@link ReconcileResult#UNCHANGED}.
     *
     * @param currentTick current monotonic server tick
     * @return summary of structural changes applied in this safe point
     */
    public ReconcileResult reconcile(long currentTick) {
        if (!this.hasPendingChanges()) {
            return ReconcileResult.UNCHANGED;
        }

        // 1. Stamp newly emptied sections with the current tick.
        if (!this.newlyEmptiedSections.isEmpty()) {
            final LongIterator it = this.newlyEmptiedSections.iterator();
            while (it.hasNext()) {
                final long secKey = it.nextLong();
                if (this.sectionChunkCounts.get(secKey) == 0
                        && this.mutableSectionToRegion.get(secKey) != NO_REGION
                        && !this.pendingEmptySinceTick.containsKey(secKey)) {
                    this.pendingEmptySinceTick.put(secKey, currentTick);
                }
            }
            this.newlyEmptiedSections.clear();
        }

        boolean changed = false;
        List<MergeEvent> merges = null;
        List<SplitEvent> splits = null;
        IntArrayList retired = null;

        // 2. Expire empty sections past splitDelayTicks BEFORE activating new sections,
        //    preventing unnecessary merge+split churn in the same tick.
        if (!this.pendingEmptySinceTick.isEmpty()) {
            this.expiredSectionsScratch.clear();
            final ObjectIterator<Long2LongMap.Entry> it = this.pendingEmptySinceTick.long2LongEntrySet().fastIterator();
            while (it.hasNext()) {
                final Long2LongMap.Entry entry = it.next();
                if (currentTick - entry.getLongValue() >= this.splitDelayTicks) {
                    this.expiredSectionsScratch.add(entry.getLongKey());
                }
            }

            if (!this.expiredSectionsScratch.isEmpty()) {
                this.splitCandidateRegionsScratch.clear();
                for (int i = 0; i < this.expiredSectionsScratch.size(); ++i) {
                    final long secKey = this.expiredSectionsScratch.getLong(i);
                    this.pendingEmptySinceTick.remove(secKey);
                    if (this.sectionChunkCounts.get(secKey) != 0) {
                        continue;
                    }
                    final int regionId = this.mutableSectionToRegion.remove(secKey);
                    if (regionId != NO_REGION) {
                        final RegionEntry region = this.regions.get(regionId);
                        if (region != null) {
                            region.sections.remove(secKey);
                            this.splitCandidateRegionsScratch.add(regionId);
                            changed = true;
                        }
                    }
                }

                // Check each affected region for retirement or connected-component split.
                final var regionIt = this.splitCandidateRegionsScratch.iterator();
                while (regionIt.hasNext()) {
                    final int regionId = regionIt.nextInt();
                    final RegionEntry region = this.regions.get(regionId);
                    if (region == null) {
                        continue;
                    }
                    if (region.sections.isEmpty()) {
                        this.regions.remove(regionId);
                        if (retired == null) {
                            retired = new IntArrayList(4);
                        }
                        retired.add(regionId);
                        continue;
                    }
                    if (region.sections.size() <= 1) {
                        region.refreshAnchor();
                        continue;
                    }
                    final List<LongOpenHashSet> components = this.findConnectedComponents(region.sections);
                    if (components.size() <= 1) {
                        region.refreshAnchor();
                        continue;
                    }
                    // Largest component keeps the existing regionId (tie-break by smallest canonical anchor).
                    int keeperIndex = 0;
                    long keeperAnchor = minSectionKey(components.get(0));
                    for (int i = 1; i < components.size(); ++i) {
                        final LongOpenHashSet candidate = components.get(i);
                        final LongOpenHashSet keeper = components.get(keeperIndex);
                        final long candidateAnchor = minSectionKey(candidate);
                        if (candidate.size() > keeper.size()
                                || (candidate.size() == keeper.size() && compareSectionKeys(candidateAnchor, keeperAnchor) < 0)) {
                            keeperIndex = i;
                            keeperAnchor = candidateAnchor;
                        }
                    }
                    region.sections.clear();
                    region.sections.addAll(components.get(keeperIndex));
                    region.anchorSectionKey = keeperAnchor;

                    final int[] newRegionIds = new int[components.size() - 1];
                    int outIdx = 0;
                    for (int i = 0; i < components.size(); ++i) {
                        if (i == keeperIndex) {
                            continue;
                        }
                        final LongOpenHashSet comp = components.get(i);
                        final int newId = this.nextRegionId++;
                        final RegionEntry newRegion = new RegionEntry(newId, comp, minSectionKey(comp));
                        this.regions.put(newId, newRegion);
                        final LongIterator secIt = comp.iterator();
                        while (secIt.hasNext()) {
                            this.mutableSectionToRegion.put(secIt.nextLong(), newId);
                        }
                        newRegionIds[outIdx++] = newId;
                    }
                    this.splitCount++;
                    if (splits == null) {
                        splits = new ArrayList<>(4);
                    }
                    splits.add(new SplitEvent(regionId, newRegionIds));
                }
            }
        }

        // 3. Process pending activations (immediate placement and 8-neighborhood merges).
        if (!this.pendingActivations.isEmpty()) {
            // Sort activations deterministically by canonical section order so region IDs are reproducible.
            final LongArrayList orderedActivations = new LongArrayList(this.pendingActivations);
            orderedActivations.sort(DynamicRegionTopology::compareSectionKeys);
            this.pendingActivations.clear();

            for (int i = 0; i < orderedActivations.size(); ++i) {
                final long secKey = orderedActivations.getLong(i);
                if (this.sectionChunkCounts.get(secKey) == 0) {
                    continue;
                }
                if (this.mutableSectionToRegion.get(secKey) != NO_REGION) {
                    continue;
                }

                this.neighborRegionScratch.clear();
                final int sx = sectionXFromKey(secKey);
                final int sz = sectionZFromKey(secKey);
                for (int dz = -this.mergeRadius; dz <= this.mergeRadius; ++dz) {
                    for (int dx = -this.mergeRadius; dx <= this.mergeRadius; ++dx) {
                        if ((dx | dz) == 0) {
                            continue;
                        }
                        final int neighborId = this.mutableSectionToRegion.get(sectionKey(sx + dx, sz + dz));
                        if (neighborId != NO_REGION) {
                            this.neighborRegionScratch.add(neighborId);
                        }
                    }
                }

                if (this.neighborRegionScratch.isEmpty()) {
                    final int newId = this.nextRegionId++;
                    final LongOpenHashSet set = new LongOpenHashSet(4);
                    set.add(secKey);
                    this.regions.put(newId, new RegionEntry(newId, set, secKey));
                    this.mutableSectionToRegion.put(secKey, newId);
                    changed = true;
                } else if (this.neighborRegionScratch.size() == 1) {
                    final int targetId = this.neighborRegionScratch.iterator().nextInt();
                    final RegionEntry target = this.regions.get(targetId);
                    target.addSection(secKey);
                    this.mutableSectionToRegion.put(secKey, targetId);
                    changed = true;
                } else {
                    // Merge small regions into the largest neighbor region (tie-break by smallest regionId).
                    int targetId = NO_REGION;
                    int targetSize = -1;
                    final var nit = this.neighborRegionScratch.iterator();
                    while (nit.hasNext()) {
                        final int candidateId = nit.nextInt();
                        final int candidateSize = this.regions.get(candidateId).sections.size();
                        if (candidateSize > targetSize || (candidateSize == targetSize && candidateId < targetId)) {
                            targetId = candidateId;
                            targetSize = candidateSize;
                        }
                    }

                    final RegionEntry target = this.regions.get(targetId);
                    target.addSection(secKey);
                    this.mutableSectionToRegion.put(secKey, targetId);

                    final var mergeIt = this.neighborRegionScratch.iterator();
                    while (mergeIt.hasNext()) {
                        final int sourceId = mergeIt.nextInt();
                        if (sourceId == targetId) {
                            continue;
                        }
                        final RegionEntry source = this.regions.remove(sourceId);
                        if (source == null) {
                            continue;
                        }
                        final LongIterator secIt = source.sections.iterator();
                        while (secIt.hasNext()) {
                            final long movedKey = secIt.nextLong();
                            target.addSection(movedKey);
                            this.mutableSectionToRegion.put(movedKey, targetId);
                        }
                        this.mergeCount++;
                        if (merges == null) {
                            merges = new ArrayList<>(4);
                        }
                        merges.add(new MergeEvent(sourceId, targetId));
                    }
                    changed = true;
                }
            }
        }

        if (!changed) {
            return ReconcileResult.UNCHANGED;
        }

        this.publishedSectionToRegion = this.mutableSectionToRegion.clone();
        this.topologyVersion++;
        return new ReconcileResult(
                true,
                this.topologyVersion,
                merges == null ? List.of() : Collections.unmodifiableList(merges),
                splits == null ? List.of() : Collections.unmodifiableList(splits),
                retired == null ? new int[0] : retired.toIntArray());
    }

    /**
     * Clears all tracked chunks and regions (used on level unload). Server-thread only.
     */
    public void clear() {
        this.loadedChunks.clear();
        this.sectionChunkCounts.clear();
        this.regions.clear();
        this.mutableSectionToRegion.clear();
        this.pendingActivations.clear();
        this.newlyEmptiedSections.clear();
        this.pendingEmptySinceTick.clear();
        this.neighborRegionScratch.clear();
        this.splitCandidateRegionsScratch.clear();
        this.expiredSectionsScratch.clear();
        this.bfsQueueScratch.clear();
        this.bfsVisitedScratch.clear();
        this.publishedSectionToRegion = newSectionRegionMap();
        this.topologyVersion++;
    }

    // ---- Lock-free worker read API (O(1), zero allocation) ----

    /**
     * {@return the published region ID owning section {@code (sectionX, sectionZ)}, or {@value #NO_REGION}}
     */
    public int regionIdAtSection(int sectionX, int sectionZ) {
        return this.publishedSectionToRegion.get(sectionKey(sectionX, sectionZ));
    }

    /**
     * {@return the published region ID owning chunk {@code (chunkX, chunkZ)}, or {@value #NO_REGION}}
     */
    public int regionIdAtChunk(int chunkX, int chunkZ) {
        return this.publishedSectionToRegion.get(sectionKey(chunkX >> this.sectionShift, chunkZ >> this.sectionShift));
    }

    /**
     * {@return the published region ID owning {@code pos}, or {@value #NO_REGION}}
     */
    public int regionIdAtBlock(BlockPos pos) {
        return this.regionIdAtChunk(pos.getX() >> CHUNK_BLOCK_SHIFT, pos.getZ() >> CHUNK_BLOCK_SHIFT);
    }

    /**
     * {@return {@code true} when chunk {@code (chunkX, chunkZ)} belongs to {@code regionId} in the published snapshot}
     */
    public boolean isChunkInRegion(int chunkX, int chunkZ, int regionId) {
        return regionId != NO_REGION && this.regionIdAtChunk(chunkX, chunkZ) == regionId;
    }

    /**
     * {@return {@code true} when {@code pos} belongs to {@code regionId} in the published snapshot}
     */
    public boolean isBlockInRegion(BlockPos pos, int regionId) {
        return regionId != NO_REGION && this.regionIdAtBlock(pos) == regionId;
    }

    // ---- Server-thread inspection & diagnostics ----

    public int regionCount() {
        return this.regions.size();
    }

    public int activeSectionCount() {
        return this.mutableSectionToRegion.size();
    }

    public int loadedChunkCount() {
        return this.loadedChunks.size();
    }

    public int pendingEmptySectionCount() {
        return this.pendingEmptySinceTick.size() + this.newlyEmptiedSections.size();
    }

    public long topologyVersion() {
        return this.topologyVersion;
    }

    public long mergeCount() {
        return this.mergeCount;
    }

    public long splitCount() {
        return this.splitCount;
    }

    void recordCoalescedSubmissions(int saved) {
        if (saved > 0) {
            this.coalescedSubmissions.add(saved);
        }
    }

    public long coalescedSubmissions() {
        return this.coalescedSubmissions.sum();
    }

    /**
     * {@return an immutable snapshot of all currently active dynamic regions, ordered by canonical anchor}
     */
    public List<RegionSnapshot> snapshotRegions() {
        if (this.regions.isEmpty()) {
            return List.of();
        }
        final List<RegionSnapshot> out = new ArrayList<>(this.regions.size());
        final ObjectIterator<Int2ObjectMap.Entry<RegionEntry>> it = this.regions.int2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            final RegionEntry entry = it.next().getValue();
            final long[] sectionKeys = entry.sections.toLongArray();
            int chunks = 0;
            for (final long secKey : sectionKeys) {
                chunks += this.sectionChunkCounts.get(secKey);
            }
            out.add(new RegionSnapshot(
                    entry.id,
                    sectionXFromKey(entry.anchorSectionKey),
                    sectionZFromKey(entry.anchorSectionKey),
                    sectionKeys,
                    chunks));
        }
        out.sort((a, b) -> {
            final int cmpX = Integer.compare(a.anchorSectionX(), b.anchorSectionX());
            return cmpX != 0 ? cmpX : Integer.compare(a.anchorSectionZ(), b.anchorSectionZ());
        });
        return Collections.unmodifiableList(out);
    }

    private List<LongOpenHashSet> findConnectedComponents(LongOpenHashSet sectionKeys) {
        this.bfsVisitedScratch.clear();
        final List<LongOpenHashSet> components = new ArrayList<>(2);
        final LongIterator allIt = sectionKeys.iterator();
        while (allIt.hasNext()) {
            final long startKey = allIt.nextLong();
            if (!this.bfsVisitedScratch.add(startKey)) {
                continue;
            }
            final LongOpenHashSet component = new LongOpenHashSet();
            this.bfsQueueScratch.clear();
            this.bfsQueueScratch.add(startKey);
            component.add(startKey);

            int head = 0;
            while (head < this.bfsQueueScratch.size()) {
                final long current = this.bfsQueueScratch.getLong(head++);
                final int sx = sectionXFromKey(current);
                final int sz = sectionZFromKey(current);
                for (int dz = -this.mergeRadius; dz <= this.mergeRadius; ++dz) {
                    for (int dx = -this.mergeRadius; dx <= this.mergeRadius; ++dx) {
                        if ((dx | dz) == 0) {
                            continue;
                        }
                        final long neighbor = sectionKey(sx + dx, sz + dz);
                        if (sectionKeys.contains(neighbor) && this.bfsVisitedScratch.add(neighbor)) {
                            component.add(neighbor);
                            this.bfsQueueScratch.add(neighbor);
                        }
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    private static long minSectionKey(LongOpenHashSet keys) {
        long best = 0L;
        boolean first = true;
        final LongIterator it = keys.iterator();
        while (it.hasNext()) {
            final long k = it.nextLong();
            if (first || compareSectionKeys(k, best) < 0) {
                best = k;
                first = false;
            }
        }
        return best;
    }

    private static int compareSectionKeys(long a, long b) {
        final int cmpX = Integer.compare(sectionXFromKey(a), sectionXFromKey(b));
        return cmpX != 0 ? cmpX : Integer.compare(sectionZFromKey(a), sectionZFromKey(b));
    }

    private static final class RegionEntry {
        final int id;
        final LongOpenHashSet sections;
        long anchorSectionKey;

        RegionEntry(int id, LongOpenHashSet sections, long anchorSectionKey) {
            this.id = id;
            this.sections = sections;
            this.anchorSectionKey = anchorSectionKey;
        }

        void addSection(long secKey) {
            this.sections.add(secKey);
            if (compareSectionKeys(secKey, this.anchorSectionKey) < 0) {
                this.anchorSectionKey = secKey;
            }
        }

        void refreshAnchor() {
            if (!this.sections.isEmpty()) {
                this.anchorSectionKey = minSectionKey(this.sections);
            }
        }
    }

    public record MergeEvent(int sourceRegionId, int targetRegionId) {}

    public record SplitEvent(int sourceRegionId, int[] newRegionIds) {}

    public record ReconcileResult(
            boolean changed,
            long topologyVersion,
            List<MergeEvent> merges,
            List<SplitEvent> splits,
            int[] retiredRegionIds) {
        static final ReconcileResult UNCHANGED = new ReconcileResult(false, 0L, List.of(), List.of(), new int[0]);
    }

    public record RegionSnapshot(
            int regionId,
            int anchorSectionX,
            int anchorSectionZ,
            long[] sectionKeys,
            int loadedChunkCount) {
        public int sectionCount() {
            return this.sectionKeys.length;
        }
    }
}
