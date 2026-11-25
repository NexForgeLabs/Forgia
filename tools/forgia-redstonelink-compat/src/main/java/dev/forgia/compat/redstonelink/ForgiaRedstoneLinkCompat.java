/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.redstonelink;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("forgia_redstonelink_compat")
public final class ForgiaRedstoneLinkCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("forgia_redstonelink_compat");

    public ForgiaRedstoneLinkCompat() {
        LOGGER.info("Forgia Redstone Link Compat active: Sable sub-level frequency distance check enabled.");
    }
}
