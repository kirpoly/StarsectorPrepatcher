package com.starsector.prepatcher.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

public final class PrepatcherConfig {
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
    public final boolean economyLocationCache;
    public final boolean economySnapshotReuse;
    public final boolean economyPersistentSnapshots;
    public final boolean commodityEventModDirtyCache;
    public final boolean remoteMarketScheduler;
    public final boolean planetConditionMarketScheduler;
    public final boolean directMarketObservation;
    public final boolean commodityTemporalFastPath;
    public final boolean marketNoOpCallbacks;
    public final boolean tempModExpiryScheduler;
    public final boolean commRelaySystemIndex;
    public final boolean shipAdvanceScratch;
    public final boolean particleCleanup;
    public final boolean loadingTextReader;
    public final boolean startupLogAggregation;
    public final boolean rulesLiteralParser;
    public final boolean saveLoadProgressThrottle;
    public final boolean saveOutputBufferDedup;
    public final boolean hyperspaceCulling;
    public final boolean hyperspaceYClamp;
    public final boolean skipNoOpTerrainLayer;
    public final boolean terrainRandomReuse;
    public final boolean automatonBufferReuse;
    public final boolean starfieldCleanupBuffers;
    public final boolean starfieldLinearRemoval;
    public final boolean fastForwardEnabled;
    public final boolean fastForwardFrameMarker;
    public final boolean fastForwardActionIndicators;
    public final boolean fastForwardLocationVisuals;
    public final boolean fastForwardFloatingText;
    public final boolean fastForwardFleetView;
    public final boolean fastForwardFleetPresentation;
    public final boolean fastForwardSensorIndicators;
    public final boolean fastForwardCelestialVisuals;
    public final boolean fastForwardAuroraAnimation;
    public final boolean fastForwardContinuousSound;
    public final boolean fastForwardGateJitter;
    public final boolean fastForwardGlobalAnimations;
    public final boolean fastForwardSensorFaders;
    public final boolean fastForwardSlipstreamParticles;
    public final boolean fastForwardParticleEmitters;
    public final boolean fastForwardGuardJar;
    public final boolean fastForwardVerbose;
    public final boolean fastForwardMetrics;
    public final boolean fastForwardVisualTimeSimulation;
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
    public final int commRelayIndexTtlMs;
    public final int remoteMarketFrames;
    public final int remoteMarketHiddenFrames;
    public final int remoteMarketMaxDeferredFrames;
    public final float remoteMarketMaxDeferredGameDays;
    public final boolean remoteMarketHotCurrentLocation;
    public final boolean remoteMarketHotPlayerOwned;
    public final boolean remoteMarketHotInteraction;
    public final int remoteMarketPolicyAuditFrames;
    public final String remoteMarketFullRateMemoryKey;
    public final int planetConditionMarketFrames;
    public final int planetConditionMarketCurrentLocationFrames;
    public final int planetConditionMarketMaxDeferredFrames;
    public final float planetConditionMarketMaxDeferredGameDays;
    public final int planetConditionMarketPolicyAuditFrames;
    public final String planetConditionMarketFullRateMemoryKey;
    public final int directMarketTimingSampleEvery;
    public final int directMarketStackSampleEvery;
    public final int directMarketMaxStacksPerSite;
    public final int directMarketReportIntervalSeconds;
    public final int directMarketMaxSites;
    public final int directMarketUnknownStackSamples;
    public final int economyStructureAuditMs;
    public final int marketStructureAuditFrames;
    public final int commodityTemporalAuditFrames;
    public final int marketNoOpIndustryAuditFrames;
    public final int statsLogIntervalSeconds;
    public final int saveLoadProgressHz;
    public final int starfieldRemoveAllThreshold;

