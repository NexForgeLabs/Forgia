/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.jetbrains.annotations.Nullable;

/** Explicit full-board resource, only for untracked exact objectives and original save-data callback. */
final class RegionPlayerScoreboardPlan {
    private final Scoreboard board;
    private final ServerPlayer player;
    private final long revision;
    private final Map<ObjectiveCriteria, List<Objective>> objectives;
    private final Set<Objective> nonempty;
    private final List<Object> shared;

    private RegionPlayerScoreboardPlan(Scoreboard board, ServerPlayer player, Map<ObjectiveCriteria, List<Objective>> objectives, Set<Objective> nonempty) {
        this.board = board;
        this.player = player;
        this.revision = board.forgiaObjectiveRevision();
        this.objectives = Collections.unmodifiableMap(new IdentityHashMap<>(objectives));
        this.nonempty = Set.copyOf(nonempty);
        this.shared = nonempty.isEmpty() ? List.of() : List.copyOf(((ServerScoreboard) board).forgiaPlayerSharedObjects());
    }

    @Nullable
    static RegionPlayerScoreboardPlan capture(ServerPlayer player, Set<ObjectiveCriteria> criteria) {
        Scoreboard board = player.getScoreboard();
        Map<ObjectiveCriteria, List<Objective>> objectives = new IdentityHashMap<>();
        Set<Objective> nonempty = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var criterion : criteria) {
            var values = board.forgiaPlayerObjectives(criterion);
            objectives.put(criterion, values);
            nonempty.addAll(values);
        }
        if (!nonempty.isEmpty() && (board.getClass() != ServerScoreboard.class || !((ServerScoreboard) board).forgiaSupportsPlayerObjectives(nonempty))) return null;
        return new RegionPlayerScoreboardPlan(board, player, objectives, nonempty);
    }

    boolean current() {
        return player.getScoreboard() == board && board.forgiaObjectiveRevision() == revision
                && (nonempty.isEmpty() || ((ServerScoreboard) board).forgiaSupportsPlayerObjectives(nonempty));
    }

    boolean allows(Scoreboard board, ObjectiveCriteria criterion, Object holder) {
        return current() && this.board == board && player == holder && objectives.containsKey(criterion);
    }

    boolean empty(ObjectiveCriteria criterion) {
        return objectives.get(criterion).isEmpty();
    }

    boolean allows(Scoreboard board, Object holder, Objective objective) {
        return current() && this.board == board && player == holder && nonempty.contains(objective);
    }

    List<Object> sharedResources() {
        return shared;
    }
}
