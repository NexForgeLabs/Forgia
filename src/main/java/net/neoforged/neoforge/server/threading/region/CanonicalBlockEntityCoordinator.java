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
        // waves join, collapsing N+1 barrier rounds into minimal waves at the cost of canonical ordering
        // between fenced and unfenced block entities.
        final List<Unit> prefix = new ArrayList<>();
        final List<Integer> prefixIndices = new ArrayList<>();
        final List<Integer> deferredFences = new ArrayList<>();
        final int total = this.ordered.size();
        while (this.cursor < total) {
            final var first = driver.prepare(this.cursor);
            if (first == null) {
                deferredFences.add(this.cursor);
                ++this.cursor;
                continue;
            }
            prefix.clear();
            prefixIndices.clear();
            prefix.add(first);
            prefixIndices.add(this.cursor);
            final int limit = Math.min(RegionBlockEntityWorkGraph.MAX_UNITS, driver.prefixLimit(first));
            if (limit < 1) throw new IllegalStateException("Empty canonical planning window");
            int scan = this.cursor + 1;
            while (scan < total && prefix.size() < limit) {
                final var next = driver.prepare(scan);
                if (next == null) {
                    deferredFences.add(scan);
                    ++scan;
                    continue;
                }
                if (!driver.mayAppend(prefix, next)) {
                    break;
                }
                prefix.add(next);
                prefixIndices.add(scan);
                ++scan;
            }
            final int admitted = driver.executePrefix(prefix);
            if (admitted < 0 || admitted > prefix.size()) throw new IllegalStateException("Noncontiguous execution result");
            if (admitted == prefix.size()) {
                this.cursor = scan;
            } else {
                // Refused partway through the prefix: execute the refused unit serially and roll back any
                // deferred fences recorded after it so they are re-evaluated cleanly from fresh state.
                final int refusedIndex = prefixIndices.get(admitted);
                while (!deferredFences.isEmpty() && deferredFences.getLast() > refusedIndex) {
                    deferredFences.removeLast();
                }
                driver.serial(refusedIndex);
                this.cursor = refusedIndex + 1;
            }
        }
        for (int i = 0, size = deferredFences.size(); i < size; ++i) {
            driver.serial(deferredFences.get(i));
        }
    }
}
