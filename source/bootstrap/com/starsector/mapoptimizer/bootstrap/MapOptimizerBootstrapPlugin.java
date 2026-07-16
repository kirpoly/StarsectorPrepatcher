package com.starsector.mapoptimizer.bootstrap;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * Sandbox-safe status stub. All file access, reflection and bytecode work is
 * performed by the startup javaagent before Starsector creates mod classloaders.
 */
public final class MapOptimizerBootstrapPlugin extends BaseModPlugin {
    @Override
    public void onApplicationLoad() {
        String version = System.getProperty("starsector.mapoptimizer.version", "unknown");
        String status = System.getProperty("starsector.mapoptimizer.status", "javaagent-not-installed");
        String patched = System.getProperty("starsector.mapoptimizer.patchedClasses", "0");
        String applied = System.getProperty("starsector.mapoptimizer.appliedPatches", "0");
        String skipped = System.getProperty("starsector.mapoptimizer.skippedPatches", "0");
        if ("javaagent-not-installed".equals(status)) {
            Global.getLogger(MapOptimizerBootstrapPlugin.class).warn(
                    "Starsector Map Optimizer mod is enabled, but its javaagent is not installed. "
                    + "Run install-agent.bat from the mod folder; the game will continue unpatched.");
        } else {
            Global.getLogger(MapOptimizerBootstrapPlugin.class).info(
                    "Starsector Map Optimizer " + version + ": status=" + status
                    + ", patchedClassesSoFar=" + patched
                    + ", appliedPatchesSoFar=" + applied
                    + ", skippedPatchesSoFar=" + skipped);
        }
    }
}