    private PrepatcherConfig(Properties p) {
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
        economyLocationCache = bool("patch.economyLocationCache", true);
        economySnapshotReuse = bool("patch.economySnapshotReuse", true);
        economyPersistentSnapshots = bool("patch.economyPersistentSnapshots", false);
        commodityEventModDirtyCache = bool("patch.commodityEventModDirtyCache", false);
        remoteMarketScheduler = bool("patch.remoteMarketScheduler", false);
        planetConditionMarketScheduler = bool("patch.planetConditionMarketScheduler", false);
        directMarketObservation = bool("patch.directMarketObservation", false);
        commodityTemporalFastPath = bool("patch.commodityTemporalFastPath", false);
        marketNoOpCallbacks = bool("patch.marketNoOpCallbacks", false);
        tempModExpiryScheduler = bool("patch.tempModExpiryScheduler", false);
        commRelaySystemIndex = bool("patch.commRelaySystemIndex", true);
        shipAdvanceScratch = bool("patch.shipAdvanceScratch", true);
        particleCleanup = bool("patch.particleCleanup", true);
        loadingTextReader = bool("patch.loadingTextReader", false);
        startupLogAggregation = bool("patch.startupLogAggregation", false);
        rulesLiteralParser = bool("patch.rulesLiteralParser", true);
        saveLoadProgressThrottle = bool("patch.saveLoadProgressThrottle", true);
        saveOutputBufferDedup = bool("patch.saveOutputBufferDedup", true);
        hyperspaceCulling = bool("patch.hyperspaceCulling", true);
        hyperspaceYClamp = bool("patch.hyperspaceYClamp", true);
        skipNoOpTerrainLayer = bool("patch.skipNoOpTerrainLayer", true);
        terrainRandomReuse = bool("patch.terrainRandomReuse", true);
        automatonBufferReuse = bool("patch.automatonBufferReuse", true);
        starfieldCleanupBuffers = bool("patch.starfieldCleanupBuffers", true);
        starfieldLinearRemoval = bool("patch.starfieldLinearRemoval", true);
        fastForwardEnabled = bool("patch.fastForwardPresentation", true);
        fastForwardFrameMarker = bool("patch.fastForwardFrameMarker", true);
        fastForwardActionIndicators = bool("patch.fastForwardActionIndicators", true);
        fastForwardLocationVisuals = bool("patch.fastForwardLocationVisuals", true);
        fastForwardFloatingText = bool("patch.fastForwardFloatingText", true);
        fastForwardFleetView = bool("patch.fastForwardFleetView", true);
        fastForwardFleetPresentation = bool("patch.fastForwardFleetPresentation", true);
        fastForwardSensorIndicators = bool("patch.fastForwardSensorIndicators", true);
        fastForwardCelestialVisuals = bool("patch.fastForwardCelestialVisuals", true);
        fastForwardAuroraAnimation = bool("patch.fastForwardAuroraAnimation", true);
        fastForwardContinuousSound = bool("patch.fastForwardContinuousSound", true);
        fastForwardGateJitter = bool("patch.fastForwardGateJitter", true);
        fastForwardGlobalAnimations = bool("patch.fastForwardGlobalAnimations", false);
        fastForwardSensorFaders = bool("patch.fastForwardSensorFaders", false);
        fastForwardSlipstreamParticles = bool("patch.fastForwardSlipstreamParticles", false);
        fastForwardParticleEmitters = bool("patch.fastForwardParticleEmitters", false);
        fastForwardGuardJar = bool("fastForward.guardJar", true);
        fastForwardVerbose = bool("fastForward.verbose", true);
        fastForwardMetrics = bool("fastForward.metrics", false);
        String fastForwardVisualTime = string("fastForward.visualTime", "realtime")
                .toLowerCase(Locale.ROOT);
        if (!fastForwardVisualTime.equals("realtime")
                && !fastForwardVisualTime.equals("simulation")) {
            PrepatcherLog.warn("Invalid fastForward.visualTime: " + fastForwardVisualTime
                    + "; using realtime");
            fastForwardVisualTime = "realtime";
        }
        fastForwardVisualTimeSimulation = fastForwardVisualTime.equals("simulation");
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
        commRelayIndexTtlMs = integer("commRelay.indexTtlMs", 250, 0, 60_000);
        remoteMarketFrames = integer("market.remote.frames", 4, 1, 120);
        remoteMarketHiddenFrames = integer("market.remote.hiddenFrames", 8, 1, 240);
        remoteMarketMaxDeferredFrames = integer("market.remote.maxDeferredFrames", 8, 1, 240);
        remoteMarketMaxDeferredGameDays = decimal(
                "market.remote.maxDeferredGameDays", 0.02f, 0f, 30f);
        remoteMarketHotCurrentLocation = bool("market.hot.currentLocation", true);
        remoteMarketHotPlayerOwned = bool("market.hot.playerOwned", true);
        remoteMarketHotInteraction = bool("market.hot.interaction", true);
        remoteMarketPolicyAuditFrames = integer(
                "market.remote.policyAuditFrames", 60, 1, 36_000);
        remoteMarketFullRateMemoryKey = string(
                "market.remote.fullRateMemoryKey", "$starsectorPrepatcher_fullRateMarket");
        planetConditionMarketFrames = integer(
                "market.planetCondition.frames", 4, 1, 240);
        planetConditionMarketCurrentLocationFrames = integer(
                "market.planetCondition.currentLocationFrames", 1, 1, 240);
        planetConditionMarketMaxDeferredFrames = integer(
                "market.planetCondition.maxDeferredFrames", 8, 1, 240);
        planetConditionMarketMaxDeferredGameDays = decimal(
                "market.planetCondition.maxDeferredGameDays", 0.02f, 0f, 30f);
        planetConditionMarketPolicyAuditFrames = integer(
                "market.planetCondition.policyAuditFrames", 60, 1, 36_000);
        planetConditionMarketFullRateMemoryKey = string(
                "market.planetCondition.fullRateMemoryKey",
                "$starsectorPrepatcher_fullRateMarket");
        directMarketTimingSampleEvery = integer(
                "directMarket.timingSampleEvery", 128, 0, 1_000_000);
        directMarketStackSampleEvery = integer(
                "directMarket.stackSampleEvery", 2048, 0, 10_000_000);
        directMarketMaxStacksPerSite = integer(
                "directMarket.maxStacksPerSite", 8, 0, 128);
        directMarketReportIntervalSeconds = integer(
                "directMarket.reportIntervalSeconds", 15, 1, 3600);
        directMarketMaxSites = integer(
                "directMarket.maxSites", 4096, 16, 65_536);
        directMarketUnknownStackSamples = integer(
                "directMarket.unknownStackSamples", 32, 0, 4096);
        economyStructureAuditMs = integer("economy.structureAuditMs", 1000, 0, 60_000);
        marketStructureAuditFrames = integer("market.structureAuditFrames", 120, 1, 100_000);
        commodityTemporalAuditFrames = integer("commodity.temporalAuditFrames", 60, 1, 100_000);
        marketNoOpIndustryAuditFrames = integer("market.noOpIndustryAuditFrames", 120, 0, 100_000);
        statsLogIntervalSeconds = integer("logging.statsIntervalSeconds", 30, 0, 3600);
        saveLoadProgressHz = integer("saveLoad.progressHz", 15, 0, 240);
        starfieldRemoveAllThreshold = integer("starfield.removeAllThreshold", 8, 1, 4096);
    }

    public static PrepatcherConfig load(Path file) {
        Properties p = new Properties();
        if (file != null && Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
                PrepatcherLog.info("Loaded configuration: " + file.toAbsolutePath());
            } catch (IOException ex) {
                PrepatcherLog.error("Unable to read configuration; defaults will be used: " + file, ex);
            }
        } else {
            PrepatcherLog.warn("Configuration file not found; defaults will be used: " + file);
        }
        return new PrepatcherConfig(p);
    }

    private boolean bool(String key, boolean fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "1": case "on": return true;
            case "false": case "no": case "0": case "off": return false;
            default:
                PrepatcherLog.warn("Invalid boolean for " + key + ": " + value + "; using " + fallback);
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
            PrepatcherLog.warn("Invalid integer for " + key + ": " + value + "; using " + fallback);
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
            PrepatcherLog.warn("Invalid float for " + key + ": " + value + "; using " + fallback);
            return fallback;
        }
    }

    private String string(String key, String fallback) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        return value.trim();
    }
}
