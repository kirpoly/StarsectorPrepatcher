package com.fs.starfarer.api;

import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.util.Misc;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import com.starsector.prepatcher.agent.PrepatcherLog;
import org.lwjgl.util.vector.Vector2f;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/** Runtime for the structurally matched CoreScript core-worlds extent cache. */
public final class StarsectorPrepatcherCoreWorldsRuntime {
    private static final String MIN_KEY = "$coreWorldsMin";
    private static final String MAX_KEY = "$coreWorldsMax";
    private static final String CENTER_KEY = "$coreWorldsCenter";
    private static final String CORE_TAG = "theme_core";
    private static final Object LOCK = new Object();

    private static volatile boolean enabled;
    private static volatile boolean skipFastForwardIterations;
    private static volatile boolean checkMemoryExpiry;
    private static volatile int validationFrames = 1;

    private static WeakReference<SectorAPI> sectorRef = new WeakReference<>(null);
    private static WeakReference<Vector2f> minRef = new WeakReference<>(null);
    private static WeakReference<Vector2f> maxRef = new WeakReference<>(null);
    private static WeakReference<Vector2f> centerRef = new WeakReference<>(null);
    private static int minXBits;
    private static int minYBits;
    private static int maxXBits;
    private static int maxYBits;
    private static int centerXBits;
    private static int centerYBits;
    private static long outerFrames;
    private static boolean initialized;

    private static final LongAdder CALLS = new LongAdder();
    private static final LongAdder SYSTEM_SCANS = new LongAdder();
    private static final LongAdder SYSTEMS_VISITED = new LongAdder();
    private static final LongAdder FAST_FORWARD_SKIPS = new LongAdder();
    private static final LongAdder FRAME_VALIDATION_SKIPS = new LongAdder();
    private static final LongAdder UNCHANGED_SKIPS = new LongAdder();
    private static final LongAdder PUBLISHES = new LongAdder();
    private static final LongAdder INTEGRITY_REPAIRS = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();

    private StarsectorPrepatcherCoreWorldsRuntime() {}

    static void configure(PrepatcherConfig config) {
        enabled = config != null && config.coreWorldsExtentCache;
        skipFastForwardIterations = config != null
                && config.coreWorldsSkipFastForwardIterations;
        checkMemoryExpiry = config == null || config.coreWorldsCheckMemoryExpiry;
        validationFrames = config == null ? 1 : config.coreWorldsValidationFrames;
        resetForTests();
    }

