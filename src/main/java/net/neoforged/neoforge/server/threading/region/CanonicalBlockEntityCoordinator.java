/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.server.threading.region.BlockEntityExecutionContract.Inspection;
import org.jetbrains.annotations.Nullable;

/** One phase, one monotonically increasing cursor, and only a contiguous proven prefix at that cursor. */
final class CanonicalBlockEntityCoordinator {
    interface Unit {
        RegionBlockEntityTickAdmission admission();

        Inspection inspection();
    }

    interface Driver {
        @Nullable
        Unit prepare(int index);

        boolean mayAppend(List<Unit> prefix, Unit next);

        default boolean maySkipInFenceTail(List<Unit> prefix, Unit next) {
            return next.getClass() != prefix.getFirst().getClass();
        }

        default int prefixLimit(Unit first) {
            return RegionBlockEntityWorkGraph.MAX_UNITS;
        }

        /** Returns the contiguous count admitted before a pre-body refusal; every started body has joined. */
        int executePrefix(List<Unit> prefix);

        void serial(int index);
    }

    private final List<RegionBlockEntityTickAdmission> ordered;
    private int cursor;

    CanonicalBlockEntityCoordinator(List<RegionBlockEntityTickAdmission> ordered) {
        this.ordered = ordered;
        long previous = -1;
        for (int i = 0; i < ordered.size(); ++i) {
            final long seq = ordered.get(i).sequence();
            if (seq <= previous) throw new IllegalArgumentException("Noncanonical invocation ordinals");
            previous = seq;
        }
    }

    void execute(Driver driver) {
        this.execute(driver, false);
    }

    void execute(Driver driver, boolean deferFencesToTail) {
        if (!deferFencesToTail) {
            final List<Unit> prefix = new ArrayList<>();
            final int total = this.ordered.size();
            while (this.cursor < total) {
                final var first = driver.prepare(this.cursor);
                if (first == null) {
                    driver.serial(this.cursor);
                    ++this.cursor;
                    continue;
                }
                prefix.clear();
                prefix.add(first);
                final int limit = Math.min(RegionBlockEntityWorkGraph.MAX_UNITS, driver.prefixLimit(first));
                if (limit < 1) throw new IllegalStateException("Empty canonical planning window");
                for (int index = this.cursor + 1; index < total && prefix.size() < limit; ++index) {
                    final var next = driver.prepare(index);
                    if (next == null || !driver.mayAppend(prefix, next)) break;
                    prefix.add(next);
                }
                final int admitted = driver.executePrefix(prefix);
                if (admitted < 0 || admitted > prefix.size()) throw new IllegalStateException("Noncontiguous execution result");
                this.cursor += admitted;
                if (admitted < prefix.size()) {
                    // No body at/after the refusal ran. The complete original ticker is a Fence; the next
                    // loop obtains new evidence. No later cached candidate may jump over this position.
                    driver.serial(this.cursor);
                    ++this.cursor;
                }
            }
            return;
        }

        // Opt-in fence-tail mode: defer unproven serial-fence units to a single serial tail after parallel
        // waves join, and coalesce interleaved parallel invocation kinds (e.g. ExecutionInvocation vs
        // NativeInvocation) into separate full-width waves without breaking on every kind alternation.
        final List<Unit> prefix = new ArrayList<>();
        final int total = this.ordered.size();
        final boolean[] completed = new boolean[total];
        final Unit[] preparedUnits = new Unit[total];
        final it.unimi.dsi.fastutil.ints.IntArrayList prefixIndices = new it.unimi.dsi.fastutil.ints.IntArrayList();
        final it.unimi.dsi.fastutil.ints.IntArrayList deferredFences = new it.unimi.dsi.fastutil.ints.IntArrayList(total);
        while (this.cursor < total) {
            if (completed[this.cursor]) {
                ++this.cursor;
                continue;
            }
            Unit first = preparedUnits[this.cursor];
            if (first != null) {
                preparedUnits[this.cursor] = null;
            } else {
                first = driver.prepare(this.cursor);
            }
            if (first == null) {
                completed[this.cursor] = true;
                deferredFences.add(this.cursor);
                ++this.cursor;
                continue;
            }
            prefix.clear();
            prefixIndices.clear();
            prefix.add(first);
            prefixIndices.add(this.cursor);
            final Class<?> waveUnitClass = first.getClass();
            final int limit = Math.min(RegionBlockEntityWorkGraph.MAX_UNITS, driver.prefixLimit(first));
            if (limit < 1) throw new IllegalStateException("Empty canonical planning window");
            int scan = this.cursor + 1;
            while (scan < total && prefix.size() < limit) {
                if (completed[scan]) {
                    ++scan;
                    continue;
                }
                Unit next = preparedUnits[scan];
                if (next == null) {
                    next = driver.prepare(scan);
                    if (next == null) {
                        completed[scan] = true;
                        deferredFences.add(scan);
                        ++scan;
                        continue;
                    }
                }
                if (!driver.mayAppend(prefix, next)) {
                    preparedUnits[scan] = next;
                    if (driver.maySkipInFenceTail(prefix, next)) {
                        ++scan;
                        continue;
                    }
                    break;
                }
                preparedUnits[scan] = null;
                prefix.add(next);
                prefixIndices.add(scan);
                ++scan;
            }
            final int admitted = driver.executePrefix(prefix);
            if (admitted < 0 || admitted > prefix.size()) throw new IllegalStateException("Noncontiguous execution result");
            for (int i = 0; i < admitted; ++i) {
                completed[prefixIndices.getInt(i)] = true;
            }
            if (admitted == prefix.size()) {
                while (this.cursor < total && completed[this.cursor]) {
                    ++this.cursor;
                }
            } else {
                // Refused partway through the prefix: execute the refused unit serially and roll back any
                // cached preparations or deferred fences recorded after it so they are re-evaluated cleanly.
                final int refusedIndex = prefixIndices.getInt(admitted);
                for (int i = refusedIndex; i < total; ++i) {
                    preparedUnits[i] = null;
                    if (i > refusedIndex) {
                        completed[i] = false;
                    }
                }
                while (!deferredFences.isEmpty() && deferredFences.getInt(deferredFences.size() - 1) > refusedIndex) {
                    deferredFences.popInt();
                }
                driver.serial(refusedIndex);
                completed[refusedIndex] = true;
                while (this.cursor < total && completed[this.cursor]) {
                    ++this.cursor;
                }
            }
        }
        for (int i = 0, size = deferredFences.size(); i < size; ++i) {
            driver.serial(deferredFences.getInt(i));
        }
    }
}
