package com.starsector.mapoptimizer.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class OptimizerConfig {
    private final Properties properties = new Properties();

    public final boolean enabled;
    public final boolean retainAll;
    public final boolean scratchCollections;
    public final boolean labelSpatialCandidates;
    public final boolean intelCallbackCache;
    public final boolean intelExistingIconLookup;
    public final boolean intelEntityIndex;
    public final boolean intelFastContains;
    public final boolean hoverHitTestCache;
    public final boolean systemNebulaCache;
    public final boolean sampleCacheClearThrottle;
    public final boolean gridLineCap;
    public final boolean arrowVectorPool;
    public final boolean campaignListenerThrottle;
    public final boolean routeJumpPointIndex;
    public final boolean campaignSnapshotReuse;
    public final boolean entityScriptSnapshotReuse;
    public final boolean emptyMemoryAdvanceFastPath;

    public final int intelMapLocationCacheMs;
    public final int intelArrowDataCacheMs;
    public final int intelEntityIndexTtlMs;
    public final int hoverMaxHz;
    public final float hoverCellPixels;
    public final int hoverCellTtlMs;
    public final int hoverMaxCells;
    public final int labelIndexTtlMs;
    public final int systemNebulaMaxAgeMs;
    public final int sampleCacheClearMinIntervalMs;
    public final int gridMaxLinesPerAxis;
    public final float gridBaseSpacing;
    public final int campaignListenerAuditMs;
    public final int routeIndexTtlMs;
    public final int statsLogIntervalSeconds;

    private OptimizerConfig(Properties p) {
        properties.putAll(p);
        enabled = bool("enabled", true);
        retainAll = bool("patch.retainAll", true);
        scratchCollections = bool("patch.scratchCollections", true);
        labelSpatialCandidates = bool("patch.labelSpatialCandidates", true);
        intelCallbackCache = bool("patch.intelCallbackCache", true);
        intelExistingIconLookup = bool("patch.intelExistingIconLookup", true);
        intelEntityIndex = bool("patch.intelEntityIndex", true);
        intelFastContains = bool("patch.intelFastContains", true);
        hoverHitTestCache = bool("patch.hoverHitTestCache", true);
        systemNebulaCache = bool("patch.systemNebulaCache", true);
        sampleCacheClearThrottle = bool("patch.sampleCacheClearThrottle", true);
        gridLineCap = bool("patch.gridLineCap", true);
        arrowVectorPool = bool("patch.arrowVectorPool", true);
        campaignListenerThrottle = bool("patch.campaignListenerThrottle", true);
        routeJumpPointIndex = bool("patch.routeJumpPointIndex", true);
        campaignSnapshotReuse = bool("patch.campaignSnapshotReuse", true);
        entityScriptSnapshotReuse = bool("patch.entityScriptSnapshotReuse", true);
        emptyMemoryAdvanceFastPath = bool("patch.emptyMemoryAdvanceFastPath", true);

        intelMapLocationCacheMs = integer("intel.mapLocationCacheMs", 120, 0, 10_000);
        intelArrowDataCacheMs = integer("intel.arrowDataCacheMs", 120, 0, 10_000);
        intelEntityIndexTtlMs = integer("intel.entityIndexTtlMs", 500, 0, 60_000);
        hoverMaxHz = integer("hover.maxHz", 60, 0, 1000);
        hoverCellPixels = decimal("hover.cellPixels", 3f, 0f, 100f);
        hoverCellTtlMs = integer("hover.cellTtlMs", 140, 0, 10_000);
        hoverMaxCells = integer("hover.maxCells", 768, 0, 100_000);
        labelIndexTtlMs = integer("label.indexTtlMs", 750, 0, 60_000);
        systemNebulaMaxAgeMs = integer("systemNebula.maxAgeMs", 600_000, 0, 86_400_000);
        sampleCacheClearMinIntervalMs = integer("hyperspace.sampleCacheClearMinIntervalMs", 1500, 0, 60_000);
        gridMaxLinesPerAxis = integer("grid.maxLinesPerAxis", 180, 0, 10_000);
        gridBaseSpacing = decimal("grid.baseSpacing", 2000f, 1f, 1_000_000f);
        campaignListenerAuditMs = integer("campaign.listenerAuditMs", 1000, 0, 60_000);
        routeIndexTtlMs = integer("route.indexTtlMs", 250, 0, 60_000);
        statsLogIntervalSeconds = integer("logging.statsIntervalSeconds", 30, 0, 3600);
    }

    public static OptimizerConfig load(Path file) {
        Properties p = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
                OptimizerLog.info("Loaded configuration: " + file.toAbsolutePath());
            } catch (IOException ex) {
                OptimizerLog.error("Unable to read configuration; defaults will be used: " + file, ex);
            }
        } else {
            OptimizerLog.warn("Configuration file not found; defaults will be used: " + file);
        }
        return new OptimizerConfig(p);
    }

    private boolean bool(String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "1": case "on": return true;
            case "false": case "no": case "0": case "off": return false;
            default:
                OptimizerLog.warn("Invalid boolean for " + key + ": " + value + "; using " + fallback);
                return fallback;
        }
    }

    private int integer(String key, int fallback, int min, int max) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ex) {
            OptimizerLog.warn("Invalid integer for " + key + ": " + value + "; using " + fallback);
            return fallback;
        }
    }

    private float decimal(String key, float fallback, float min, float max) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (!Float.isFinite(parsed)) return fallback;
            return Math.max(min, Math.min(max, parsed));
        } catch (NumberFormatException ex) {
            OptimizerLog.warn("Invalid float for " + key + ": " + value + "; using " + fallback);
            return fallback;
        }
    }
}
