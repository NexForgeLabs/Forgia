/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.tacz;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("forgia_tacz_compat")
public final class ForgiaTaczCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("forgia_tacz_compat");

    public ForgiaTaczCompat() {
        LOGGER.info("Forgia TaCZ Compat active: thread-safe SyncedEntityData & DataHolder enabled.");
    }
}
