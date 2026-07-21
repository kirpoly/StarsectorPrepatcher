package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherCoreWorldsRuntime;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;
import org.lwjgl.util.vector.Vector2f;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Semantic regression for the CoreScript core-worlds cache runtime. */
public final class CoreWorldsRuntimeRegressionTest {
    private CoreWorldsRuntimeRegressionTest() {}

    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile("prepatcher-core-worlds", ".properties");
        Files.writeString(configFile, String.join("\n",
                "patch.coreWorldsExtentCache=true",
                "coreWorlds.skipFastForwardIterations=true",
                "coreWorlds.checkMemoryExpiry=true",
                "coreWorlds.validationFrames=1", ""));
        PrepatcherConfig config = PrepatcherConfig.load(configFile);
        Method configure = StarsectorPrepatcherCoreWorldsRuntime.class
                .getDeclaredMethod("configure", PrepatcherConfig.class);
        configure.setAccessible(true);
        configure.invoke(null, config);
        StarsectorPrepatcherCoreWorldsRuntime.resetForTests();

        MemoryState memoryState = new MemoryState();
        MemoryAPI memory = proxy(MemoryAPI.class, (proxy, method, argv) -> {
            String name = method.getName();
            if (name.equals("get")) return memoryState.values.get((String) argv[0]);
            if (name.equals("set")) {
                String key = (String) argv[0];
                memoryState.values.put(key, argv[1]);
                memoryState.expires.remove(key);
                memoryState.setCalls++;
                return null;
            }
            if (name.equals("getExpire")) {
                return memoryState.expires.getOrDefault((String) argv[0], -1f);
            }
            return defaultValue(method.getReturnType());
        });

        SystemState first = new SystemState(true, 10f, 20f);
        SystemState second = new SystemState(true, 30f, 40f);
        List<StarSystemAPI> systems = new ArrayList<>();
        systems.add(system(first));
        systems.add(system(second));
        SectorState sectorState = new SectorState(memory, systems);
        SectorAPI sector = proxy(SectorAPI.class, (proxy, method, argv) -> {
            return switch (method.getName()) {
                case "getMemoryWithoutUpdate" -> sectorState.memory;
                case "getStarSystems" -> sectorState.systems;
                case "isFastForwardIteration" -> sectorState.fastForward;
                default -> defaultValue(method.getReturnType());
            };
        });

        StarsectorPrepatcherCoreWorldsRuntime.update(sector);
        require(memoryState.setCalls == 3, "initial scan must publish three memory values");
        assertVector(memoryState.values.get("$coreWorldsMin"), 0f, 0f, "initial min");
        assertVector(memoryState.values.get("$coreWorldsMax"), 30f, 40f, "initial max");
        assertVector(memoryState.values.get("$coreWorldsCenter"), 15f, 20f, "initial center");

        StarsectorPrepatcherCoreWorldsRuntime.update(sector);
        require(memoryState.setCalls == 3, "unchanged scan must not republish");

        ((Vector2f) memoryState.values.get("$coreWorldsMin")).x = 99f;
        StarsectorPrepatcherCoreWorldsRuntime.update(sector);
        require(memoryState.setCalls == 6, "mutated published vector must be repaired immediately");
        assertVector(memoryState.values.get("$coreWorldsMin"), 0f, 0f, "repaired min");

        second.location.x = 50f;
        sectorState.fastForward = true;
        StarsectorPrepatcherCoreWorldsRuntime.update(sector);
        require(memoryState.setCalls == 6,
                "radical fast-forward substep must skip an intact global scan");
        assertVector(memoryState.values.get("$coreWorldsMax"), 30f, 40f,
                "fast-forward deferred max");

        sectorState.fastForward = false;
        StarsectorPrepatcherCoreWorldsRuntime.update(sector);
        require(memoryState.setCalls == 9,
                "first non-fast-forward substep must publish changed geometry");
        assertVector(memoryState.values.get("$coreWorldsMax"), 50f, 40f,
                "updated max");

        memoryState.expires.put("$coreWorldsMin", 5f);
        StarsectorPrepatcherCoreWorldsRuntime.update(sector);
        require(memoryState.setCalls == 12, "timed expiry must force a full republish");
        require(!memoryState.expires.containsKey("$coreWorldsMin"),
                "republish must clear timed expiry through MemoryAPI.set");

