/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.neoforged.neoforge.server.threading.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/** Contiguous-prefix conflict planning only: no pool, reordered fences, or body execution. */
@ApiStatus.Internal
public final class RegionPlayerConflictPlan {
    public enum Conflict {
        SAME_REGION, HALO_INTERSECTION, SHARED_TARGET, SHARED_RESOURCE
    }

    /** A null footprint is an original serial fence. Entity references are the original objects. */
    public record Entry(Entity player, RegionCoordinate home, @Nullable RegionPlayerFootprint footprint, List<Entity> targets, List<Object> sharedResources) {
        public Entry {
            targets = List.copyOf(targets);
            sharedResources = List.copyOf(sharedResources);
        }

        public Entry(Entity player, RegionCoordinate home, @Nullable RegionPlayerFootprint footprint, List<Entity> targets) {
            this(player, home, footprint, targets, List.of());
        }
    }

    public record Group(List<Entry> entries, List<RegionCoordinate> regions, Set<Conflict> reasons) {
        public Group {
            entries = List.copyOf(entries);
            regions = List.copyOf(regions);
            reasons = Set.copyOf(reasons);
        }
    }

    public record Prefix(List<Group> groups, int nextIndex, boolean oversizedConflict) {
        public Prefix {
            groups = List.copyOf(groups);
        }
    }

    private RegionPlayerConflictPlan() {}

    /** Caller joins this prefix, executes the original fence, then CAPTURES the suffix again. */
    public static Prefix prefix(List<Entry> ordered) {
        if (ordered.isEmpty()) return new Prefix(List.of(), 0, false);
        int size = 0;
        var level = ordered.getFirst().home.level();
        for (Entry entry : ordered) {
            if (entry.footprint == null || entry.home.level() != level || entry.footprint.level() != level) break;
            size++;
        }
        return groups(ordered, size, false);
    }

    private static Prefix groups(List<Entry> ordered, int size, boolean oversized) {
        int[] parent = new int[size];
        for (int i = 0; i < size; i++) parent[i] = i;
        if (size <= 4) {
            for (int i = 0; i < size; i++) for (int j = 0; j < i; j++) {
                if (conflict(ordered.get(i), ordered.get(j)) != null) parent[root(parent, i)] = root(parent, j);
            }
        } else {
            // Every object/coordinate induces a clique. Linking to its first occurrence gives
            // exactly the same components without examining every pair of independent players.
            Map<RegionCoordinate, Integer> homes = new HashMap<>();
            Map<RegionCoordinate, Integer> regions = new HashMap<>();
            Map<Entity, Integer> players = new IdentityHashMap<>();
            Map<Entity, Integer> targets = new IdentityHashMap<>();
            Map<Object, Integer> resources = new IdentityHashMap<>();
            for (int i = 0; i < size; i++) {
                Entry entry = ordered.get(i);
                connect(parent, i, players, entry.player);
                connect(parent, i, homes, entry.home);
                for (var region : entry.footprint.regions()) connect(parent, i, regions, region);
                for (var target : entry.targets) connect(parent, i, targets, target);
                for (var resource : entry.sharedResources) connect(parent, i, resources, resource);
            }
        }
        Map<Integer, List<Entry>> members = new LinkedHashMap<>();
        Map<Integer, Integer> first = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            int key = root(parent, i);
            members.computeIfAbsent(key, ignored -> new ArrayList<>()).add(ordered.get(i));
            first.putIfAbsent(key, i);
        }
        List<Group> result = new ArrayList<>();
        for (var component : members.entrySet()) {
            Set<RegionCoordinate> regions = new LinkedHashSet<>();
            Set<Conflict> reasons = new LinkedHashSet<>();
            List<Entry> entries = component.getValue();
            for (int i = 0; i < entries.size(); i++) {
                regions.addAll(entries.get(i).footprint.regions());
                // The exact pairwise diagnostic is needed only within a conflict component.
                for (int j = 0; j < i; j++) {
                    Conflict reason = conflict(entries.get(i), entries.get(j));
                    if (reason != null) reasons.add(reason);
                }
            }
            if (regions.size() > RegionPlayerFootprint.MAX_REGIONS) {
                // No member of the oversized component starts. Earlier independent entries remain
                // eligible, and the caller performs a complete serial fence at its FIRST member.
                return groups(ordered, first.get(component.getKey()), true);
            }
            List<RegionCoordinate> canonical = new ArrayList<>(regions);
            canonical.sort(RegionLeasePack.CANONICAL_ORDER);
            result.add(new Group(entries, canonical, reasons));
        }
        return new Prefix(result, size, oversized);
    }

    private static <T> void connect(int[] parent, int index, Map<T, Integer> first, T key) {
        Integer other = first.putIfAbsent(key, index);
        if (other != null) parent[root(parent, index)] = root(parent, other);
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    @Nullable
    private static Conflict conflict(Entry a, Entry b) {
        if (a.player == b.player || a.home.equals(b.home)) return Conflict.SAME_REGION;
        if (sharesIdentity(a.targets, b.targets)) return Conflict.SHARED_TARGET;
        if (sharesIdentity(a.sharedResources, b.sharedResources)) return Conflict.SHARED_RESOURCE;
        for (RegionCoordinate region : a.footprint.regions()) if (b.footprint.regions().contains(region)) return Conflict.HALO_INTERSECTION;
        return null;
    }

    private static boolean sharesIdentity(List<?> a, List<?> b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if ((long) a.size() * b.size() <= 64) {
            for (Object x : a) for (Object y : b) if (x == y) return true;
        } else {
            List<?> shorter = a.size() <= b.size() ? a : b;
            List<?> longer = shorter == a ? b : a;
            Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>(shorter.size()));
            identities.addAll(shorter);
            for (Object object : longer) if (identities.contains(object)) return true;
        }
        return false;
    }
}
