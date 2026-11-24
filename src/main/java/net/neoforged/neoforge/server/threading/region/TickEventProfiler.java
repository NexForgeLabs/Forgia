/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.IEventBus;

/**
 * Lightweight per-listener profiler for {@code LevelTickEvent} and {@code ServerTickEvent}.
 * Has zero overhead when disabled and is toggled via {@code /forgia events start|stop|top|reset}.
 */
public final class TickEventProfiler {
    private static final AtomicBoolean ENABLED = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, ListenerStat> STATS = new ConcurrentHashMap<>();
    private static final LongAdder PROFILED_TICKS = new LongAdder();
    private static final MethodHandle GET_LISTENER_LIST = resolveGetListenerList();

    private TickEventProfiler() {}

    private static MethodHandle resolveGetListenerList() {
        try {
            return MethodHandles.privateLookupIn(EventBus.class, MethodHandles.lookup())
                    .findVirtual(EventBus.class, "getListenerList", MethodType.methodType(ListenerList.class, Class.class));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean isEnabled() {
        return ENABLED.get();
    }

    public static void setEnabled(boolean enabled) {
        ENABLED.set(enabled);
    }

    public static void reset() {
        STATS.clear();
        PROFILED_TICKS.reset();
    }

    public static void countTick() {
        if (ENABLED.get()) {
            PROFILED_TICKS.increment();
        }
    }

    public static <T extends Event> T postTickEvent(IEventBus bus, T event, String eventLabel) {
        if (!ENABLED.get() || GET_LISTENER_LIST == null || !(bus instanceof EventBus eventBus)) {
            return bus.post(event);
        }
        final EventListener[] listeners;
        try {
            final ListenerList list = (ListenerList) GET_LISTENER_LIST.invokeExact(eventBus, (Class<?>) event.getClass());
            listeners = list.getListeners();
        } catch (Throwable ignored) {
            return bus.post(event);
        }
        for (int i = 0; i < listeners.length; i++) {
            final EventListener listener = listeners[i];
            final long start = System.nanoTime();
            try {
                listener.invoke(event);
            } catch (Throwable t) {
                eventBus.handleException(bus, event, listeners, i, t);
                throw t;
            } finally {
                final long elapsed = System.nanoTime() - start;
                final String key = eventLabel + "|" + describeListener(listener);
                STATS.computeIfAbsent(key, k -> new ListenerStat(eventLabel, describeListener(listener)))
                        .record(elapsed);
            }
        }
        return event;
    }

    private static String describeListener(EventListener listener) {
        final String raw = listener.toString();
        if (raw != null && !raw.isEmpty()) {
            // Trim verbose package prefixes or lambda memory addresses if helpful while keeping mod/class identity
            final int slash = raw.indexOf("/0x");
            if (slash > 0) {
                return raw.substring(0, slash);
            }
            return raw;
        }
        return listener.getClass().getName();
    }

    public static Snapshot snapshot(int limit) {
        final List<Entry> entries = new ArrayList<>(STATS.size());
        long totalNanos = 0L;
        for (final Map.Entry<String, ListenerStat> e : STATS.entrySet()) {
            final ListenerStat s = e.getValue();
            final long nanos = s.totalNanos.sum();
            final long calls = s.calls.sum();
            if (calls <= 0) {
                continue;
            }
            totalNanos += nanos;
            entries.add(new Entry(s.eventLabel, s.listenerName, calls, nanos, s.maxNanos.get()));
        }
        entries.sort(Comparator.comparingLong(Entry::totalNanos).reversed());
        final int capped = Math.max(1, Math.min(limit, entries.size()));
        return new Snapshot(ENABLED.get(), PROFILED_TICKS.sum(), totalNanos,
                entries.isEmpty() ? List.of() : List.copyOf(entries.subList(0, capped)));
    }

    private static final class ListenerStat {
        final String eventLabel;
        final String listenerName;
        final LongAdder calls = new LongAdder();
        final LongAdder totalNanos = new LongAdder();
        final AtomicLong maxNanos = new AtomicLong();

        ListenerStat(String eventLabel, String listenerName) {
            this.eventLabel = eventLabel;
            this.listenerName = listenerName;
        }

        void record(long elapsed) {
            this.calls.increment();
            this.totalNanos.add(elapsed);
            this.maxNanos.accumulateAndGet(elapsed, Math::max);
        }
    }

    public record Entry(String event, String listener, long calls, long totalNanos, long maxNanos) {}

    public record Snapshot(boolean enabled, long profiledInvocations, long totalNanos, List<Entry> top) {}
}
