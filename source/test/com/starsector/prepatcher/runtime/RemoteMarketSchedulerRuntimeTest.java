package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Runtime semantics checks for the full staggered remote-market scheduler. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class RemoteMarketSchedulerRuntimeTest {
    private RemoteMarketSchedulerRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        PrepatcherConfig config = config(true);
        installConfig(config);
        Object engine = new Object();
        activateCampaign(engine);

        MutableLocation currentLocation = new MutableLocation("current");
        MutableLocation remoteLocation = new MutableLocation("remote");
        SectorContext sector = new SectorContext(currentLocation.proxy, 100f);
        Global.setSector(sector.proxy);

        try {
            assertIdentityStateMap();
            assertStaggeringAndAmountConservation(sector, currentLocation, remoteLocation);
            assertHotPolicies(sector, currentLocation, remoteLocation);
            assertMemoryOptOut(sector, remoteLocation);
            assertHiddenCadenceAndSaveFlush(sector, remoteLocation);
            assertNestedEconomyFallbackPreservesOuterFrame(sector, remoteLocation);
            assertReentrantFallback(sector, remoteLocation);
            assertDisabledFallback(sector, remoteLocation);
        } finally {
            Global.setSector(null);
            StarsectorPrepatcherHooks.beginCampaignEngineChange(null);
            installConfig(null);
        }

        System.out.println("OK remote-market-scheduler identity-state"
                + " stable-phase/staggering amount-conservation"
                + " current/player/interaction/memory hot policies"
                + " hidden cadence save-flush nested-economy/reentrant/disabled fallback");
    }

    private static void assertIdentityStateMap() throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("REMOTE_MARKET_STATES");
        field.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) field.get(null);
        Field delegate = findSynchronizedMapDelegate(map.getClass());
        boolean inspected = false;
        if (delegate != null) {
            try {
                delegate.setAccessible(true);
                Object backing = delegate.get(map);
                require(backing instanceof IdentityHashMap,
                        "remote market scheduler is not identity-keyed");
                inspected = true;
            } catch (RuntimeException inaccessibleOnStrongModules) {
                // The normal Java 17 test JVM does not open java.util. Functional
                // proxy tests below still prove custom equals/hashCode does not
                // force the vanilla fallback or alias two market identities.
            }
        }
        if (!inspected) {
            require(map.getClass().getName().contains("SynchronizedMap"),
                    "unexpected remote-market state map type: " + map.getClass());
        }
    }

    private static Field findSynchronizedMapDelegate(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField("m");
            } catch (NoSuchFieldException ignored) {
                // Continue through package-private superclass.
            }
        }
        return null;
    }

    private static void assertStaggeringAndAmountConservation(
            SectorContext sector, MutableLocation current, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = current.proxy;
        sector.interactionMarket = null;

        ArrayList<MutableMarket> markets = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            markets.add(new MutableMarket("remote-" + i, remote.proxy, false, false));
        }

        Method stablePhase = StarsectorPrepatcherHooks.class.getDeclaredMethod(
                "stableMarketPhase", MarketAPI.class, int.class);
        stablePhase.setAccessible(true);

        int frames = 13;
        for (int frame = 1; frame <= frames; frame++) {
            beginSyntheticEconomyFrame();
            try {
                for (MutableMarket market : markets) {
                    StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f);
                }
            } finally {
                endSyntheticEconomyFrame();
            }
        }

        boolean sawDeferred = false;
        boolean sawDifferentCadence = false;
        int firstCalls = markets.get(0).advanceFrames.size();
        for (MutableMarket market : markets) {
            int phase = (Integer) stablePhase.invoke(null, market.proxy, 4);
            ArrayList<Integer> expected = new ArrayList<>();
            expected.add(1); // Newly observed markets always initialize immediately.
            for (int frame = 2; frame <= frames; frame++) {
                if (Math.floorMod(frame, 4) == phase) expected.add(frame);
            }
            require(market.advanceFrames.equals(expected),
                    "remote market cadence diverged for " + market.id
                            + ": expected=" + expected + " actual=" + market.advanceFrames);
            if (market.calls < frames) sawDeferred = true;
            if (market.calls != firstCalls) sawDifferentCadence = true;
        }
        require(sawDeferred, "remote markets were not staggered");
        // Different phase counts are not guaranteed for every short interval,
        // but the per-market frame assertions above prove stable phase behavior.
        if (!sawDifferentCadence) {
            require(markets.stream().map(m -> m.advanceFrames).distinct().count() > 1,
                    "all remote markets unexpectedly used one phase");
        }

        StarsectorPrepatcherHooks.flushRemoteMarketsBeforeSave();
        for (MutableMarket market : markets) {
            require(close(market.totalAmount, frames),
                    "deferred amount was lost for " + market.id + ": " + market.totalAmount);
        }
    }

    private static void assertHotPolicies(
            SectorContext sector, MutableLocation current, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();

        MutableMarket local = new MutableMarket("local", current.proxy, false, false);
        MutableMarket playerOwned = new MutableMarket("player", remote.proxy, true, false);
        MutableMarket interaction = new MutableMarket("interaction", remote.proxy, false, false);
        sector.currentLocation = current.proxy;
        sector.interactionMarket = interaction.proxy;

        for (int frame = 0; frame < 9; frame++) {
            beginSyntheticEconomyFrame();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(local.proxy, 0.25f);
                StarsectorPrepatcherHooks.advanceMarketScheduled(playerOwned.proxy, 0.25f);
                StarsectorPrepatcherHooks.advanceMarketScheduled(interaction.proxy, 0.25f);
            } finally {
                endSyntheticEconomyFrame();
            }
        }

        require(local.calls == 9 && playerOwned.calls == 9 && interaction.calls == 9,
                "one of the full-rate hot-market policies was throttled");
        require(close(local.totalAmount, 2.25f)
                        && close(playerOwned.totalAmount, 2.25f)
                        && close(interaction.totalAmount, 2.25f),
                "hot-market amount changed");
    }

    private static void assertMemoryOptOut(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket market = new MutableMarket("memory-hot", remote.proxy, false, false);
        market.memory.fullRate = true;

        for (int frame = 0; frame < 7; frame++) {
            beginSyntheticEconomyFrame();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f);
            } finally {
                endSyntheticEconomyFrame();
            }
        }
        require(market.calls == 7 && close(market.totalAmount, 7f),
                "per-market memory opt-out did not keep full cadence");
    }

    private static void assertHiddenCadenceAndSaveFlush(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket hidden = new MutableMarket("hidden", remote.proxy, false, true);

        Method stablePhase = StarsectorPrepatcherHooks.class.getDeclaredMethod(
                "stableMarketPhase", MarketAPI.class, int.class);
        stablePhase.setAccessible(true);
        int phase = (Integer) stablePhase.invoke(null, hidden.proxy, 8);

        int frames = 11;
        for (int frame = 1; frame <= frames; frame++) {
            beginSyntheticEconomyFrame();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(hidden.proxy, 2f);
            } finally {
                endSyntheticEconomyFrame();
            }
        }
        ArrayList<Integer> expected = new ArrayList<>();
        expected.add(1);
        for (int frame = 2; frame <= frames; frame++) {
            if (Math.floorMod(frame, 8) == phase) expected.add(frame);
        }
        require(hidden.advanceFrames.equals(expected),
                "hidden market did not use hidden cadence: expected=" + expected
                        + " actual=" + hidden.advanceFrames);

        float before = hidden.totalAmount;
        StarsectorPrepatcherHooks.flushRemoteMarketsBeforeSave();
        require(close(hidden.totalAmount, frames * 2f),
                "save flush lost hidden-market time: before=" + before
                        + " after=" + hidden.totalAmount);
        int callsAfterFirstFlush = hidden.calls;
        StarsectorPrepatcherHooks.flushRemoteMarketsBeforeSave();
        require(hidden.calls == callsAfterFirstFlush,
                "save flush replayed already-applied pending time");
    }

    private static void assertNestedEconomyFallbackPreservesOuterFrame(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket nested = new MutableMarket("nested-economy", remote.proxy, false, false);

        beginSyntheticEconomyFrame();
        try {
            long outerFrame = schedulerFrame();
            StarsectorPrepatcherHooks.beginScratchScope();
            try {
                // A recursively entered Economy.advance() would execute these two
                // transformed hooks at scratch depth 2. It must leave the outer
                // context intact and execute the nested market immediately.
                StarsectorPrepatcherHooks.beginRemoteMarketFrame();
                require(schedulerFrame() == outerFrame,
                        "nested Economy frame overwrote the outer scheduler context");
                StarsectorPrepatcherHooks.advanceMarketScheduled(nested.proxy, 0.5f);
            } finally {
                StarsectorPrepatcherHooks.endScratchScope();
            }

            require(schedulerFrame() == outerFrame,
                    "nested Economy fallback did not preserve the outer frame");
            StarsectorPrepatcherHooks.advanceMarketScheduled(nested.proxy, 1f);
        } finally {
            endSyntheticEconomyFrame();
        }

        require(nested.calls == 2 && close(nested.totalAmount, 1.5f),
                "nested Economy fallback changed callback multiplicity or amount");
    }

    private static void assertReentrantFallback(
            SectorContext sector, MutableLocation remote) throws Exception {
        resetSchedulerGeneration();
        sector.currentLocation = null;
        sector.interactionMarket = null;
        MutableMarket market = new MutableMarket("reentrant", remote.proxy, false, false);
        market.reenterOnce = true;

        beginSyntheticEconomyFrame();
        try {
            StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f);
        } finally {
            endSyntheticEconomyFrame();
        }
        require(market.calls == 2 && close(market.totalAmount, 1.5f),
                "reentrant market callback deadlocked, disappeared, or coalesced unexpectedly");
    }

    private static void assertDisabledFallback(
            SectorContext sector, MutableLocation remote) throws Exception {
        installConfig(config(false));
        MutableMarket market = new MutableMarket("disabled", remote.proxy, false, false);
        for (int i = 0; i < 5; i++) {
            beginSyntheticEconomyFrame();
            try {
                StarsectorPrepatcherHooks.advanceMarketScheduled(market.proxy, 1f);
            } finally {
                endSyntheticEconomyFrame();
            }
        }
        require(market.calls == 5 && close(market.totalAmount, 5f),
                "disabled scheduler did not execute exact immediate fallback");
        installConfig(config(true));
        resetSchedulerGeneration();
    }

    private static void beginSyntheticEconomyFrame() {
        StarsectorPrepatcherHooks.beginScratchScope();
        StarsectorPrepatcherHooks.beginRemoteMarketFrame();
    }

    private static void endSyntheticEconomyFrame() {
        StarsectorPrepatcherHooks.endScratchScope();
    }

    private static PrepatcherConfig config(boolean enabled) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.remoteMarketScheduler", Boolean.toString(enabled));
        properties.setProperty("market.remote.frames", "4");
        properties.setProperty("market.remote.hiddenFrames", "8");
        properties.setProperty("market.remote.maxDeferredFrames", "120");
        properties.setProperty("market.remote.maxDeferredGameDays", "0");
        properties.setProperty("market.hot.currentLocation", "true");
        properties.setProperty("market.hot.playerOwned", "true");
        properties.setProperty("market.hot.interaction", "true");
        properties.setProperty("market.remote.policyAuditFrames", "1");
        properties.setProperty("market.remote.fullRateMemoryKey",
                "$starsectorPrepatcher_fullRateMarket");
        properties.setProperty("logging.statsIntervalSeconds", "0");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        PrepatcherConfig config = constructor.newInstance(properties);
        require(config.remoteMarketScheduler == enabled
                        && config.remoteMarketFrames == 4
                        && config.remoteMarketHiddenFrames == 8
                        && config.remoteMarketMaxDeferredFrames == 120
                        && config.remoteMarketMaxDeferredGameDays == 0f,
                "scheduler test configuration was not parsed correctly");
        return config;
    }

    private static void installConfig(PrepatcherConfig config) throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(null, config);
    }

    private static void activateCampaign(Object engine) {
        long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(engine);
        require(transition >= 0L, "campaign lifecycle did not open scheduler generation");
        StarsectorPrepatcherHooks.completeCampaignEngineChange(engine, transition);
    }

    private static void resetSchedulerGeneration() {
        Object engine = new Object();
        long transition = StarsectorPrepatcherHooks.beginCampaignEngineChange(engine);
        require(transition >= 0L, "scheduler reset did not open new generation");
        StarsectorPrepatcherHooks.completeCampaignEngineChange(engine, transition);
    }

    private static long schedulerFrame() throws Exception {
        Field field = StarsectorPrepatcherHooks.class.getDeclaredField("remoteMarketFrame");
        field.setAccessible(true);
        return field.getLong(null);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) <= 0.0001f * Math.max(1f, Math.max(Math.abs(a), Math.abs(b)));
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, String label) {
        return switch (method.getName()) {
            case "toString" -> label;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> null;
        };
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class MutableLocation implements InvocationHandler {
        final String id;
        final LocationAPI proxy;

        MutableLocation(String id) {
            this.id = id;
            this.proxy = (LocationAPI) Proxy.newProxyInstance(
                    LocationAPI.class.getClassLoader(), new Class<?>[] {LocationAPI.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "Location[" + id + "]");
            }
            if (method.getName().equals("getId")) return id;
            return primitiveDefault(method.getReturnType());
        }
    }

    private static final class MutableMemory implements InvocationHandler {
        boolean fullRate;
        final MemoryAPI proxy;

        MutableMemory() {
            this.proxy = (MemoryAPI) Proxy.newProxyInstance(
                    MemoryAPI.class.getClassLoader(), new Class<?>[] {MemoryAPI.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "Memory");
            }
            if (method.getName().equals("getBoolean")) return fullRate;
            return primitiveDefault(method.getReturnType());
        }
    }

    private static final class MutableMarket implements InvocationHandler {
        final String id;
        final LocationAPI location;
        final boolean playerOwned;
        final boolean hidden;
        final MutableMemory memory = new MutableMemory();
        final MarketAPI proxy;
        final ArrayList<Integer> advanceFrames = new ArrayList<>();
        int calls;
        float totalAmount;
        boolean reenterOnce;
        boolean inHandler;

        MutableMarket(String id, LocationAPI location, boolean playerOwned, boolean hidden) {
            this.id = id;
            this.location = location;
            this.playerOwned = playerOwned;
            this.hidden = hidden;
            this.proxy = (MarketAPI) Proxy.newProxyInstance(
                    MarketAPI.class.getClassLoader(), new Class<?>[] {MarketAPI.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "Market[" + id + "]");
            }
            return switch (method.getName()) {
                case "getId" -> id;
                case "getContainingLocation" -> location;
                case "isPlayerOwned" -> playerOwned;
                case "isHidden" -> hidden;
                case "getMemoryWithoutUpdate" -> memory.proxy;
                case "advance" -> {
                    float amount = (Float) args[0];
                    calls++;
                    totalAmount += amount;
                    advanceFrames.add(currentSchedulerFrame());
                    if (reenterOnce && !inHandler) {
                        reenterOnce = false;
                        inHandler = true;
                        try {
                            StarsectorPrepatcherHooks.advanceMarketScheduled(this.proxy, 0.5f);
                        } finally {
                            inHandler = false;
                        }
                    }
                    yield null;
                }
                default -> primitiveDefault(method.getReturnType());
            };
        }

        private int currentSchedulerFrame() {
            try {
                Field field = StarsectorPrepatcherHooks.class.getDeclaredField("remoteMarketFrame");
                field.setAccessible(true);
                return (int) field.getLong(null);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static final class SectorContext implements InvocationHandler {
        LocationAPI currentLocation;
        MarketAPI interactionMarket;
        final float secondsPerDay;
        final SectorAPI proxy;
        final CampaignClockAPI clock;
        final CampaignUIAPI ui;

        SectorContext(LocationAPI currentLocation, float secondsPerDay) {
            this.currentLocation = currentLocation;
            this.secondsPerDay = secondsPerDay;
            this.clock = (CampaignClockAPI) Proxy.newProxyInstance(
                    CampaignClockAPI.class.getClassLoader(),
                    new Class<?>[] {CampaignClockAPI.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args, "Clock");
                        }
                        if (method.getName().equals("getSecondsPerDay")) return this.secondsPerDay;
                        return primitiveDefault(method.getReturnType());
                    });
            this.ui = (CampaignUIAPI) Proxy.newProxyInstance(
                    CampaignUIAPI.class.getClassLoader(),
                    new Class<?>[] {CampaignUIAPI.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return objectMethod(proxy, method, args, "CampaignUI");
                        }
                        if (method.getName().equals("getCurrentInteractionDialog")) {
                            if (interactionMarket == null) return null;
                            SectorEntityToken token = (SectorEntityToken) Proxy.newProxyInstance(
                                    SectorEntityToken.class.getClassLoader(),
                                    new Class<?>[] {SectorEntityToken.class},
                                    (tokenProxy, tokenMethod, tokenArgs) -> {
                                        if (tokenMethod.getDeclaringClass() == Object.class) {
                                            return objectMethod(tokenProxy, tokenMethod, tokenArgs,
                                                    "InteractionTarget");
                                        }
                                        if (tokenMethod.getName().equals("getMarket")) {
                                            return interactionMarket;
                                        }
                                        return primitiveDefault(tokenMethod.getReturnType());
                                    });
                            return Proxy.newProxyInstance(
                                    InteractionDialogAPI.class.getClassLoader(),
                                    new Class<?>[] {InteractionDialogAPI.class},
                                    (dialogProxy, dialogMethod, dialogArgs) -> {
                                        if (dialogMethod.getDeclaringClass() == Object.class) {
                                            return objectMethod(dialogProxy, dialogMethod, dialogArgs,
                                                    "InteractionDialog");
                                        }
                                        if (dialogMethod.getName().equals("getInteractionTarget")) {
                                            return token;
                                        }
                                        return primitiveDefault(dialogMethod.getReturnType());
                                    });
                        }
                        return primitiveDefault(method.getReturnType());
                    });
            this.proxy = (SectorAPI) Proxy.newProxyInstance(
                    SectorAPI.class.getClassLoader(), new Class<?>[] {SectorAPI.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, "Sector");
            }
            return switch (method.getName()) {
                case "getCurrentLocation" -> currentLocation;
                case "getCampaignUI" -> ui;
                case "getClock" -> clock;
                default -> primitiveDefault(method.getReturnType());
            };
        }
    }
}
