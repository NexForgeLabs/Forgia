/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.api;

import java.util.List;
import java.util.Objects;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Explicitly trusted, audited execution of a complete original ticker. Register through
 * {@link ParallelApi.Registrar#registerBlockEntityExecutionAdapter} after checking
 * BLOCK_ENTITY_EXECUTION_ADAPTERS. Existing predicate/C5 registrations do not acquire this contract.
 *
 * <p>Core binds the actual source/function, verifies the implementation manifest and refreshes the
 * complete inspection on the quiescent coordinator after mailbox delivery. Only then may its original
 * Ticket authorize a body. No adapter callback runs concurrently with bodies in this execution path.
 * The adapter must close every inherited/behaviour/callback branch and control external writers;
 * neither hashes nor resource declarations prove that semantic obligation automatically.
 *
 * <p>RESOURCE_SCOPED supports instance-local roots and explicitly shared READ roots, including independent
 * sources in one region. The complete branch must only modify its declared storage: no world/provider
 * queries, RNG, notifications, events, packets, scheduling or foreign owners. REGION_OWNER retains
 * region exclusion but does not authorize foreign/global resources. Unknown branches stay serial.
 * Started failures are never replayed. All inspection inputs are borrowed and must not be retained.
 */
@ApiStatus.Experimental
public interface BlockEntityExecutionAdapter extends AuditedBlockEntityTickAdapter {
    int PROTOCOL_VERSION = 1;

    /** A complete region owner and a subordinate work scope are different authorities. */
    enum Mode {
        REGION_OWNER, RESOURCE_SCOPED
    }

    /** Frozen during registration; never a grant of ownership. */
    Mode executionMode();

    /**
     * A complete, bounded read-only inspection, on the coordinator only. Default reuses the audited
     * check/footprint methods. Override to include dependency revisions. Each dependency root must also
     * occur in the footprint, so its writers cannot overlap readers after a version check.
     */
    default Inspection inspectExecution(BlockEntity source, Class<?> tickerImplementation) {
        final var decision = Objects.requireNonNull(this.check(source));
        if (decision != Decision.ADMITTED) return Inspection.serial(decision);
        final var proof = this.inspect(source, tickerImplementation);
        return proof == null ? Inspection.serial(Decision.SHARED_RESOURCE) : Inspection.candidate(proof);
    }

    /** Called once after joined work and required commits, even on failure, for each prepared adapter. */
    default void phaseClosed(boolean successful) {}

    record Dependency(Object identity, long revision) {
        public Dependency {
            Objects.requireNonNull(identity);
        }
    }

    record Inspection(@Nullable Proof proof, Decision decision, List<Dependency> dependencies) {
        public Inspection {
            Objects.requireNonNull(decision);
            dependencies = List.copyOf(dependencies);
            if ((proof != null) != (decision == Decision.ADMITTED) || dependencies.size() > 64
                    || proof == null && !dependencies.isEmpty())
                throw new IllegalArgumentException("Invalid execution inspection");
        }

        public static Inspection candidate(Proof proof) {
            return new Inspection(Objects.requireNonNull(proof), Decision.ADMITTED, List.of());
        }

        public static Inspection serial(Decision reason) {
            return new Inspection(null, reason, List.of());
        }
    }
}