    /** Called from transformed CoreScript.advance(float). */
    public static void update(SectorAPI sector) {
        CALLS.increment();
        if (!enabled) {
            Misc.computeCoreWorldsExtent();
            return;
        }
        try {
            synchronized (LOCK) {
                updateChecked(sector);
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            synchronized (LOCK) {
                resetState(null);
            }
            FALLBACKS.increment();
            try {
                PrepatcherLog.warn("Core-worlds extent cache failed; using vanilla computation: "
                        + failure.getClass().getName() + ": " + failure.getMessage());
            } catch (Throwable ignored) {
                // Preserve the vanilla fallback even when logging is unavailable.
            }
            Misc.computeCoreWorldsExtent();
        }
    }

    private static void updateChecked(SectorAPI sector) {
        if (sector == null) {
            Misc.computeCoreWorldsExtent();
            return;
        }
        if (sectorRef.get() != sector) {
            resetState(sector);
        }

        MemoryAPI memory = sector.getMemoryWithoutUpdate();
        boolean fastForwardIteration = sector.isFastForwardIteration();
        boolean memoryIntact = initialized && memoryObjectsIntact(memory);
        if (!memoryIntact) {
            if (initialized) INTEGRITY_REPAIRS.increment();
            scanAndPublish(sector, memory, true);
            return;
        }

        if (skipFastForwardIterations && fastForwardIteration) {
            FAST_FORWARD_SKIPS.increment();
            return;
        }

        if (!fastForwardIteration) {
            outerFrames++;
            if (validationFrames > 1 && outerFrames % validationFrames != 0) {
                FRAME_VALIDATION_SKIPS.increment();
                return;
            }
        }

        if (checkMemoryExpiry && hasExpiry(memory)) {
            INTEGRITY_REPAIRS.increment();
            scanAndPublish(sector, memory, true);
            return;
        }
        scanAndPublish(sector, memory, false);
    }

    private static void scanAndPublish(SectorAPI sector, MemoryAPI memory, boolean force) {
        List<StarSystemAPI> systems = sector.getStarSystems();
        float minX = 0f;
        float minY = 0f;
        float maxX = 0f;
        float maxY = 0f;
        int visited = systems == null ? 0 : systems.size();
        if (systems != null) {
            for (StarSystemAPI system : systems) {
                if (system == null || !system.hasTag(CORE_TAG)) continue;
                Vector2f location = system.getLocation();
                minX = Math.min(minX, location.x);
                minY = Math.min(minY, location.y);
                maxX = Math.max(maxX, location.x);
                maxY = Math.max(maxY, location.y);
            }
        }
        float centerX = (minX + maxX) * 0.5f;
        float centerY = (minY + maxY) * 0.5f;
        SYSTEM_SCANS.increment();
        SYSTEMS_VISITED.add(visited);

        int newMinXBits = Float.floatToRawIntBits(minX);
        int newMinYBits = Float.floatToRawIntBits(minY);
        int newMaxXBits = Float.floatToRawIntBits(maxX);
        int newMaxYBits = Float.floatToRawIntBits(maxY);
        int newCenterXBits = Float.floatToRawIntBits(centerX);
        int newCenterYBits = Float.floatToRawIntBits(centerY);
        boolean changed = !initialized
                || minXBits != newMinXBits || minYBits != newMinYBits
                || maxXBits != newMaxXBits || maxYBits != newMaxYBits
                || centerXBits != newCenterXBits || centerYBits != newCenterYBits;
        if (!force && !changed) {
            UNCHANGED_SKIPS.increment();
            return;
        }

        Vector2f min = new Vector2f(minX, minY);
        Vector2f max = new Vector2f(maxX, maxY);
        Vector2f center = new Vector2f(centerX, centerY);
        memory.set(MIN_KEY, min);
        memory.set(MAX_KEY, max);
        memory.set(CENTER_KEY, center);

        minRef = new WeakReference<>(min);
        maxRef = new WeakReference<>(max);
        centerRef = new WeakReference<>(center);
        minXBits = newMinXBits;
        minYBits = newMinYBits;
        maxXBits = newMaxXBits;
        maxYBits = newMaxYBits;
        centerXBits = newCenterXBits;
        centerYBits = newCenterYBits;
        initialized = true;
        PUBLISHES.increment();
    }

    private static boolean memoryObjectsIntact(MemoryAPI memory) {
        return vectorIntact(memory.get(MIN_KEY), minRef.get(), minXBits, minYBits)
                && vectorIntact(memory.get(MAX_KEY), maxRef.get(), maxXBits, maxYBits)
                && vectorIntact(memory.get(CENTER_KEY), centerRef.get(),
                        centerXBits, centerYBits);
    }

    private static boolean vectorIntact(Object actual, Vector2f expected,
                                        int expectedXBits, int expectedYBits) {
        if (actual != expected || !(actual instanceof Vector2f vector)) return false;
        return Float.floatToRawIntBits(vector.x) == expectedXBits
                && Float.floatToRawIntBits(vector.y) == expectedYBits;
    }

    private static boolean hasExpiry(MemoryAPI memory) {
        return memory.getExpire(MIN_KEY) != -1f
                || memory.getExpire(MAX_KEY) != -1f
                || memory.getExpire(CENTER_KEY) != -1f;
    }

    private static void resetState(SectorAPI sector) {
        sectorRef = new WeakReference<>(sector);
        minRef = new WeakReference<>(null);
        maxRef = new WeakReference<>(null);
        centerRef = new WeakReference<>(null);
        minXBits = minYBits = maxXBits = maxYBits = centerXBits = centerYBits = 0;
        outerFrames = 0;
        initialized = false;
    }

    public static void resetForTests() {
        synchronized (LOCK) {
            resetState(null);
        }
    }

    static String statsAndResetFragment() {
        return ", coreWorldsCalls=" + CALLS.sumThenReset()
                + ", coreWorldsSystemScans=" + SYSTEM_SCANS.sumThenReset()
                + ", coreWorldsSystemsVisited=" + SYSTEMS_VISITED.sumThenReset()
                + ", coreWorldsFastForwardSkips=" + FAST_FORWARD_SKIPS.sumThenReset()
                + ", coreWorldsFrameValidationSkips="
                + FRAME_VALIDATION_SKIPS.sumThenReset()
                + ", coreWorldsUnchangedSkips=" + UNCHANGED_SKIPS.sumThenReset()
                + ", coreWorldsPublishes=" + PUBLISHES.sumThenReset()
                + ", coreWorldsIntegrityRepairs=" + INTEGRITY_REPAIRS.sumThenReset()
                + ", coreWorldsFallbacks=" + FALLBACKS.sumThenReset();
    }
}
