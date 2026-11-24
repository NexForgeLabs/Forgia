/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package dev.forgia.compat.lootr;

import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.server.threading.api.RegionAdapterApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod("forgia_lootr_compat")
public final class ForgiaLootrCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("forgia_lootr_compat");

    public ForgiaLootrCompat() {
        RegionAdapterApi.admitRegionSandboxedNamespace("lootr");
        LOGGER.info("Forgia Lootr Compat active: synchronized NewTickingData and admitted lootr block entities to Region Sandbox.");
    }
}
