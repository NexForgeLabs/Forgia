/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.server.timings.TimeTracker;

/** Full original empty-input clock branch only. Cooking/fuel/recipes/notifications remain serial fences. */
final class C7FurnaceTask implements C7Task {
    private static final Set<Class<?>> TYPES = Set.of(FurnaceBlockEntity.class, BlastFurnaceBlockEntity.class, SmokerBlockEntity.class);
    private final ServerLevel level;
    private final TickingBlockEntity ticker;
    private final C7Options options;
    private final C7Ownership ownership;
    private final BooleanSupplier eligible;
    private final String type;
    private C7Refusal serialReason = C7Refusal.UNKNOWN_ACCESS;
    private final java.util.function.Consumer<C7Refusal> refused;
    @org.jetbrains.annotations.Nullable
    private LevelChunk.CapturedTickerBinding planned;

    C7FurnaceTask(ServerLevel level, TickingBlockEntity ticker, C7Options options, C7Ownership ownership, String type, java.util.function.Consumer<C7Refusal> refused, BooleanSupplier eligible) {
        this.level = level;
        this.ticker = ticker;
        this.options = options;
        this.ownership = ownership;
        this.eligible = eligible;
        this.refused = refused;
        this.type = type;
    }

    @Override
    public String type() {
        return this.type;
    }

    @Override
    public C7Refusal serialReason() {
        return this.serialReason;
    }

    @Override
    public Object identity() {
        return this.ticker;
    }

    @Override
    public boolean current() {
        return this.eligible.getAsBoolean();
    }

    @Override
    public void serial() {
        this.ownership.run(this.ticker.getPos(), this.ticker::tick);
    }

    @org.jetbrains.annotations.Nullable
    private LevelChunk.CapturedTickerBinding refuse(C7Refusal reason, String detail) {
        this.serialReason = reason;
        this.refused.accept(reason);
        if (this.options.debug()) org.apache.logging.log4j.LogManager.getLogger().info("C7 refusal: {} {}", reason, detail);
        return null;
    }

    @org.jetbrains.annotations.Nullable
    private LevelChunk.CapturedTickerBinding binding() {
        if (!C7RuntimeAudit.verified()) return this.refuse(C7Refusal.UNVERIFIED_RUNTIME, C7RuntimeAudit.reason());
        if (TimeTracker.BLOCK_ENTITY_UPDATE.isEnabled()) return this.refuse(C7Refusal.ACTIVE_TIMINGS, "");
        if (this.level.getClass() != ServerLevel.class) return this.refuse(C7Refusal.CUSTOM_WORLD, "");
        final var frame = LevelChunk.captureCoreTickerFrame(this.ticker);
        if (frame == null || frame.level() != this.level || frame.function() != C7FurnaceProgram.ticker()
                || !TYPES.contains(frame.source().getClass()) || frame.chunk().getClass() != LevelChunk.class)
            return this.refuse(C7Refusal.CUSTOM_INSTANCE, "frame/function/class");
        final var source = (AbstractFurnaceBlockEntity) frame.source();
        final var block = source.getBlockState().getBlock();
        if (block != Blocks.FURNACE && block != Blocks.BLAST_FURNACE && block != Blocks.SMOKER) return null;
        if (!source.getType().isValid(source.getBlockState())) return this.refuse(C7Refusal.INVALID_TYPE, "");
        if (!this.live(frame)) return this.refuse(C7Refusal.UNAVAILABLE_CHUNK, "stale lifetime");
        if (source.forgia$c7ClockSnapshot() == null) return this.refuse(C7Refusal.UNSUPPORTED_BRANCH, "");
        return frame;
    }

    private boolean live(LevelChunk.CapturedTickerBinding frame) {
        final var source = frame.source();
        final var pos = source.getBlockPos();
        return frame.current() && !source.isRemoved() && source.getLevel() == this.level && !source.hasAttachments()
                && frame.chunk().isLoaded()
                && this.level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == frame.chunk()
                && frame.chunk().getBlockEntities().get(pos) == source
                && frame.chunk().getBlockState(pos) == source.getBlockState();
    }

