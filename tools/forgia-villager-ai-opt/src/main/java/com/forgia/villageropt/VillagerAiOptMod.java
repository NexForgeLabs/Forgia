package com.forgia.villageropt;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(value = VillagerAiOptMod.MOD_ID)
public final class VillagerAiOptMod {
    public static final String MOD_ID = "forgia_villager_ai_opt";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public VillagerAiOptMod(IEventBus iEventBus) {
        LOGGER.info("Forgia Villager AI Opt 1.3.0 loaded: Villager Brain throttle + Brainlayers chunk-BE fast lookup + TealLib/ByteStreamTag/Hamster + ChunkMap unload spin-guard.");
    }
}
