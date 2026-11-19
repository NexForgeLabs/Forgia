package com.example.cleanprobe;

import net.neoforged.fml.common.Mod;

/** Minimal third-party mod with no player event listeners and only a mod-owned mixin target. */
@Mod("clean_mod_probe")
public final class CleanModProbe {
    public CleanModProbe() {
        // Touch CleanModTarget so Mixin applies CleanModTargetMixin during mod init.
        CleanModTarget.value();
    }
}
