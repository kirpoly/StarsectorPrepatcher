package com.starsector.mapoptimizer.runtime;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.JumpPointAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.impl.campaign.procgen.Constellation;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.starsector.mapoptimizer.agent.OptimizerConfig;
import com.starsector.mapoptimizer.agent.OptimizerLog;
import org.lwjgl.util.vector.Vector2f;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.RandomAccess;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Runtime entry points called by transformed Starsector classes. */
public final class MapOptimizerHooks {
    private static volatile OptimizerConfig config;
    private static volatile Path modRoot;

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);
    private static volatile Map<IntelInfoPlugin, IntelCache> INTEL_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> LABEL_INDEXES =
            new IdentityHashMap<>();
    private static volatile IdentityHashMap<List<?>, IntelEntityIndex> INTEL_ENTITY_INDEXES =
            new IdentityHashMap<>();
    private static volatile IdentityHashMap<Object, HitCache> HIT_CACHES = new IdentityHashMap<>();
    private static volatile WeakHashMap<Object, Long> SAMPLE_CLEAR_TIMES = new WeakHashMap<>();
    private static volatile Map<Object, CampaignListenerState> CAMPAIGN_LISTENER_STATES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Map<LocationAPI, RouteJumpIndex> ROUTE_JUMP_INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile Map<Object, RouteSystemIndex> ROUTE_SYSTEM_INDEXES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Object CAMPAIGN_CACHE_LIFECYCLE_LOCK = new Object();

    /**
     * The engine reference must itself remain weak: lifecycle hooks are a cleanup
     * trigger, not a new process-lifetime owner of campaign state.
     */
    private static volatile WeakReference<Object> activeCampaignEngine = new WeakReference<>(null);
    private static volatile boolean campaignEngineObserved;
    private static volatile boolean campaignCacheGenerationActive;
    private static volatile long campaignCacheGeneration;
    private static final long NO_CAMPAIGN_TRANSITION = -1L;
    private static long pendingCampaignTransition = NO_CAMPAIGN_TRANSITION;
    private static WeakReference<Object> pendingCampaignEngine = new WeakReference<>(null);

    private static final LongAdder RETAIN_CALLS = new LongAdder();
    private static final LongAdder RETAIN_UPPER_BOUND = new LongAdder();
    private static final LongAdder RETAIN_KEYS = new LongAdder();
    private static final LongAdder RETAIN_REMOVED = new LongAdder();
    private static final LongAdder RETAIN_EQUALITY_FALLBACKS = new LongAdder();
    private static final LongAdder HOVER_HITS = new LongAdder();
    private static final LongAdder HOVER_MISSES = new LongAdder();
    private static final LongAdder MAP_LOCATION_HITS = new LongAdder();
    private static final LongAdder MAP_LOCATION_MISSES = new LongAdder();
    private static final LongAdder ARROW_HITS = new LongAdder();
    private static final LongAdder ARROW_MISSES = new LongAdder();
    private static final LongAdder INTEL_INDEX_HITS = new LongAdder();
    private static final LongAdder INTEL_INDEX_BUILDS = new LongAdder();
    private static final LongAdder LABEL_TOTAL = new LongAdder();
    private static final LongAdder LABEL_CANDIDATES = new LongAdder();
    private static final LongAdder NEBULA_HITS = new LongAdder();
    private static final LongAdder NEBULA_MISSES = new LongAdder();
    private static final LongAdder SAMPLE_CLEAR_SKIPS = new LongAdder();
    private static final LongAdder CAMPAIGN_LISTENER_RUNS = new LongAdder();
    private static final LongAdder CAMPAIGN_LISTENER_SKIPS = new LongAdder();
    private static final LongAdder ROUTE_JUMP_INDEX_HITS = new LongAdder();
    private static final LongAdder ROUTE_JUMP_INDEX_BUILDS = new LongAdder();
    private static final LongAdder ROUTE_JUMP_INDEX_FALLBACKS = new LongAdder();
    private static final LongAdder ROUTE_SYSTEM_INDEX_HITS = new LongAdder();
    private static final LongAdder ROUTE_SYSTEM_INDEX_BUILDS = new LongAdder();
    private static final LongAdder ROUTE_SYSTEM_INDEX_FALLBACKS = new LongAdder();
    private static final LongAdder CAMPAIGN_CACHE_RESETS = new LongAdder();

    private static volatile NebulaCacheSlot nebulaCacheSlot = new NebulaCacheSlot();
    private static volatile float cachedGridSpacing = -1f;
    private static volatile float cachedGridWidth = Float.NaN;
    private static volatile float cachedGridHeight = Float.NaN;

    private MapOptimizerHooks() {}

    public static void configure(OptimizerConfig optimizerConfig, Path root) {
        config = optimizerConfig;
        modRoot = root;
        if (optimizerConfig.statsLogIntervalSeconds > 0) {
            Thread stats = new Thread(MapOptimizerHooks::statsLoop, "StarsectorMapOptimizer-Stats");
            stats.setDaemon(true);
            stats.setPriority(Thread.MIN_PRIORITY);
            stats.start();
        }
    }

    // ---------------------------------------------------------------------
    // Campaign cache lifecycle
    // ---------------------------------------------------------------------

    /**
     * Closes the current cache generation immediately before CampaignEngine
     * publishes its structurally verified singleton-field write.
     *
     * All caches below are useful only inside one campaign generation. Several
     * values intentionally contain locations, entities or UI objects that lead
     * back to ObjectRepository.listener and therefore to CampaignEngine. Weak
     * keys cannot break that value-to-key cycle, so changing the active engine is
     * the authoritative eviction boundary.
     *
     * The lifecycle monitor is deliberately released before old roots are
     * detached. Detachment swaps volatile map and nebula-slot references instead
     * of acquiring their monitors: cache builders may call mod/game code, so
     * cross-monitor clearing would permit an ABBA deadlock during a re-entrant
     * reset. This hook is identity-idempotent and deliberately noexcept.
     *
     * @return a transition token for {@link #completeCampaignEngineChange}, or a
     * negative value when the requested identity is already active
     */
    public static long beginCampaignEngineChange(Object engine) {
        long transition = NO_CAMPAIGN_TRANSITION;
        try {
            synchronized (CAMPAIGN_CACHE_LIFECYCLE_LOCK) {
                Object observed = activeCampaignEngine.get();
                if (pendingCampaignTransition == NO_CAMPAIGN_TRANSITION
                        && campaignEngineObserved && observed == engine
                        && campaignCacheGenerationActive == (engine != null)) {
                    return NO_CAMPAIGN_TRANSITION;
                }
                // Close the generation before eviction so no new cache path can
                // enter while the old state is being detached.
                campaignCacheGenerationActive = false;
                transition = ++campaignCacheGeneration;
                pendingCampaignTransition = transition;
                pendingCampaignEngine = new WeakReference<>(engine);
                CAMPAIGN_CACHE_RESETS.increment();
            }
            clearCampaignCaches();
            return transition;
        } catch (Throwable ignored) {
            // CampaignEngine lifecycle semantics always take precedence over an
            // optional optimization cache. Returning the no-op token leaves all
            // stateful caches disabled if cleanup could not finish.
            return NO_CAMPAIGN_TRANSITION;
        }
    }

    /**
     * Activates a successfully published campaign only after setInstance() has
     * completed its Global/Factory updates and other normal-return work. A stale,
     * mismatched or failed transition remains safely disabled.
     */
    public static void completeCampaignEngineChange(Object engine, long transition) {
        boolean clearMismatchedGeneration = false;
        try {
            synchronized (CAMPAIGN_CACHE_LIFECYCLE_LOCK) {
                boolean noOpMatches = transition == NO_CAMPAIGN_TRANSITION
                        && pendingCampaignTransition == NO_CAMPAIGN_TRANSITION
                        && campaignEngineObserved && activeCampaignEngine.get() == engine
                        && campaignCacheGenerationActive == (engine != null);
                if (noOpMatches) return;
                if (transition != NO_CAMPAIGN_TRANSITION
                        && pendingCampaignTransition == transition
                        && pendingCampaignEngine.get() == engine) {
                    activeCampaignEngine = new WeakReference<>(engine);
                    campaignEngineObserved = true;
                    pendingCampaignTransition = NO_CAMPAIGN_TRANSITION;
                    pendingCampaignEngine = new WeakReference<>(null);
                    // resetInstance(null) intentionally leaves caches inactive until
                    // the next successfully completed non-null setInstance().
                    campaignCacheGenerationActive = engine != null;
                    return;
                }

                // Concurrent/out-of-order lifecycle calls are not expected from
                // the game, but must still fail closed: never leave cache identity
                // active for an engine that may no longer be the singleton.
                campaignCacheGenerationActive = false;
                campaignCacheGeneration++;
                activeCampaignEngine = new WeakReference<>(null);
                campaignEngineObserved = false;
                pendingCampaignTransition = NO_CAMPAIGN_TRANSITION;
                pendingCampaignEngine = new WeakReference<>(null);
                CAMPAIGN_CACHE_RESETS.increment();
                clearMismatchedGeneration = true;
            }
        } catch (Throwable ignored) {
            // Fail closed: the generation remains inactive and hooks use vanilla.
        }
        if (clearMismatchedGeneration) clearCampaignCaches();
    }

    private static void clearCampaignCaches() {
        // Detach each process-lifetime root instead of waiting for its monitor.
        // In-flight calls retain only their local old map/slot; epoch checks stop
        // them from publishing into a current generation, and the detached roots
        // become collectible when those calls end.
        INTEL_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
        LABEL_INDEXES = new IdentityHashMap<>();
        INTEL_ENTITY_INDEXES = new IdentityHashMap<>();
        HIT_CACHES = new IdentityHashMap<>();
        SAMPLE_CLEAR_TIMES = new WeakHashMap<>();
        CAMPAIGN_LISTENER_STATES = Collections.synchronizedMap(new WeakHashMap<>());
        ROUTE_JUMP_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
        ROUTE_SYSTEM_INDEXES = Collections.synchronizedMap(new WeakHashMap<>());
        nebulaCacheSlot = new NebulaCacheSlot();
        // Lifecycle callbacks run on the campaign/UI thread. Scratch frames are
        // normally empty already; remove the ThreadLocal entry as a final guard.
        SCRATCH.remove();
    }

    private static boolean campaignCachesReady(long generation) {
        return campaignCacheGenerationActive && campaignCacheGeneration == generation
                && activeCampaignEngine.get() != null;
    }

    private static boolean campaignCachesReady(long generation, Object engine) {
        return campaignCacheGenerationActive && campaignCacheGeneration == generation
                && activeCampaignEngine.get() == engine;
    }

    // ---------------------------------------------------------------------
    // Map reconciliation and scratch collection reuse
    // ---------------------------------------------------------------------

    /** Opens a re-entrancy-safe lease for pooled values used by one transformed method. */
    public static void beginScratchScope() {
        Scratch scratch = SCRATCH.get();
        int next = scratch.scopeDepth;
        scratch.frameAt(next);
        scratch.scopeDepth = next + 1;
    }

    /**
     * Closes the current lease. This hook is deliberately noexcept and idempotent:
     * it is also called by a generated catch-all handler.
     */
    public static void endScratchScope() {
        try {
            Scratch scratch = SCRATCH.get();
            if (scratch.scopeDepth <= 0) return;
            int index = --scratch.scopeDepth;
            scratch.frameAt(index).clear();
        } catch (Throwable ignored) {
            // Never mask an original return value or exception.
        }
    }

    /**
     * Replaces the exact H.renderStuff() reconciliation call.
     *
     * Vanilla invokes LinkedHashMap.keySet().retainAll(ArrayList), which scans the
     * ArrayList once for every icon key. The map keys are concrete campaign-entity
     * instances; all vanilla 0.98a-RC8 CampaignEntity implementations inherit
     * Object.equals/hashCode, so identity membership is the native domain semantic.
     *
     * A reusable IdentityHashMap avoids entity hashCode implementations for vanilla
     * entities and the explicit iterator avoids the generic AbstractCollection path.
     * Modded entity classes overriding equals/hashCode use a reusable HashSet so the
     * original Java collection semantics are retained.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean retainAllFast(Set target, Collection keep, Object mapOwner) {
        OptimizerConfig c = config;
        if (c == null || !c.retainAll) {
            return target.retainAll(keep);
        }

        ScratchFrame scratch = SCRATCH.get().scopeFrame();
        IdentityHashMap<Object, Boolean> membership = scratch.identityMembership;
        membership.clear();
        boolean customEquality = false;
        if (keep instanceof List list) {
            for (int i = 0, size = list.size(); i < size; i++) {
                Object entity = list.get(i);
                membership.put(entity, Boolean.TRUE);
                if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) customEquality = true;
            }
        } else {
            for (Object entity : keep) {
                membership.put(entity, Boolean.TRUE);
                if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) customEquality = true;
            }
        }

        if (!customEquality) {
            for (Object entity : target) {
                if (entity != null && CUSTOM_EQUALITY.get(entity.getClass())) {
                    customEquality = true;
                    break;
                }
            }
        }

        Set<Object> equalityMembership = null;
        if (customEquality) {
            // Preserve equals/hashCode semantics for modded entity classes that
            // override Object equality. Vanilla entities stay on the faster
            // identity path. LinkedHashMap keys already require a valid hashCode
            // contract, so HashSet membership matches normal Java collection rules.
            equalityMembership = scratch.equalityMembership;
            equalityMembership.clear();
            equalityMembership.addAll(keep);
            RETAIN_EQUALITY_FALLBACKS.increment();
        }

        RETAIN_CALLS.increment();
        RETAIN_UPPER_BOUND.add((long) target.size() * (long) keep.size());
        RETAIN_KEYS.add(target.size());

        boolean changed = false;
        long removed = 0L;
        try {
            Iterator iterator = target.iterator();
            while (iterator.hasNext()) {
                Object entity = iterator.next();
                boolean present = customEquality
                        ? equalityMembership.contains(entity)
                        : membership.containsKey(entity);
                if (!present) {
                    iterator.remove();
                    changed = true;
                    removed++;
                }
            }
            if (removed != 0L) RETAIN_REMOVED.add(removed);
            return changed;
        } finally {
            membership.clear();
            if (equalityMembership != null) equalityMembership.clear();
        }
    }

    private static final ClassValue<Boolean> CUSTOM_EQUALITY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                Method equals = type.getMethod("equals", Object.class);
                Method hashCode = type.getMethod("hashCode");
                return equals.getDeclaringClass() != Object.class
                        || hashCode.getDeclaringClass() != Object.class;
            } catch (ReflectiveOperationException | SecurityException ex) {
                // Unknown/custom class shape: prefer collection semantics over the
                // identity optimization.
                return Boolean.TRUE;
            }
        }
    };

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowEntityList(Collection source) {
        OptimizerConfig c = config;
        if (c == null || !c.scratchCollections) return new ArrayList(source);
        ArrayList list = SCRATCH.get().scopeFrame().entityList;
        list.clear();
        list.addAll(source);
        return list;
    }

    /**
     * Reuses the defensive snapshots taken by BaseLocation.advance(). The
     * returned object is deliberately exposed only as List: transformed sites
     * are structurally proven to iterate it without mutation or escape.
     * Collection.toArray(T[]) preserves the vanilla point-in-time copy while
     * reusing the backing array after the first capacity warm-up.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List borrowLocationAdvanceSnapshot(Collection source) {
        OptimizerConfig c = config;
        if (c == null || !c.campaignSnapshotReuse) return new ArrayList(source);
        return borrowCampaignSnapshot(source);
    }

    /** Same snapshot contract for BaseLocation.advanceEvenIfPaused(). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List borrowPausedLocationSnapshot(Collection source) {
        OptimizerConfig c = config;
        if (c == null || !c.campaignSnapshotReuse) return new ArrayList(source);
        return borrowCampaignSnapshot(source);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List borrowCampaignSnapshot(Collection source) {
        ScratchFrame frame = SCRATCH.get().scopeFrame();
        SnapshotList snapshot = frame.campaignSnapshotAt(frame.campaignSnapshotIndex++);
        snapshot.copyFrom(source);
        return snapshot;
    }

    /**
     * Reuses BaseCampaignEntity.runScripts()' defensive copy. Empty script
     * lists use the JDK's allocation-free immutable empty list; non-empty lists
     * keep the same point-in-time iteration semantics as the vanilla copy.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List borrowEntityScriptSnapshot(Collection source) {
        OptimizerConfig c = config;
        if (c == null || !c.entityScriptSnapshotReuse) return new ArrayList(source);
        if (source.isEmpty()) return Collections.emptyList();
        return borrowCampaignSnapshot(source);
    }

    /** Avoids allocating ArrayList.Itr for the overwhelmingly common empty Memory.expire list. */
    @SuppressWarnings("rawtypes")
    public static Iterator memoryExpireIterator(List source) {
        OptimizerConfig c = config;
        if (c != null && c.emptyMemoryAdvanceFastPath && source.isEmpty()) {
            return Collections.emptyIterator();
        }
        return source.iterator();
    }

    /** Avoids allocating LinkedHashMap.ValueIterator for an empty Memory.require view. */
    @SuppressWarnings("rawtypes")
    public static Iterator memoryRequireIterator(Collection source) {
        OptimizerConfig c = config;
        if (c != null && c.emptyMemoryAdvanceFastPath && source.isEmpty()) {
            return Collections.emptyIterator();
        }
        return source.iterator();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static HashSet borrowClassSet() {
        OptimizerConfig c = config;
        if (c == null || !c.scratchCollections) return new HashSet();
        HashSet set = SCRATCH.get().scopeFrame().classSet;
        set.clear();
        return set;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static ArrayList borrowHitList(Collection source) {
        OptimizerConfig c = config;
        if (c == null || !c.scratchCollections) return new ArrayList(source);
        ArrayList list = SCRATCH.get().scopeFrame().hitList;
        list.clear();
        list.addAll(source);
        return list;
    }

    public static Vector2f borrowHitPoint(float x, float y) {
        OptimizerConfig c = config;
        if (c == null || !c.scratchCollections) return new Vector2f(x, y);
        Vector2f vector = SCRATCH.get().scopeFrame().hitPoint;
        vector.set(x, y);
        return vector;
    }

    public static Vector2f borrowArrowVector(float x, float y) {
        OptimizerConfig c = config;
        if (c == null || !c.arrowVectorPool) return new Vector2f(x, y);
        ScratchFrame scratch = SCRATCH.get().scopeFrame();
        Vector2f vector = scratch.arrowVectors[scratch.arrowVectorIndex++ & (scratch.arrowVectors.length - 1)];
        vector.set(x, y);
        return vector;
    }

    // ---------------------------------------------------------------------
    // Label candidate spatial index. Original method still performs exact
    // visibility and distance checks; this only narrows the candidate set.
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Collection nearbyLabelIcons(LinkedHashMap icons, Object mapOwner, Object targetIcon) {
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.labelSpatialCandidates || !campaignCachesReady(generation)
                || icons.size() < 64) {
            return icons.values();
        }
        try {
            LabelIndex index;
            long now = System.nanoTime();
            IdentityHashMap<LinkedHashMap<?, ?>, LabelIndex> labelIndexes = LABEL_INDEXES;
            synchronized (labelIndexes) {
                if (!campaignCachesReady(generation)) return icons.values();
                index = labelIndexes.get(icons);
                long ttlNs = c.labelIndexTtlMs * 1_000_000L;
                if (index == null || index.size != icons.size()
                        || (ttlNs > 0L && now - index.builtAtNanos > ttlNs)) {
                    index = LabelIndex.build(icons, now);
                    if (!campaignCachesReady(generation)) return icons.values();
                    if (labelIndexes.size() >= 32 && !labelIndexes.containsKey(icons)) {
                        Iterator<LinkedHashMap<?, ?>> iterator = labelIndexes.keySet().iterator();
                        if (iterator.hasNext()) {
                            iterator.next();
                            iterator.remove();
                        }
                    }
                    labelIndexes.put(icons, index);
                }
                if (!campaignCachesReady(generation)) {
                    labelIndexes.clear();
                    return icons.values();
                }
            }
            SectorEntityToken token = iconToken(targetIcon);
            if (token == null || token.getLocation() == null || index.failed) return icons.values();
            Vector2f location = token.getLocation();
            int cx = floorCell(location.x, LabelIndex.CELL_X);
            int cy = floorCell(location.y, LabelIndex.CELL_Y);
            ArrayList<Object> result = SCRATCH.get().scopeFrame().labelCandidates;
            result.clear();
            result.addAll(index.fallback);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    List<Object> bucket = index.buckets.get(cellKey(cx + dx, cy + dy));
                    if (bucket != null) result.addAll(bucket);
                }
            }
            LABEL_TOTAL.add(icons.size());
            LABEL_CANDIDATES.add(result.size());
            return result;
        } catch (Throwable ex) {
            return icons.values();
        }
    }

    private static SectorEntityToken iconToken(Object icon) throws Exception {
        Method method = IconAccess.TOKEN_METHODS.get(icon.getClass());
        return (SectorEntityToken) method.invoke(icon);
    }

    private static final class IconAccess {
        private static final ClassValue<Method> TOKEN_METHODS = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                Class<?> current = type;
                while (current != null) {
                    try {
                        Method method = current.getDeclaredMethod("int");
                        method.setAccessible(true);
                        return method;
                    } catch (NoSuchMethodException ignored) {
                        current = current.getSuperclass();
                    }
                }
                throw new IllegalStateException("Unable to find map-icon token accessor for " + type.getName());
            }
        };
    }

    private static final class LabelIndex {
        static final float CELL_X = 10_000f;
        static final float CELL_Y = 3_000f;
        final int size;
        final long builtAtNanos;
        final Map<Long, List<Object>> buckets;
        final List<Object> fallback;
        final boolean failed;

        private LabelIndex(int size, long builtAtNanos, Map<Long, List<Object>> buckets,
                           List<Object> fallback, boolean failed) {
            this.size = size;
            this.builtAtNanos = builtAtNanos;
            this.buckets = buckets;
            this.fallback = fallback;
            this.failed = failed;
        }

        static LabelIndex build(LinkedHashMap<?, ?> icons, long now) {
            Map<Long, List<Object>> buckets = new HashMap<>();
            List<Object> fallback = new ArrayList<>();
            try {
                for (Object icon : icons.values()) {
                    try {
                        SectorEntityToken token = iconToken(icon);
                        Vector2f location = token == null ? null : token.getLocation();
                        if (location == null || !Float.isFinite(location.x) || !Float.isFinite(location.y)) {
                            fallback.add(icon);
                            continue;
                        }
                        int cx = floorCell(location.x, CELL_X);
                        int cy = floorCell(location.y, CELL_Y);
                        buckets.computeIfAbsent(cellKey(cx, cy), ignored -> new ArrayList<>()).add(icon);
                    } catch (Throwable ex) {
                        fallback.add(icon);
                    }
                }
                return new LabelIndex(icons.size(), now, buckets, fallback, false);
            } catch (ConcurrentModificationException ex) {
                return new LabelIndex(icons.size(), now, Collections.emptyMap(), new ArrayList<>(), true);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Intel callbacks
    // ---------------------------------------------------------------------

    public static SectorEntityToken getMapLocationCached(IntelInfoPlugin plugin, SectorMapAPI map) {
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.intelCallbackCache || !campaignCachesReady(generation)
                || c.intelMapLocationCacheMs <= 0) {
            return plugin.getMapLocation(map);
        }
        long now = System.nanoTime();
        long ttl = c.intelMapLocationCacheMs * 1_000_000L;
        IntelCache entry;
        Map<IntelInfoPlugin, IntelCache> intelCache = INTEL_CACHE;
        synchronized (intelCache) {
            if (!campaignCachesReady(generation)) {
                entry = null;
            } else {
                entry = intelCache.computeIfAbsent(plugin, ignored -> new IntelCache());
                // WeakHashMap key operations may invoke user-defined hashCode.
                // A re-entrant lifecycle change must not leave that late insert.
                if (!campaignCachesReady(generation)) {
                    intelCache.clear();
                    entry = null;
                }
            }
            if (entry != null && entry.mapForLocation == map && entry.locationValid
                    && now - entry.locationAtNanos <= ttl) {
                MAP_LOCATION_HITS.increment();
                return entry.location;
            }
        }
        if (entry == null) return plugin.getMapLocation(map);
        SectorEntityToken value = plugin.getMapLocation(map);
        synchronized (intelCache) {
            entry.mapForLocation = map;
            entry.location = value;
            entry.locationAtNanos = now;
            entry.locationValid = true;
        }
        MAP_LOCATION_MISSES.increment();
        return value;
    }

    @SuppressWarnings("rawtypes")
    public static List getArrowDataCached(IntelInfoPlugin plugin, SectorMapAPI map) {
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.intelCallbackCache || !campaignCachesReady(generation)
                || c.intelArrowDataCacheMs <= 0) {
            return plugin.getArrowData(map);
        }
        long now = System.nanoTime();
        long ttl = c.intelArrowDataCacheMs * 1_000_000L;
        IntelCache entry;
        Map<IntelInfoPlugin, IntelCache> intelCache = INTEL_CACHE;
        synchronized (intelCache) {
            if (!campaignCachesReady(generation)) {
                entry = null;
            } else {
                entry = intelCache.computeIfAbsent(plugin, ignored -> new IntelCache());
                if (!campaignCachesReady(generation)) {
                    intelCache.clear();
                    entry = null;
                }
            }
            if (entry != null && entry.mapForArrows == map && entry.arrowsValid
                    && now - entry.arrowsAtNanos <= ttl) {
                ARROW_HITS.increment();
                return entry.arrows;
            }
        }
        if (entry == null) return plugin.getArrowData(map);
        List value = plugin.getArrowData(map);
        synchronized (intelCache) {
            entry.mapForArrows = map;
            entry.arrows = value;
            entry.arrowsAtNanos = now;
            entry.arrowsValid = true;
        }
        ARROW_MISSES.increment();
        return value;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean fastContains(Collection collection, Object value) {
        OptimizerConfig c = config;
        if (c == null || !c.intelFastContains || collection.size() < 16) {
            return collection.contains(value);
        }
        ScratchFrame scratch = SCRATCH.get().scopeFrame();
        if (scratch.containsSource != collection || scratch.containsSourceSize != collection.size()) {
            scratch.containsSet.clear();
            scratch.containsSet.addAll(collection);
            scratch.containsSource = collection;
            scratch.containsSourceSize = collection.size();
        }
        return scratch.containsSet.contains(value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Collection existingIntelIconCandidates(LinkedHashMap icons, Object entity) {
        Object direct = icons.get(entity);
        if (direct != null) return Collections.singletonList(direct);
        return icons.values();
    }

    /** Identity index for H.getIntelIconEntity(), avoiding an O(M) scan per Intel item. */
    @SuppressWarnings("rawtypes")
    public static Object getIntelIconEntityIndexed(Object owner, List intelEntities, IntelInfoPlugin plugin) {
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.intelEntityIndex || !campaignCachesReady(generation)
                || intelEntities == null || plugin == null) {
            return invokeOriginalIntelIconLookup(owner, plugin);
        }
        try {
            long now = System.nanoTime();
            Object first = intelEntities.isEmpty() ? null : intelEntities.get(0);
            Object last = intelEntities.isEmpty() ? null : intelEntities.get(intelEntities.size() - 1);
            IntelEntityIndex index;
            IdentityHashMap<List<?>, IntelEntityIndex> intelEntityIndexes = INTEL_ENTITY_INDEXES;
            synchronized (intelEntityIndexes) {
                if (!campaignCachesReady(generation)) {
                    index = null;
                } else {
                    index = intelEntityIndexes.get(intelEntities);
                    long ttlNs = c.intelEntityIndexTtlMs * 1_000_000L;
                    if (index == null || index.size != intelEntities.size()
                            || index.first != first || index.last != last
                            || (ttlNs > 0L && now - index.builtAtNanos > ttlNs)) {
                        index = IntelEntityIndex.build(intelEntities, first, last, now);
                        if (!campaignCachesReady(generation)) {
                            index = null;
                        } else {
                            if (intelEntityIndexes.size() >= 32
                                    && !intelEntityIndexes.containsKey(intelEntities)) {
                                Iterator<List<?>> iterator = intelEntityIndexes.keySet().iterator();
                                if (iterator.hasNext()) {
                                    iterator.next();
                                    iterator.remove();
                                }
                            }
                            intelEntityIndexes.put(intelEntities, index);
                            INTEL_INDEX_BUILDS.increment();
                        }
                    }
                    if (!campaignCachesReady(generation)) {
                        intelEntityIndexes.clear();
                        index = null;
                    }
                }
            }
            if (index != null && !index.failed) {
                INTEL_INDEX_HITS.increment();
                return index.byPlugin.get(plugin);
            }
        } catch (Throwable ignored) {
            // Fall through to the original method. Keep that invocation outside
            // this catch so an exception from game/mod code is never retried.
        }
        return invokeOriginalIntelIconLookup(owner, plugin);
    }

    private static Object invokeOriginalIntelIconLookup(Object owner, IntelInfoPlugin plugin) {
        if (owner == null) return null;
        try {
            return IntelEntityAccess.ORIGINAL.get(owner.getClass()).invoke(owner, plugin);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke original Starsector Intel entity lookup", ex);
        }
    }

    private static final class IntelEntityAccess {
        static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method method = findDeclaredMethod(type,
                            "smo$originalGetIntelIconEntity", IntelInfoPlugin.class);
                    method.setAccessible(true);
                    return method;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };

        static final ClassValue<Field> PLUGIN_FIELD = new ClassValue<>() {
            @Override
            protected Field computeValue(Class<?> type) {
                Field result = null;
                Class<?> current = type;
                while (current != null) {
                    for (Field field : current.getDeclaredFields()) {
                        if (IntelInfoPlugin.class.isAssignableFrom(field.getType())) {
                            if (result != null) {
                                throw new IllegalStateException("Ambiguous IntelInfoPlugin fields in "
                                        + type.getName());
                            }
                            result = field;
                        }
                    }
                    current = current.getSuperclass();
                }
                if (result == null) {
                    throw new IllegalStateException("No IntelInfoPlugin field in " + type.getName());
                }
                result.setAccessible(true);
                return result;
            }
        };
    }

    private static final class IntelEntityIndex {
        final int size;
        final Object first;
        final Object last;
        final long builtAtNanos;
        final IdentityHashMap<IntelInfoPlugin, Object> byPlugin;
        final boolean failed;

        IntelEntityIndex(int size, Object first, Object last, long builtAtNanos,
                         IdentityHashMap<IntelInfoPlugin, Object> byPlugin, boolean failed) {
            this.size = size;
            this.first = first;
            this.last = last;
            this.builtAtNanos = builtAtNanos;
            this.byPlugin = byPlugin;
            this.failed = failed;
        }

        @SuppressWarnings("rawtypes")
        static IntelEntityIndex build(List entities, Object first, Object last, long now) {
            IdentityHashMap<IntelInfoPlugin, Object> byPlugin = new IdentityHashMap<>();
            try {
                for (Object entity : entities) {
                    if (!(entity instanceof SectorEntityToken token)) continue;
                    Object data = token.getCustomData().get("intelIconData");
                    if (data == null) continue;
                    Field pluginField = IntelEntityAccess.PLUGIN_FIELD.get(data.getClass());
                    Object value = pluginField.get(data);
                    if (value instanceof IntelInfoPlugin plugin) byPlugin.put(plugin, entity);
                }
                return new IntelEntityIndex(entities.size(), first, last, now, byPlugin, false);
            } catch (ConcurrentModificationException ex) {
                return new IntelEntityIndex(entities.size(), first, last, now,
                        new IdentityHashMap<>(), true);
            } catch (Throwable ex) {
                return new IntelEntityIndex(entities.size(), first, last, now,
                        new IdentityHashMap<>(), true);
            }
        }
    }

    private static final class IntelCache {
        SectorMapAPI mapForLocation;
        SectorEntityToken location;
        long locationAtNanos;
        boolean locationValid;
        SectorMapAPI mapForArrows;
        List<?> arrows;
        long arrowsAtNanos;
        boolean arrowsValid;
    }

    // ---------------------------------------------------------------------
    // Hover / hit-test cache. The exact vanilla hit-test remains the source
    // of truth on misses; results are reused for a short time/cell.
    // ---------------------------------------------------------------------

    public static SectorEntityToken hitTestCached(Object handler, float x, float y, float radius) {
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.hoverHitTestCache || !campaignCachesReady(generation)) {
            return invokeOriginalHitTest(handler, x, y, radius);
        }

        HitCache cache = null;
        long now = 0L;
        long cellKey = 0L;
        boolean cacheCell = false;
        try {
            HitAccess access = HitAccess.ACCESS.get(handler.getClass());
            Object map = access.mapField.get(handler);
            float factor = ((Number) access.getFactor.invoke(map)).floatValue();
            Object icons = access.getIcons.invoke(map);
            int iconCount = icons instanceof Map ? ((Map<?, ?>) icons).size() : -1;
            Object location = access.getLocation.invoke(map);
            now = System.nanoTime();
            IdentityHashMap<Object, HitCache> hitCaches = HIT_CACHES;
            synchronized (hitCaches) {
                if (campaignCachesReady(generation)) {
                    cache = hitCaches.computeIfAbsent(
                            handler, ignored -> new HitCache(c.hoverMaxCells));
                    if (hitCaches.size() > 64) {
                        Iterator<Object> iterator = hitCaches.keySet().iterator();
                        while (iterator.hasNext() && hitCaches.size() > 64) {
                            Object candidate = iterator.next();
                            if (candidate != handler) iterator.remove();
                        }
                    }
                }
                if (!campaignCachesReady(generation)) {
                    hitCaches.clear();
                    cache = null;
                }
            }
            if (cache != null) {
                if (cache.location != location || cache.iconCount != iconCount
                        || Float.floatToIntBits(cache.factor) != Float.floatToIntBits(factor)) {
                    cache.reset(location, iconCount, factor);
                }
                long minInterval = c.hoverMaxHz <= 0 ? 0L : 1_000_000_000L / c.hoverMaxHz;
                if (cache.hasLast && minInterval > 0L && now - cache.lastAtNanos < minInterval) {
                    HOVER_HITS.increment();
                    return cache.lastResult;
                }
                if (c.hoverCellPixels > 0f && c.hoverCellTtlMs > 0 && c.hoverMaxCells > 0
                        && Float.isFinite(factor) && factor > 0f) {
                    int cellX = safeFloorToInt((x * factor) / c.hoverCellPixels);
                    int cellY = safeFloorToInt((y * factor) / c.hoverCellPixels);
                    cellKey = cellKey(cellX, cellY)
                            ^ ((long) Float.floatToIntBits(radius) * 0x9E3779B97F4A7C15L);
                    CellHit hit = cache.cells.get(cellKey);
                    if (hit != null && now - hit.atNanos <= c.hoverCellTtlMs * 1_000_000L) {
                        cache.setLast(hit.value, now);
                        HOVER_HITS.increment();
                        return hit.value;
                    }
                    cacheCell = true;
                }
            }
        } catch (Throwable ignored) {
            // Cache setup is optional. Invoke the original once, below, outside
            // the catch boundary so its exception/side effects stay exact.
            cache = null;
            cacheCell = false;
        }

        SectorEntityToken value = invokeOriginalHitTest(handler, x, y, radius);
        if (cache != null) {
            try {
                if (cacheCell) cache.cells.put(cellKey, new CellHit(value, now));
                cache.setLast(value, now);
                HOVER_MISSES.increment();
            } catch (Throwable ignored) {
                // Returning the already-computed original result is always safe.
            }
        }
        return value;
    }

    private static SectorEntityToken invokeOriginalHitTest(Object handler, float x, float y, float radius) {
        try {
            Method original = HitOriginalAccess.ORIGINAL.get(handler.getClass());
            return (SectorEntityToken) original.invoke(handler, x, y, radius);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke original Starsector map hit-test", ex);
        }
    }

    private static final class HitOriginalAccess {
        static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method original = findDeclaredMethod(type,
                            "smo$originalHitTest", float.class, float.class, float.class);
                    original.setAccessible(true);
                    return original;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private static final class HitAccess {
        static final ClassValue<HitAccess> ACCESS = new ClassValue<>() {
            @Override
            protected HitAccess computeValue(Class<?> type) {
                try {
                    Field mapField = findUniqueFieldByTypeName(type,
                            "com.fs.starfarer.coreui.A.H", "H map field");
                    mapField.setAccessible(true);
                    Class<?> mapType = mapField.getType();
                    Method getFactor = mapType.getMethod("getFactor");
                    Method getIcons = mapType.getMethod("getIcons");
                    Method getLocation = mapType.getMethod("getLocation");
                    return new HitAccess(mapField, getFactor, getIcons, getLocation);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };

        final Field mapField;
        final Method getFactor;
        final Method getIcons;
        final Method getLocation;

        HitAccess(Field mapField, Method getFactor, Method getIcons, Method getLocation) {
            this.mapField = mapField;
            this.getFactor = getFactor;
            this.getIcons = getIcons;
            this.getLocation = getLocation;
        }
    }

    private static final class HitCache {
        Object location;
        int iconCount = Integer.MIN_VALUE;
        float factor = Float.NaN;
        boolean hasLast;
        SectorEntityToken lastResult;
        long lastAtNanos;
        final LinkedHashMap<Long, CellHit> cells;

        HitCache(int maxEntries) {
            final int limit = Math.max(1, maxEntries);
            cells = new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CellHit> eldest) {
                    return size() > limit;
                }
            };
        }

        void reset(Object newLocation, int newIconCount, float newFactor) {
            location = newLocation;
            iconCount = newIconCount;
            factor = newFactor;
            hasLast = false;
            lastResult = null;
            lastAtNanos = 0L;
            cells.clear();
        }

        void setLast(SectorEntityToken result, long now) {
            hasLast = true;
            lastResult = result;
            lastAtNanos = now;
        }
    }

    private static final class CellHit {
        final SectorEntityToken value;
        final long atNanos;
        CellHit(SectorEntityToken value, long atNanos) {
            this.value = value;
            this.atNanos = atNanos;
        }
    }

    // ---------------------------------------------------------------------
    // Map-open preprocessing caches
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void updateSystemNebulasCached(Object owner, List systemNebulas,
                                                  List constellationLabels, List nebulaStars) {
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.systemNebulaCache || !campaignCachesReady(generation)) {
            invokeOriginalSystemNebulaBuilder(owner);
            return;
        }
        systemNebulas.clear();
        constellationLabels.clear();
        nebulaStars.clear();
        Throwable cacheFailure = null;
        try {
            SectorAPI sector = Global.getSector();
            if (sector != null) {
                NebulaCache cache = getOrBuildNebulaCache(sector, c, generation);
                if (cache != null) {
                    SystemNebulaAccess access = SystemNebulaAccess.ACCESS.get(owner.getClass());
                    for (int i = 0; i < cache.systemNebulaSystems.size(); i++) {
                        StarSystemAPI system = cache.systemNebulaSystems.get(i);
                        Object terrain = access.createNebula.invoke(null, system);
                        if (!(terrain instanceof SectorEntityToken token)) {
                            throw new IllegalStateException("createNebula returned " + terrain);
                        }
                        token.getCustomData().put("system", system);
                        token.getCustomData().put("seed", Long.valueOf(cache.systemNebulaSeeds[i]));
                        systemNebulas.add(terrain);
                    }
                    for (Constellation constellation : cache.constellations) {
                        Object label = access.createConstellationLabel.invoke(null, constellation);
                        if (!(label instanceof SectorEntityToken token)) {
                            throw new IllegalStateException("createConstellationLabel returned " + label);
                        }
                        token.getCustomData().put("constellationLabel", Boolean.TRUE);
                        token.getCustomData().put("constellation", constellation);
                        constellationLabels.add(label);
                    }
                    nebulaStars.addAll(cache.nebulaStars);
                    NEBULA_HITS.increment();
                    return;
                }
            }
        } catch (Throwable ex) {
            cacheFailure = ex;
        }

        systemNebulas.clear();
        constellationLabels.clear();
        nebulaStars.clear();
        if (cacheFailure != null) {
            OptimizerLog.warn("System-nebula metadata cache failed; using vanilla builder: "
                    + cacheFailure.getClass().getSimpleName() + ": " + cacheFailure.getMessage());
        }
        // Never place the original call inside the compatibility catch: if game
        // or mod code throws, it must be observed after exactly one invocation.
        invokeOriginalSystemNebulaBuilder(owner);
    }

    private static NebulaCache getOrBuildNebulaCache(SectorAPI sector, OptimizerConfig c,
                                                      long generation) {
        int systems = sector.getStarSystems().size();
        long now = System.nanoTime();
        NebulaCacheSlot slot = nebulaCacheSlot;
        NebulaCache current = slot.value;
        if (campaignCachesReady(generation)
                && nebulaCacheSlot == slot
                && current != null && current.sector.get() == sector && current.systemCount == systems
                && (c.systemNebulaMaxAgeMs <= 0
                    || now - current.createdAtNanos <= c.systemNebulaMaxAgeMs * 1_000_000L)) {
            return current;
        }
        synchronized (slot) {
            if (nebulaCacheSlot != slot || !campaignCachesReady(generation)) return null;
            current = slot.value;
            if (current != null && current.sector.get() == sector && current.systemCount == systems
                    && (c.systemNebulaMaxAgeMs <= 0
                        || now - current.createdAtNanos <= c.systemNebulaMaxAgeMs * 1_000_000L)) {
                return current;
            }
            NebulaCache built = buildNebulaMetadata(sector, now);
            if (nebulaCacheSlot != slot || !campaignCachesReady(generation)) return null;
            slot.value = built;
            // A lifecycle transition may run between the pre-publication check
            // and assignment. It swaps the static slot without waiting here, so
            // this write can then reach only the detached slot. Clear it eagerly
            // and refuse the stale result if either identity check changed.
            if (nebulaCacheSlot != slot || !campaignCachesReady(generation)) {
                slot.value = null;
                return null;
            }
            NEBULA_MISSES.increment();
            return built;
        }
    }

    private static NebulaCache buildNebulaMetadata(SectorAPI sector, long now) {
        long baseSeed = 0L;
        String seedString = sector.getSeedString();
        if (seedString != null && seedString.length() > 3) {
            baseSeed = Long.parseLong(seedString.substring(3));
        }
        Random random = Misc.getRandom(baseSeed, 10);
        ArrayList<StarSystemAPI> nebulaSystems = new ArrayList<>();
        ArrayList<Long> seeds = new ArrayList<>();
        HashSet<Constellation> constellationSet = new HashSet<>();
        ArrayList<SectorEntityToken> nebulaStars = new ArrayList<>();
        for (StarSystemAPI system : sector.getStarSystems()) {
            if (Boolean.TRUE.equals(system.hasSystemwideNebula()) && system.getAge() != null) {
                nebulaSystems.add(system);
                seeds.add(random.nextLong());
            }
            if (system.isInConstellation()) {
                Constellation constellation = system.getConstellation();
                if (constellation != null) constellationSet.add(constellation);
            }
            if (system.getType() == StarSystemGenerator.StarSystemType.NEBULA
                    && system.getStar() != null
                    && system.getStar().getSpec().isNebulaCenter()) {
                nebulaStars.add(system.getStar());
            }
        }
        long[] seedArray = new long[seeds.size()];
        for (int i = 0; i < seeds.size(); i++) seedArray[i] = seeds.get(i);
        return new NebulaCache(new WeakReference<>(sector), sector.getStarSystems().size(), now,
                nebulaSystems, seedArray, new ArrayList<>(constellationSet), nebulaStars);
    }

    private static void invokeOriginalSystemNebulaBuilder(Object owner) {
        try {
            SystemNebulaOriginalAccess.ORIGINAL.get(owner.getClass()).invoke(owner);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke original Starsector system-nebula builder", ex);
        }
    }

    private static final class SystemNebulaOriginalAccess {
        static final ClassValue<Method> ORIGINAL = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method original = findDeclaredMethod(type, "smo$originalUpdateSystemNebulas");
                    original.setAccessible(true);
                    return original;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };
    }

    private static final class SystemNebulaAccess {
        static final ClassValue<SystemNebulaAccess> ACCESS = new ClassValue<>() {
            @Override
            protected SystemNebulaAccess computeValue(Class<?> type) {
                try {
                    Method createNebula = type.getMethod("createNebula", StarSystemAPI.class);
                    Method createConstellationLabel = type.getMethod("createConstellationLabel", Constellation.class);
                    return new SystemNebulaAccess(createNebula, createConstellationLabel);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        };

        final Method createNebula;
        final Method createConstellationLabel;

        SystemNebulaAccess(Method createNebula, Method createConstellationLabel) {
            this.createNebula = createNebula;
            this.createConstellationLabel = createConstellationLabel;
        }
    }

    public static void forceClearSampleCacheThrottled(Object plugin) {
        if (plugin == null) return;
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.sampleCacheClearThrottle || !campaignCachesReady(generation)
                || c.sampleCacheClearMinIntervalMs <= 0) {
            ((BaseTiledTerrain) plugin).forceClearSampleCache();
            return;
        }
        long now = System.nanoTime();
        long interval = c.sampleCacheClearMinIntervalMs * 1_000_000L;
        WeakHashMap<Object, Long> sampleClearTimes = SAMPLE_CLEAR_TIMES;
        synchronized (sampleClearTimes) {
            if (!campaignCachesReady(generation)) {
                // Fall through to the exact vanilla clear below.
            } else {
                Long previous = sampleClearTimes.get(plugin);
                if (campaignCachesReady(generation)
                        && previous != null && now - previous < interval) {
                    SAMPLE_CLEAR_SKIPS.increment();
                    return;
                }
                if (campaignCachesReady(generation)) {
                    sampleClearTimes.put(plugin, now);
                    if (!campaignCachesReady(generation)) {
                        // Protect against a re-entrant reset from custom key code.
                        sampleClearTimes.clear();
                    }
                }
            }
        }
        // A generation change only disables this optional throttle. The exact
        // vanilla clear still runs, preserving all behavior and side effects.
        ((BaseTiledTerrain) plugin).forceClearSampleCache();
    }

    public static float gridSpacing() {
        OptimizerConfig c = config;
        if (c == null || !c.gridLineCap || c.gridMaxLinesPerAxis <= 0) {
            return c == null ? 2000f : c.gridBaseSpacing;
        }
        try {
            float width = Math.abs(Global.getSettings().getFloat("sectorWidth"));
            float height = Math.abs(Global.getSettings().getFloat("sectorHeight"));
            if (Float.floatToIntBits(width) == Float.floatToIntBits(cachedGridWidth)
                    && Float.floatToIntBits(height) == Float.floatToIntBits(cachedGridHeight)
                    && cachedGridSpacing > 0f) {
                return cachedGridSpacing;
            }
            float base = c.gridBaseSpacing;
            float maxDimension = Math.max(width, height);
            float lines = maxDimension / base;
            int multiplier = Math.max(1, (int) Math.ceil(lines / c.gridMaxLinesPerAxis));
            float spacing = base * multiplier;
            cachedGridWidth = width;
            cachedGridHeight = height;
            cachedGridSpacing = spacing;
            if (spacing > base) {
                OptimizerLog.info("Map grid LOD: sector=" + width + "x" + height
                        + ", spacing=" + spacing + " (base=" + base + ")");
            }
            return spacing;
        } catch (Throwable ex) {
            return c.gridBaseSpacing;
        }
    }

    private static final class NebulaCacheSlot {
        volatile NebulaCache value;
    }

    private static final class NebulaCache {
        final WeakReference<SectorAPI> sector;
        final int systemCount;
        final long createdAtNanos;
        final List<StarSystemAPI> systemNebulaSystems;
        final long[] systemNebulaSeeds;
        final List<Constellation> constellations;
        final List<SectorEntityToken> nebulaStars;

        NebulaCache(WeakReference<SectorAPI> sector, int systemCount, long createdAtNanos,
                    List<StarSystemAPI> systemNebulaSystems, long[] systemNebulaSeeds,
                    List<Constellation> constellations, List<SectorEntityToken> nebulaStars) {
            this.sector = sector;
            this.systemCount = systemCount;
            this.createdAtNanos = createdAtNanos;
            this.systemNebulaSystems = systemNebulaSystems;
            this.systemNebulaSeeds = systemNebulaSeeds;
            this.constellations = constellations;
            this.nebulaStars = nebulaStars;
        }
    }

    // ---------------------------------------------------------------------
    // Closed-map campaign bookkeeping.
    //
    // CampaignEngine.advance() calls readdChangeListeners() every frame. The
    // vanilla method walks hyperspace and every star system and only writes the
    // same ObjectRepository listener reference again. Creation/removal APIs
    // already update listeners immediately, so retain the public vanilla method
    // unchanged and throttle only the call site in advance(). Structural changes
    // trigger an immediate refresh; a periodic audit covers direct list mutation
    // by mods.
    // ---------------------------------------------------------------------

    public static void readdChangeListenersIfNeeded(Object engine, List<?> systems, Object hyperspace) {
        if (engine == null) return;
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.campaignListenerThrottle
                || !campaignCachesReady(generation, engine)) {
            invokeReaddChangeListeners(engine);
            return;
        }

        int size = -1;
        Object first = null;
        Object last = null;
        long now = 0L;
        boolean refresh = true;
        boolean compatibilityFallback = false;
        Map<Object, CampaignListenerState> listenerStates = CAMPAIGN_LISTENER_STATES;
        try {
            size = systems == null ? -1 : systems.size();
            first = size > 0 ? systems.get(0) : null;
            last = size > 0 ? systems.get(size - 1) : null;
            now = System.nanoTime();
            long auditNs = c.campaignListenerAuditMs * 1_000_000L;

            synchronized (listenerStates) {
                if (!campaignCachesReady(generation, engine)) {
                    compatibilityFallback = true;
                } else {
                    CampaignListenerState state = listenerStates.get(engine);
                    if (!campaignCachesReady(generation, engine)) {
                        compatibilityFallback = true;
                    } else {
                        refresh = state == null
                                || state.systems != systems
                                || state.hyperspace != hyperspace
                                || state.systemCount != size
                                || state.firstSystem != first
                                || state.lastSystem != last
                                || (auditNs > 0L && now - state.refreshedAtNanos >= auditNs);
                    }
                }
            }
        } catch (Throwable ex) {
            compatibilityFallback = true;
        }

        if (compatibilityFallback) {
            // Keep the original call outside the catch boundary so an exception
            // from game/mod code cannot cause a retry.
            invokeReaddChangeListeners(engine);
            return;
        }

        if (!refresh && campaignCachesReady(generation, engine)) {
            CAMPAIGN_LISTENER_SKIPS.increment();
            return;
        }

        // Keep exceptions from the vanilla method exact: do not catch and retry it.
        invokeReaddChangeListeners(engine);
        try {
            synchronized (listenerStates) {
                if (!campaignCachesReady(generation, engine)) return;
                CampaignListenerState state = listenerStates.computeIfAbsent(
                        engine, ignored -> new CampaignListenerState());
                if (!campaignCachesReady(generation, engine)) {
                    listenerStates.clear();
                } else {
                    state.systems = systems;
                    state.hyperspace = hyperspace;
                    state.systemCount = size;
                    state.firstSystem = first;
                    state.lastSystem = last;
                    state.refreshedAtNanos = now;
                }
            }
        } catch (Throwable ignored) {
            // A failed bookkeeping update merely causes another vanilla refresh on
            // the next frame; it must not affect campaign execution.
        }
        CAMPAIGN_LISTENER_RUNS.increment();
    }

    private static void invokeReaddChangeListeners(Object engine) {
        try {
            CampaignListenerAccess.ACCESS.get(engine.getClass()).readdChangeListeners.invoke(engine);
        } catch (InvocationTargetException ex) {
            throwUnchecked(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke CampaignEngine.readdChangeListeners()", ex);
        }
    }

    private static final class CampaignListenerAccess {
        static final ClassValue<CampaignListenerAccess> ACCESS = new ClassValue<>() {
            @Override
            protected CampaignListenerAccess computeValue(Class<?> type) {
                try {
                    Method refresh = type.getMethod("readdChangeListeners");
                    refresh.setAccessible(true);
                    return new CampaignListenerAccess(refresh);
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Unexpected CampaignEngine layout: " + type.getName(), ex);
                }
            }
        };

        final Method readdChangeListeners;

        CampaignListenerAccess(Method readdChangeListeners) {
            this.readdChangeListeners = readdChangeListeners;
        }
    }

    private static final class CampaignListenerState {
        Object systems;
        Object hyperspace;
        Object firstSystem;
        Object lastSystem;
        int systemCount;
        long refreshedAtNanos;
    }

    // ---------------------------------------------------------------------
    // Route/pathfinding indexes.
    //
    // O0Oo.getNextStep() and getLastLegDistance() preserve all of their original
    // filtering, distance and tie-breaking code. These hooks only replace the
    // candidate source: an O(all hyperspace jump points) list becomes the same
    // ordered subset whose first destination belongs to the requested system.
    // Cache misses and malformed/custom data fall back to the vanilla full list.
    // ---------------------------------------------------------------------

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List routeJumpPointsForSystem(LocationAPI hyperspace, Object system) {
        if (hyperspace == null) return null;
        List live = hyperspace.getJumpPoints();
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.routeJumpPointIndex || !campaignCachesReady(generation)
                || c.routeIndexTtlMs <= 0
                || system == null || live == null || !(live instanceof RandomAccess)
                || live.size() < 32) {
            ROUTE_JUMP_INDEX_FALLBACKS.increment();
            return live;
        }

        try {
            long now = System.nanoTime();
            RouteJumpIndex index;
            Map<LocationAPI, RouteJumpIndex> routeJumpIndexes = ROUTE_JUMP_INDEXES;
            synchronized (routeJumpIndexes) {
                if (!campaignCachesReady(generation)) {
                    index = null;
                } else {
                    index = routeJumpIndexes.get(hyperspace);
                    if (!campaignCachesReady(generation)) {
                        index = null;
                    } else if (index == null || !index.matches(live, now, c.routeIndexTtlMs)) {
                        index = RouteJumpIndex.build(live, now);
                        if (!campaignCachesReady(generation)) {
                            index = null;
                        } else {
                            routeJumpIndexes.put(hyperspace, index);
                            if (!campaignCachesReady(generation)) {
                                routeJumpIndexes.clear();
                                index = null;
                            } else {
                                ROUTE_JUMP_INDEX_BUILDS.increment();
                            }
                        }
                    }
                    if (!campaignCachesReady(generation)) {
                        routeJumpIndexes.clear();
                        index = null;
                    }
                }
            }
            if (index == null) {
                ROUTE_JUMP_INDEX_FALLBACKS.increment();
                return live;
            }
            if (index.failed) {
                ROUTE_JUMP_INDEX_FALLBACKS.increment();
                return live;
            }
            List candidates = index.bySystem.get(system);
            if (candidates == null) {
                // A full-list fallback on a miss prevents stale indexes from ever
                // hiding a newly retargeted modded jump point.
                ROUTE_JUMP_INDEX_FALLBACKS.increment();
                return live;
            }
            ROUTE_JUMP_INDEX_HITS.increment();
            return candidates;
        } catch (Throwable ex) {
            ROUTE_JUMP_INDEX_FALLBACKS.increment();
            return live;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static List routeSystemsForAnchor(Object engine, Object anchor) {
        if (engine == null || anchor == null) return vanillaStarSystems(engine);
        OptimizerConfig c = config;
        long generation = campaignCacheGeneration;
        if (c == null || !c.routeJumpPointIndex
                || !campaignCachesReady(generation, engine)
                || c.routeIndexTtlMs <= 0) {
            return vanillaStarSystems(engine);
        }

        try {
            CampaignRouteAccess access = CampaignRouteAccess.ACCESS.get(engine.getClass());
            List systems = access.backingSystems(engine);
            if (systems instanceof RandomAccess && systems.size() >= 32) {
                long now = System.nanoTime();
                RouteSystemIndex index;
                Map<Object, RouteSystemIndex> routeSystemIndexes = ROUTE_SYSTEM_INDEXES;
                synchronized (routeSystemIndexes) {
                    if (!campaignCachesReady(generation, engine)) {
                        index = null;
                    } else {
                        index = routeSystemIndexes.get(engine);
                        if (!campaignCachesReady(generation, engine)) {
                            index = null;
                        } else if (index == null || !index.matches(systems, now, c.routeIndexTtlMs)) {
                            index = RouteSystemIndex.build(systems, now);
                            if (!campaignCachesReady(generation, engine)) {
                                index = null;
                            } else {
                                routeSystemIndexes.put(engine, index);
                                if (!campaignCachesReady(generation, engine)) {
                                    routeSystemIndexes.clear();
                                    index = null;
                                } else {
                                    ROUTE_SYSTEM_INDEX_BUILDS.increment();
                                }
                            }
                        }
                    }
                    if (!campaignCachesReady(generation, engine)) {
                        routeSystemIndexes.clear();
                        index = null;
                    }
                }
                if (index != null && !index.failed) {
                    List candidates = index.byAnchor.get(anchor);
                    if (candidates != null) {
                        ROUTE_SYSTEM_INDEX_HITS.increment();
                        return candidates;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through. The public getter below is deliberately independent
            // from backing-field discovery, so compatibility fallback stays live.
        }
        ROUTE_SYSTEM_INDEX_FALLBACKS.increment();
        return vanillaStarSystems(engine);
    }

    @SuppressWarnings("rawtypes")
    private static List vanillaStarSystems(Object engine) {
        if (engine == null) return Collections.emptyList();
        try {
            return (List) CampaignRouteGetter.GETTER.get(engine.getClass()).invoke(engine);
        } catch (InvocationTargetException ex) {
            throwUnchecked(ex.getCause());
            return Collections.emptyList(); // unreachable
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException("Unable to invoke CampaignEngine.getStarSystems()", ex);
        }
    }

    private static final class CampaignRouteGetter {
        static final ClassValue<Method> GETTER = new ClassValue<>() {
            @Override
            protected Method computeValue(Class<?> type) {
                try {
                    Method getter = type.getMethod("getStarSystems");
                    getter.setAccessible(true);
                    return getter;
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException("Unexpected CampaignEngine route layout: "
                            + type.getName(), ex);
                }
            }
        };
    }

    private static final class CampaignRouteAccess {
        static final ClassValue<CampaignRouteAccess> ACCESS = new ClassValue<>() {
            @Override
            protected CampaignRouteAccess computeValue(Class<?> type) {
                Method getter = CampaignRouteGetter.GETTER.get(type);
                ArrayList<Field> candidates = new ArrayList<>();
                Class<?> current = type;
                while (current != null) {
                    for (Field field : current.getDeclaredFields()) {
                        if (List.class.isAssignableFrom(field.getType())) {
                            field.setAccessible(true);
                            candidates.add(field);
                        }
                    }
                    current = current.getSuperclass();
                }
                return new CampaignRouteAccess(candidates, getter);
            }
        };

        final List<Field> listFields;
        final Method getStarSystems;
        volatile Field resolvedStarSystems;

        CampaignRouteAccess(List<Field> listFields, Method getStarSystems) {
            this.listFields = listFields;
            this.getStarSystems = getStarSystems;
        }

        @SuppressWarnings("rawtypes")
        List backingSystems(Object engine) throws ReflectiveOperationException {
            Field resolved = resolvedStarSystems;
            if (resolved != null) return (List) resolved.get(engine);
            synchronized (this) {
                resolved = resolvedStarSystems;
                if (resolved != null) return (List) resolved.get(engine);
                List visible = (List) getStarSystems.invoke(engine);
                Field match = null;
                for (Field candidate : listFields) {
                    Object value = candidate.get(engine);
                    if (!(value instanceof List list) || !sameIdentityContents(list, visible)) continue;
                    if (match != null) {
                        throw new IllegalStateException("Ambiguous CampaignEngine star-system List fields");
                    }
                    match = candidate;
                }
                if (match == null) {
                    throw new IllegalStateException("CampaignEngine backing star-system List was not found");
                }
                resolvedStarSystems = match;
                return (List) match.get(engine);
            }
        }

        @SuppressWarnings("rawtypes")
        private static boolean sameIdentityContents(List candidate, List visible) {
            if (candidate == null || visible == null || candidate.size() != visible.size()) return false;
            for (int i = 0; i < candidate.size(); i++) {
                if (candidate.get(i) != visible.get(i)) return false;
            }
            return true;
        }
    }

    private static final class RouteJumpIndex {
        final int size;
        final Object first;
        final Object last;
        long validatedAtNanos;
        final IdentityHashMap<Object, List<SectorEntityToken>> bySystem;
        final Object[] entries;
        final Object[] destinationSystems;
        final boolean failed;

        RouteJumpIndex(int size, Object first, Object last, long builtAtNanos,
                       IdentityHashMap<Object, List<SectorEntityToken>> bySystem,
                       Object[] entries, Object[] destinationSystems, boolean failed) {
            this.size = size;
            this.first = first;
            this.last = last;
            this.validatedAtNanos = builtAtNanos;
            this.bySystem = bySystem;
            this.entries = entries;
            this.destinationSystems = destinationSystems;
            this.failed = failed;
        }

        boolean matches(List<?> live, long now, int ttlMs) {
            int currentSize = live.size();
            Object currentFirst = currentSize > 0 ? live.get(0) : null;
            Object currentLast = currentSize > 0 ? live.get(currentSize - 1) : null;
            long ttlNs = ttlMs * 1_000_000L;
            if (size != currentSize || first != currentFirst || last != currentLast) return false;
            if (ttlNs <= 0L) return false;
            if (now - validatedAtNanos < ttlNs) return true;
            if (failed) return false;

            // Size/edge checks do not catch middle replacement or a mod retargeting
            // an existing jump point. Periodically validate the full relation while
            // keeping ordinary cache hits O(1); a mismatch rebuilds the index.
            try {
                for (int i = 0; i < currentSize; i++) {
                    Object raw = live.get(i);
                    if (raw != entries[i]
                            || destinationSystem(raw) != destinationSystems[i]) return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            validatedAtNanos = now;
            return true;
        }

        @SuppressWarnings("rawtypes")
        static RouteJumpIndex build(List live, long now) {
            int size = live.size();
            Object first = size > 0 ? live.get(0) : null;
            Object last = size > 0 ? live.get(size - 1) : null;
            IdentityHashMap<Object, List<SectorEntityToken>> bySystem = new IdentityHashMap<>();
            Object[] entries = new Object[size];
            Object[] destinationSystems = new Object[size];
            try {
                for (int i = 0; i < size; i++) {
                    Object raw = live.get(i);
                    entries[i] = raw;
                    Object location = destinationSystem(raw);
                    destinationSystems[i] = location;
                    if (location == null) continue;
                    bySystem.computeIfAbsent(location, ignored -> new ArrayList<>(2))
                            .add((SectorEntityToken) raw);
                }
                return new RouteJumpIndex(size, first, last, now, bySystem,
                        entries, destinationSystems, false);
            } catch (Throwable ex) {
                return new RouteJumpIndex(size, first, last, now, bySystem,
                        entries, destinationSystems, true);
            }
        }

        private static Object destinationSystem(Object raw) {
            if (!(raw instanceof JumpPointAPI jumpPoint)) {
                throw new IllegalStateException("Non-jump-point entry in hyperspace jump list");
            }
            List<JumpPointAPI.JumpDestination> destinations = jumpPoint.getDestinations();
            if (destinations == null || destinations.isEmpty()) return null;
            JumpPointAPI.JumpDestination firstDestination = destinations.get(0);
            if (firstDestination == null || firstDestination.getDestination() == null) {
                throw new IllegalStateException("Malformed jump-point destination");
            }
            return firstDestination.getDestination().getContainingLocation();
        }
    }

    private static final class RouteSystemIndex {
        final int size;
        final Object first;
        final Object last;
        long validatedAtNanos;
        final IdentityHashMap<Object, List<Object>> byAnchor;
        final Object[] entries;
        final Object[] anchors;
        final boolean failed;

        RouteSystemIndex(int size, Object first, Object last, long builtAtNanos,
                         IdentityHashMap<Object, List<Object>> byAnchor,
                         Object[] entries, Object[] anchors, boolean failed) {
            this.size = size;
            this.first = first;
            this.last = last;
            this.validatedAtNanos = builtAtNanos;
            this.byAnchor = byAnchor;
            this.entries = entries;
            this.anchors = anchors;
            this.failed = failed;
        }

        boolean matches(List<?> live, long now, int ttlMs) {
            int currentSize = live.size();
            Object currentFirst = currentSize > 0 ? live.get(0) : null;
            Object currentLast = currentSize > 0 ? live.get(currentSize - 1) : null;
            long ttlNs = ttlMs * 1_000_000L;
            if (size != currentSize || first != currentFirst || last != currentLast) return false;
            if (ttlNs <= 0L) return false;
            if (now - validatedAtNanos < ttlNs) return true;
            if (failed) return false;

            try {
                for (int i = 0; i < currentSize; i++) {
                    Object raw = live.get(i);
                    if (raw != entries[i] || !(raw instanceof StarSystemAPI system)
                            || system.getHyperspaceAnchor() != anchors[i]) return false;
                }
            } catch (Throwable ignored) {
                return false;
            }
            validatedAtNanos = now;
            return true;
        }

        @SuppressWarnings("rawtypes")
        static RouteSystemIndex build(List systems, long now) {
            int size = systems.size();
            Object first = size > 0 ? systems.get(0) : null;
            Object last = size > 0 ? systems.get(size - 1) : null;
            IdentityHashMap<Object, List<Object>> byAnchor = new IdentityHashMap<>(Math.max(32, size * 2));
            Object[] entries = new Object[size];
            Object[] anchors = new Object[size];
            try {
                for (int i = 0; i < size; i++) {
                    Object raw = systems.get(i);
                    entries[i] = raw;
                    if (!(raw instanceof StarSystemAPI system)) {
                        throw new IllegalStateException("Non-system entry in CampaignEngine star-system list");
                    }
                    SectorEntityToken anchor = system.getHyperspaceAnchor();
                    anchors[i] = anchor;
                    if (anchor != null) {
                        byAnchor.computeIfAbsent(anchor, ignored -> new ArrayList<>(1)).add(raw);
                    }
                }
                return new RouteSystemIndex(size, first, last, now, byAnchor, entries, anchors, false);
            } catch (Throwable ex) {
                return new RouteSystemIndex(size, first, last, now, byAnchor, entries, anchors, true);
            }
        }
    }

    private static Method findDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static Field findUniqueFieldByTypeName(Class<?> type, String fieldTypeName, String label)
            throws NoSuchFieldException {
        Field result = null;
        Class<?> current = type;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.getType().getName().equals(fieldTypeName)) continue;
                if (result != null) {
                    throw new NoSuchFieldException("Ambiguous " + label + " in " + type.getName());
                }
                result = field;
            }
            current = current.getSuperclass();
        }
        if (result == null) throw new NoSuchFieldException(label + " in " + type.getName());
        return result;
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static void throwUnchecked(Throwable cause) {
        if (cause instanceof RuntimeException runtime) throw runtime;
        if (cause instanceof Error error) throw error;
        throw new RuntimeException(cause);
    }

    // ---------------------------------------------------------------------
    // Helpers and stats
    // ---------------------------------------------------------------------

    private static int floorCell(float value, float cellSize) {
        return safeFloorToInt(value / cellSize);
    }

    private static int safeFloorToInt(float value) {
        if (value >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value <= Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) Math.floor(value);
    }

    private static long cellKey(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static void statsLoop() {
        OptimizerConfig c = config;
        int seconds = c == null ? 0 : c.statsLogIntervalSeconds;
        if (seconds <= 0) return;
        while (true) {
            try {
                Thread.sleep(seconds * 1000L);
                OptimizerLog.info("stats: retainCalls=" + RETAIN_CALLS.sumThenReset()
                        + ", avoidedContainsUpperBound=" + RETAIN_UPPER_BOUND.sumThenReset()
                        + ", retainKeysScanned=" + RETAIN_KEYS.sumThenReset()
                        + ", retainKeysRemoved=" + RETAIN_REMOVED.sumThenReset()
                        + ", retainEqualityFallbacks=" + RETAIN_EQUALITY_FALLBACKS.sumThenReset()
                        + ", hoverHits=" + HOVER_HITS.sumThenReset()
                        + ", hoverMisses=" + HOVER_MISSES.sumThenReset()
                        + ", mapLocationHits=" + MAP_LOCATION_HITS.sumThenReset()
                        + ", mapLocationMisses=" + MAP_LOCATION_MISSES.sumThenReset()
                        + ", arrowHits=" + ARROW_HITS.sumThenReset()
                        + ", arrowMisses=" + ARROW_MISSES.sumThenReset()
                        + ", intelIndexHits=" + INTEL_INDEX_HITS.sumThenReset()
                        + ", intelIndexBuilds=" + INTEL_INDEX_BUILDS.sumThenReset()
                        + ", labelCandidates=" + LABEL_CANDIDATES.sumThenReset()
                        + "/" + LABEL_TOTAL.sumThenReset()
                        + ", nebulaHits=" + NEBULA_HITS.sumThenReset()
                        + ", nebulaMisses=" + NEBULA_MISSES.sumThenReset()
                        + ", sampleClearSkips=" + SAMPLE_CLEAR_SKIPS.sumThenReset()
                        + ", campaignListenerRuns=" + CAMPAIGN_LISTENER_RUNS.sumThenReset()
                        + ", campaignListenerSkips=" + CAMPAIGN_LISTENER_SKIPS.sumThenReset()
                        + ", routeJumpIndexHits=" + ROUTE_JUMP_INDEX_HITS.sumThenReset()
                        + ", routeJumpIndexBuilds=" + ROUTE_JUMP_INDEX_BUILDS.sumThenReset()
                        + ", routeJumpIndexFallbacks=" + ROUTE_JUMP_INDEX_FALLBACKS.sumThenReset()
                        + ", routeSystemIndexHits=" + ROUTE_SYSTEM_INDEX_HITS.sumThenReset()
                        + ", routeSystemIndexBuilds=" + ROUTE_SYSTEM_INDEX_BUILDS.sumThenReset()
                        + ", routeSystemIndexFallbacks=" + ROUTE_SYSTEM_INDEX_FALLBACKS.sumThenReset()
                        + ", campaignCacheResets=" + CAMPAIGN_CACHE_RESETS.sumThenReset());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ex) {
                OptimizerLog.error("Stats logger failed", ex);
            }
        }
    }

    private static final class Scratch {
        final ArrayList<ScratchFrame> frames = new ArrayList<>(2);
        int scopeDepth;

        ScratchFrame frameAt(int index) {
            while (frames.size() <= index) frames.add(new ScratchFrame());
            return frames.get(index);
        }

        ScratchFrame scopeFrame() {
            return frameAt(Math.max(0, scopeDepth - 1));
        }
    }

    private static final class ScratchFrame {
        final ArrayList<Object> entityList = new ArrayList<>(1024);
        final ArrayList<Object> hitList = new ArrayList<>(1024);
        final HashSet<Object> classSet = new HashSet<>(16);
        final IdentityHashMap<Object, Boolean> identityMembership = new IdentityHashMap<>(2048);
        final HashSet<Object> equalityMembership = new HashSet<>(2048);
        final HashSet<Object> containsSet = new HashSet<>(512);
        Collection<?> containsSource;
        int containsSourceSize = -1;
        final ArrayList<Object> labelCandidates = new ArrayList<>(256);
        final Vector2f hitPoint = new Vector2f();
        final Vector2f[] arrowVectors = new Vector2f[] {
                new Vector2f(), new Vector2f(), new Vector2f(), new Vector2f(),
                new Vector2f(), new Vector2f(), new Vector2f(), new Vector2f()
        };
        int arrowVectorIndex;
        final ArrayList<SnapshotList> campaignSnapshots = new ArrayList<>(3);
        int campaignSnapshotIndex;

        SnapshotList campaignSnapshotAt(int index) {
            while (campaignSnapshots.size() <= index) {
                campaignSnapshots.add(new SnapshotList());
            }
            return campaignSnapshots.get(index);
        }

        void clear() {
            entityList.clear();
            hitList.clear();
            classSet.clear();
            identityMembership.clear();
            equalityMembership.clear();
            containsSet.clear();
            containsSource = null;
            containsSourceSize = -1;
            labelCandidates.clear();
            arrowVectorIndex = 0;
            for (int i = 0; i < campaignSnapshots.size(); i++) {
                campaignSnapshots.get(i).clearSnapshot();
            }
            campaignSnapshotIndex = 0;
        }
    }

    /** Array-backed, reusable read-only snapshot used only by verified loops. */
    private static final class SnapshotList extends AbstractList<Object> implements RandomAccess {
        private Object[] elements = new Object[0];
        private int size;
        private final ArrayList<SnapshotIterator> iterators = new ArrayList<>(2);
        private int iteratorIndex;

        void copyFrom(Collection<?> source) {
            int oldSize = size;
            try {
                int newSize = source.size();
                Object[] current = elements;
                Object[] copied = source.toArray(current);
                boolean reused = copied == current;
                int copiedSize = reused ? newSize : copied.length;
                if (reused && newSize < oldSize) {
                    Arrays.fill(current, newSize, oldSize, null);
                }
                elements = copied;
                size = copiedSize;
                iteratorIndex = 0;
                modCount++;
            } catch (Throwable ex) {
                // A custom Collection may fail after partially populating the
                // supplied array. Never let that exceptional path pin campaign
                // objects in the process-lifetime ThreadLocal.
                if (elements != null) Arrays.fill(elements, null);
                size = 0;
                iteratorIndex = 0;
                modCount++;
                throw propagate(ex);
            }
        }

        void clearSnapshot() {
            if (size > 0) Arrays.fill(elements, 0, size, null);
            size = 0;
            iteratorIndex = 0;
            modCount++;
        }

        @Override
        public Object get(int index) {
            if (index < 0 || index >= size) throw new IndexOutOfBoundsException(index);
            return elements[index];
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public Iterator<Object> iterator() {
            while (iterators.size() <= iteratorIndex) {
                iterators.add(new SnapshotIterator());
            }
            SnapshotIterator result = iterators.get(iteratorIndex++);
            result.reset();
            return result;
        }

        private final class SnapshotIterator implements Iterator<Object> {
            private int cursor;
            private int expectedModCount;

            void reset() {
                cursor = 0;
                expectedModCount = modCount;
            }

            @Override
            public boolean hasNext() {
                return cursor != size;
            }

            @Override
            public Object next() {
                if (expectedModCount != modCount) throw new ConcurrentModificationException();
                if (cursor >= size) throw new NoSuchElementException();
                return elements[cursor++];
            }
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> RuntimeException propagate(Throwable ex) throws T {
            throw (T) ex;
        }
    }
}