        assertNoStaticGameObjectRetention();
        Files.deleteIfExists(configFile);
        System.out.println("OK core-worlds runtime initial/unchanged/repair/fast-forward/expiry"
                + " weak-reachability");
    }

    /**
     * Leaves the fixture as the runtime's current sector and verifies that the
     * cache itself does not keep any part of the game-object graph alive.
     */
    private static void assertNoStaticGameObjectRetention() throws Exception {
        List<GcProbe> probes = createGcFixture();
        for (int attempt = 0; attempt < 80 && hasLiveProbe(probes); attempt++) {
            System.gc();
            System.runFinalization();
            byte[][] pressure = new byte[8][];
            for (int i = 0; i < pressure.length; i++) {
                pressure[i] = new byte[128 * 1024];
            }
            Thread.sleep(10L);
        }
        List<String> survivors = new ArrayList<>();
        for (GcProbe probe : probes) {
            if (probe.reference.get() != null) survivors.add(probe.label);
        }
        require(survivors.isEmpty(),
                "core-worlds static state retained game objects: " + survivors);
        StarsectorPrepatcherCoreWorldsRuntime.resetForTests();
    }

    private static List<GcProbe> createGcFixture() {
        MemoryState memoryState = new MemoryState();
        MemoryAPI memory = proxy(MemoryAPI.class, (proxy, method, argv) -> {
            if (method.getName().equals("get")) {
                return memoryState.values.get((String) argv[0]);
            }
            if (method.getName().equals("set")) {
                memoryState.values.put((String) argv[0], argv[1]);
                return null;
            }
            if (method.getName().equals("getExpire")) return -1f;
            return defaultValue(method.getReturnType());
        });
        List<StarSystemAPI> systems = new ArrayList<>();
        systems.add(system(new SystemState(true, 100f, 200f)));
        SectorState sectorState = new SectorState(memory, systems);
        SectorAPI sector = proxy(SectorAPI.class, (proxy, method, argv) -> switch (
                method.getName()) {
            case "getMemoryWithoutUpdate" -> sectorState.memory;
            case "getStarSystems" -> sectorState.systems;
            case "isFastForwardIteration" -> false;
            default -> defaultValue(method.getReturnType());
        });
        StarsectorPrepatcherCoreWorldsRuntime.update(sector);
        Object publishedMin = memoryState.values.get("$coreWorldsMin");
        require(publishedMin instanceof Vector2f,
                "GC fixture did not publish the minimum vector");
        return List.of(
                new GcProbe("sector", new WeakReference<>(sector)),
                new GcProbe("memory", new WeakReference<>(memory)),
                new GcProbe("systems", new WeakReference<>(systems)),
                new GcProbe("publishedMin", new WeakReference<>(publishedMin)));
    }

    private static boolean hasLiveProbe(List<GcProbe> probes) {
        for (GcProbe probe : probes) {
            if (probe.reference.get() != null) return true;
        }
        return false;
    }

    private static StarSystemAPI system(SystemState state) {
        return proxy(StarSystemAPI.class, (proxy, method, argv) -> switch (method.getName()) {
            case "hasTag" -> state.core && "theme_core".equals(argv[0]);
            case "getLocation" -> state.location;
            default -> defaultValue(method.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
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

    private static void assertVector(Object value, float x, float y, String label) {
        require(value instanceof Vector2f, label + " is not Vector2f: " + value);
        Vector2f vector = (Vector2f) value;
        require(Float.floatToRawIntBits(vector.x) == Float.floatToRawIntBits(x)
                        && Float.floatToRawIntBits(vector.y) == Float.floatToRawIntBits(y),
                label + " expected=(" + x + "," + y + ") actual=("
                        + vector.x + "," + vector.y + ")");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class MemoryState {
        final Map<String, Object> values = new HashMap<>();
        final Map<String, Float> expires = new HashMap<>();
        int setCalls;
    }

    private static final class SystemState {
        final boolean core;
        final Vector2f location;

        SystemState(boolean core, float x, float y) {
            this.core = core;
            this.location = new Vector2f(x, y);
        }
    }

    private static final class SectorState {
        final MemoryAPI memory;
        final List<StarSystemAPI> systems;
        boolean fastForward;

        SectorState(MemoryAPI memory, List<StarSystemAPI> systems) {
            this.memory = memory;
            this.systems = systems;
        }
    }

    private record GcProbe(String label, WeakReference<Object> reference) {}
}
