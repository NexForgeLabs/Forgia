/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.server.threading.api.BlockEntityExecutionContract.Inspection;
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
    }
}
