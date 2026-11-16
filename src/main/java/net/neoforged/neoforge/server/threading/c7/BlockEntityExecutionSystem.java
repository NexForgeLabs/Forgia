/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.server.threading.c7;

/** Mutually exclusive canonical BE drivers. C6 remains the default. */
public enum BlockEntityExecutionSystem {
    C6, C7, DISABLED
}
