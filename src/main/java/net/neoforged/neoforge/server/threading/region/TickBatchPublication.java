/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.region;

/** Result of the existing lifecycle-gated batch publication; this value grants no execution authority. */
enum TickBatchPublication {
    PUBLISHED,
    LEVEL_CLOSED,
    SHUTDOWN
}
