package com.starsector.prepatcher.bootstrap;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Sandbox-safe status stub. All file access, reflection and bytecode work is
 * performed by the startup javaagent before Starsector creates mod classloaders.
 */
public final class PrepatcherBootstrapPlugin extends BaseModPlugin {
    private boolean hyperspaceWarningLogged;

    @Override
    public void onApplicationLoad() {
        String version = System.getProperty("starsector.prepatcher.version", "unknown");
        String status = System.getProperty("starsector.prepatcher.status", "javaagent-not-installed");
        String patched = System.getProperty("starsector.prepatcher.patchedClasses", "0");
        String applied = System.getProperty("starsector.prepatcher.appliedPatches", "0");
        String skipped = System.getProperty("starsector.prepatcher.skippedPatches", "0");
        String hyperspaceStatus = System.getProperty(
                "starsector.prepatcher.hyperspaceStatus", "hyperspace-javaagent-not-installed");
        String hyperspaceVersion = System.getProperty("starsector.prepatcher.hyperspaceVersion", "unknown");
        if ("javaagent-not-installed".equals(status)) {
            Global.getLogger(PrepatcherBootstrapPlugin.class).warn(
                    "StarsectorPrepatcher mod is enabled, but its javaagent is not installed. "
                    + "Run install-agent.bat from the mod folder; the game will continue unpatched.");
        } else {
            Global.getLogger(PrepatcherBootstrapPlugin.class).info(
                    "StarsectorPrepatcher " + version + ": status=" + status
                    + ", patchedClassesSoFar=" + patched
                    + ", appliedPatchesSoFar=" + applied
                    + ", skippedPatchesSoFar=" + skipped
                    + ", hyperspaceVersion=" + hyperspaceVersion
                    + ", hyperspaceStatus=" + hyperspaceStatus);
            warnIfHyperspaceInactive(hyperspaceStatus);
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Some terrain targets are loaded after onApplicationLoad(). Recheck here
        // so an exact per-class guard failure is visible in the normal game log.
        warnIfHyperspaceInactive(System.getProperty(
                "starsector.prepatcher.hyperspaceStatus",
                "hyperspace-javaagent-not-installed"));
    }

    private void warnIfHyperspaceInactive(String status) {
        if (hyperspaceWarningLogged || "transformer-installed".equals(status)
                || "disabled-by-config".equals(status)) {
            return;
        }
        hyperspaceWarningLogged = true;
        String detail = System.getProperty("starsector.prepatcher.hyperspaceGuardFailure", "none");
        Global.getLogger(PrepatcherBootstrapPlugin.class).warn(
                "StarsectorPrepatcher hyperspace agent is inactive or incomplete: status=" + status
                + ", detail=" + detail + ". Unknown target bytecode remains vanilla; "
                + "reinstall the agents or verify the supported game build.");
    }
}
