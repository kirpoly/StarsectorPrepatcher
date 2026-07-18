package com.starsector.prepatcher.runtime;

import com.fs.starfarer.api.StarsectorPrepatcherHooks;
import com.fs.starfarer.api.StarsectorPrepatcherRuntimeBridge;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.stream.Stream;

/** File-output and fail-open behavior test for direct Market.advance observation. */
public final class DirectMarketObservationRuntimeTest {
    private DirectMarketObservationRuntimeTest() {}

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("prepatcher-direct-market-");
        try {
            PrepatcherConfig config = config();
            System.setProperty("starsector.prepatcher.version", "test");
            StarsectorPrepatcherRuntimeBridge.configure(config, root);

            Counter counter = new Counter();
            MarketAPI market = (MarketAPI) Proxy.newProxyInstance(
                    ClassLoader.getSystemClassLoader(), new Class<?>[]{MarketAPI.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("advance")) {
                            counter.calls++;
                            counter.sum += (Float) arguments[0];
                        }
                        return defaultValue(method.getReturnType());
                    });

            String metadata = String.join("\u001f", "1", "mods/TestMod/test.jar",
                    "example.Driver", "advance", "(F)V", "42", "0", "ARG0",
                    "com.fs.starfarer.api.campaign.econ.MarketAPI", "185");
            long siteId = 0x1234abcdL;
            StarsectorPrepatcherHooks.observeDirectMarketAdvance(market, 0.25f, siteId, metadata);
            StarsectorPrepatcherHooks.observeDirectMarketAdvance(market, 0.75f, siteId, metadata);
            // Simulates a reflective/uninstrumented entry. It is observation-only and does not
            // invoke the market itself because the real entry probe runs inside Market.advance.
            StarsectorPrepatcherHooks.observeMarketAdvanceEntry(market, 0.5f);
            require(counter.calls == 2, "observation wrapper changed call count");
            require(Float.floatToIntBits(counter.sum) == Float.floatToIntBits(1f),
                    "observation wrapper changed amount sum: " + counter.sum);

            Path session = waitForSession(root.resolve("logs/direct-market-observe"));
            Path observations = session.resolve("observations.csv");
            Path sites = session.resolve("call-sites.csv");
            Path summary = session.resolve("summary.csv");
            waitForContent(observations, "example.Driver", 5000L);
            waitForContent(sites, "mods/TestMod/test.jar", 5000L);
            waitForContent(summary, ",2,", 5000L);

            String observationText = Files.readString(observations, StandardCharsets.UTF_8);
            require(observationText.contains("0x1234abcd"),
                    "site id missing from observations");
            require(observationText.contains("example.Driver"),
                    "caller metadata missing from observations");
            String unknown = Files.readString(session.resolve("unknown-stacks.csv"),
                    StandardCharsets.UTF_8);
            require(!unknown.isBlank(), "unknown-entry stack file was not created");

            System.out.println("OK direct-market-runtime session=" + session
                    + " calls=" + counter.calls);
        } finally {
            deleteRecursively(root);
        }
    }

    private static PrepatcherConfig config() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.directMarketObservation", "true");
        properties.setProperty("directMarket.timingSampleEvery", "1");
        properties.setProperty("directMarket.stackSampleEvery", "1");
        properties.setProperty("directMarket.maxStacksPerSite", "4");
        properties.setProperty("directMarket.reportIntervalSeconds", "1");
        properties.setProperty("directMarket.maxSites", "32");
        properties.setProperty("directMarket.unknownStackSamples", "4");
        properties.setProperty("logging.statsIntervalSeconds", "0");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static Path waitForSession(Path base) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.isDirectory(base)) {
                try (Stream<Path> entries = Files.list(base)) {
                    Path session = entries.filter(Files::isDirectory).findFirst().orElse(null);
                    if (session != null) return session;
                }
            }
            Thread.sleep(25L);
        }
        throw new AssertionError("observation session directory was not created: " + base);
    }

    private static void waitForContent(Path file, String needle, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (text.contains(needle)) return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("timed out waiting for '" + needle + "' in " + file);
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

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Counter {
        int calls;
        float sum;
    }
}