    @Override
    public C7Footprint footprint() {
        // Publish the exact core wrapper's lazy descriptive type name on the coordinator before
        // capturing its immutable frame. Unknown/custom getter callbacks are never invoked here.
        if (!C7RuntimeAudit.verified()) {
            this.refuse(C7Refusal.UNVERIFIED_RUNTIME, C7RuntimeAudit.reason());
            return C7Footprint.unknown();
        }
        if (!LevelChunk.exactCoreBlockEntityBinding(this.ticker)
                || LevelChunk.actualBlockEntityFunction(this.ticker) != C7FurnaceProgram.ticker()) {
            this.serialReason = C7TypePolicy.unsupported(this.type);
            this.refused.accept(this.serialReason);
            return C7Footprint.unknown();
        }
        final var subject = LevelChunk.c7TickerSubject(this.ticker);
        if (subject == null || !TYPES.contains(subject.getClass())) {
            this.refuse(C7Refusal.CUSTOM_INSTANCE, "unverified subject class");
            return C7Footprint.unknown();
        }
        if (!this.current()) return C7Footprint.unknown();
        this.ticker.getType();
        final var frame = LevelChunk.captureCoreTickerFrame(this.ticker);
        if (frame == null || !TYPES.contains(frame.source().getClass()) || frame.function() != C7FurnaceProgram.ticker())
            return C7Footprint.unknown();
        return this.ownership.call(frame.source().getBlockPos(), () -> {
            this.planned = this.binding();
            if (this.planned == null) return C7Footprint.unknown();
            return new C7Footprint(Map.of(
                    new C7Resource(this.level, this.planned.source(), C7Resource.Kind.BLOCK_ENTITY), C7Footprint.Access.WRITE,
                    new C7Resource(this.level, this.planned.chunk(), C7Resource.Kind.CHUNK), C7Footprint.Access.READ,
                    new C7Resource(this.level, this.planned.source().getBlockState(), C7Resource.Kind.BLOCK_STATE), C7Footprint.Access.READ,
                    new C7Resource(this.level, this.planned.source(), C7Resource.Kind.INVENTORY), C7Footprint.Access.READ), true);
        });
    }

    /** Workers receive this record, not the live validation/commit object below. Resource keys are opaque. */
    private record ClockComputation(C7Resource state, C7FurnaceClock before) implements C7Task.Computation {
        @Override public Object compute(C7AccessScope access) {
            final C7FurnaceClock[] result = new C7FurnaceClock[1];
            access.read(this.state, () -> this.before);
            access.write(this.state, () -> result[0] = this.before.advance());
            return result[0];
        }
    }

    @Override
    @org.jetbrains.annotations.Nullable
    public Prepared prepare() {
        final var previous = this.planned;
        if (previous == null) return null;
        return this.ownership.call(previous.source().getBlockPos(), () -> {
            final var fresh = this.binding();
            if (fresh == null || fresh.source() != previous.source() || fresh.chunk() != previous.chunk()
                    || fresh.delegate() != previous.delegate() || fresh.version() != previous.version())
                return null;
            final var source = (AbstractFurnaceBlockEntity) fresh.source();
            final C7FurnaceClock before = source.forgia$c7ClockSnapshot();
            final C7Resource state = new C7Resource(this.level, source, C7Resource.Kind.BLOCK_ENTITY);
            return new Prepared() {
                @Override
                public C7Task.Computation computation() {
                    return new ClockComputation(state, before);
                }

                @Override
                public boolean valid() {
                    return ownership.call(source.getBlockPos(), () -> live(fresh) && before.equals(source.forgia$c7ClockSnapshot()));
                }

                @Override
                public void commit(Object result) {
                    ownership.run(source.getBlockPos(), () -> source.forgia$c7CommitClock(before, (C7FurnaceClock) result));
                }
            };
        });
    }
}
