package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.reflect.Constructor;
import java.net.URL;
import java.security.CodeSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarFile;

/** Offline exact-build verification for fast-forward presentation coalescing. */
public final class FastForwardPresentationCompatibilityTest {
    private static final String PRESENTATION_HOOKS =
            "com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks";
    private static final String PREPATCHER_HOOK_PREFIX =
            "com/fs/starfarer/api/StarsectorPrepatcher";
    private static final String PATCH_MARKER_PREFIX = "smo$patched$";

    private static final Map<String, Expected> EXPECTED = expectedTargets();
    private static final Map<String, Set<String>> CHAIN_MARKERS = Map.of(
            "com/fs/starfarer/campaign/CampaignEngine", Set.of(
                    "smo$patched$campaignCacheLifecycle",
                    "smo$patched$campaignListenerThrottle"),
            "com/fs/starfarer/campaign/BaseLocation", Set.of(
                    "smo$patched$campaignSnapshotReuse"),
            "com/fs/starfarer/campaign/BaseCampaignEntity", Set.of(
                    "smo$patched$entityScriptSnapshotReuse"),
            "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin", Set.of(
                    "smo$patched$skipNoOpTerrainLayer"));

    private FastForwardPresentationCompatibilityTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: FastForwardPresentationCompatibilityTest <starfarer_obf.jar> <starfarer.api.jar>");
        }
        Path coreJar = Path.of(args[0]).toAbsolutePath().normalize();
        Path apiJar = Path.of(args[1]).toAbsolutePath().normalize();
        require(Files.isRegularFile(coreJar), "core JAR not found: " + coreJar);
        require(Files.isRegularFile(apiJar), "API JAR not found: " + apiJar);

        Map<String, byte[]> originals = readOriginals(coreJar, apiJar);
        PrepatcherConfig safe = config(profileProperties(false));
        PrepatcherConfig aggressive = config(profileProperties(true));

        ProfileResult safeResult = verifyProfile("safe", safe, originals, false);
        require(safeResult.classes() == 20,
                "safe profile must patch exactly 20 classes, found " + safeResult.classes());
        require(safeResult.callSites() == 59,
                "safe profile must patch exactly 59 call sites, found " + safeResult.callSites());

        ProfileResult aggressiveResult = verifyProfile(
                "aggressive", aggressive, originals, true);
        require(aggressiveResult.classes() == 24,
                "aggressive profile must patch exactly 24 classes, found "
                        + aggressiveResult.classes());
        require(aggressiveResult.callSites() == 71,
                "aggressive profile must patch exactly 71 call sites, found "
                        + aggressiveResult.callSites());

        verifyDisabledGroups(originals);
        verifyContainerGuard(originals, coreJar, apiJar);
        verifyHashMismatch(safe, originals);
        verifyTransformerChain(originals);

        System.out.println("OK fast-forward presentation exact targets=" + EXPECTED.size()
                + " safeClasses=" + safeResult.classes()
                + " safeCallSites=" + safeResult.callSites()
                + " aggressiveClasses=" + aggressiveResult.classes()
                + " aggressiveCallSites=" + aggressiveResult.callSites()
                + " chainedTargets=" + CHAIN_MARKERS.size());
    }

    private static ProfileResult verifyProfile(String label, PrepatcherConfig config,
                                                Map<String, byte[]> originals,
                                                boolean aggressive) throws Exception {
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config);
        require(transformer.targetClassesForTest().equals(EXPECTED.keySet()),
                label + " target inventory changed: " + transformer.targetClassesForTest());

        int classes = 0;
        int callSites = 0;
        for (Map.Entry<String, Expected> entry : EXPECTED.entrySet()) {
            String name = entry.getKey();
            Expected expected = entry.getValue();
            require(expected.jar().equals(transformer.targetJarForTest(name)),
                    name + " container changed: " + transformer.targetJarForTest(name));
            byte[] original = originals.get(name);
            require(countPresentationCalls(original) == 0,
                    name + " already contains presentation hooks in the game JAR");

            byte[] transformed = transformer.transformBytesForTest(name, original);
            int expectedCalls = aggressive ? expected.aggressiveCalls() : expected.safeCalls();
            if (expectedCalls == 0) {
                require(transformed == null,
                        label + " disabled group unexpectedly transformed " + name);
                continue;
            }

            require(transformed != null, label + " did not transform " + name);
            require(classVersion(original) == classVersion(transformed),
                    label + " changed class version for " + name);
            int actualCalls = countPresentationCalls(transformed);
            require(actualCalls == expectedCalls,
                    label + " " + name + ": expected " + expectedCalls
                            + " presentation calls, found " + actualCalls);
            verifyMethods(transformed);
            classes++;
            callSites += actualCalls;
        }
        return new ProfileResult(classes, callSites);
    }

    private static void verifyDisabledGroups(Map<String, byte[]> originals) throws Exception {
        Properties noGroupsProperties = profileProperties(false);
        setPresentationGroups(noGroupsProperties, false);
        PrepatcherConfig noGroups = config(noGroupsProperties);
        FastForwardPresentationTransformer noGroupsTransformer =
                new FastForwardPresentationTransformer(noGroups);
        for (Map.Entry<String, byte[]> entry : originals.entrySet()) {
            require(!noGroupsTransformer.isTargetEnabled(entry.getKey()),
                    "all-disabled configuration enabled " + entry.getKey());
            require(noGroupsTransformer.transformBytesForTest(
                            entry.getKey(), entry.getValue()) == null,
                    "all-disabled configuration transformed " + entry.getKey());
        }

        Properties masterOffProperties = profileProperties(true);
        masterOffProperties.setProperty("patch.fastForwardPresentation", "false");
        FastForwardPresentationTransformer masterOff =
                new FastForwardPresentationTransformer(config(masterOffProperties));
        for (Map.Entry<String, byte[]> entry : originals.entrySet()) {
            require(masterOff.transform(null, entry.getKey(), null, null,
                            entry.getValue()) == null,
                    "master-disabled configuration transformed " + entry.getKey());
        }
    }

    private static void verifyHashMismatch(PrepatcherConfig config,
                                           Map<String, byte[]> originals) {
        String name = "com/fs/starfarer/campaign/CampaignState";
        byte[] changed = originals.get(name).clone();
        changed[changed.length - 1] ^= 1;
        FastForwardPresentationTransformer transformer =
                new FastForwardPresentationTransformer(config);

        try {
            transformer.transformBytesForTest(name, changed);
            throw new AssertionError("test entry point accepted a class hash mismatch");
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage().contains("class hash mismatch"),
                    "unexpected hash-mismatch exception: " + expected);
        }

        String statusKey = "starsector.prepatcher.patchStatus."
                + name.replace('/', '.') + ".fastForwardPresentation";
        System.clearProperty(statusKey);
        require(transformer.transform(null, name, null, null, changed) == null,
                "runtime transformer did not fail open on a class hash mismatch");
        require("SKIPPED_CLASS_HASH".equals(System.getProperty(statusKey)),
                "runtime transformer did not report SKIPPED_CLASS_HASH");
    }

    private static void verifyContainerGuard(Map<String, byte[]> originals,
                                             Path coreJar, Path apiJar)
            throws Exception {
        String coreName = "com/fs/starfarer/campaign/CampaignState";
        String apiName =
                "com/fs/starfarer/api/impl/campaign/terrain/StarCoronaTerrainPlugin";
        String coreStatus = statusKey(coreName);
        String apiStatus = statusKey(apiName);

        Properties guardedProperties = profileProperties(false);
        guardedProperties.setProperty("fastForward.guardJar", "true");
        FastForwardPresentationTransformer guarded =
                new FastForwardPresentationTransformer(config(guardedProperties));

        System.clearProperty(coreStatus);
        require(guarded.transform(null, coreName, null, null,
                        originals.get(coreName)) == null,
                "container guard accepted a null ProtectionDomain");
        require("SKIPPED_CONTAINER_HASH".equals(System.getProperty(coreStatus)),
                "null ProtectionDomain did not report SKIPPED_CONTAINER_HASH");

        System.clearProperty(coreStatus);
        require(guarded.transform(null, coreName, null, domain(apiJar),
                        originals.get(coreName)) == null,
                "container guard accepted the API JAR for a core target");
        require("SKIPPED_CONTAINER_HASH".equals(System.getProperty(coreStatus)),
                "wrong container did not report SKIPPED_CONTAINER_HASH");

        System.clearProperty(coreStatus);
        byte[] guardedCore = guarded.transform(null, coreName, null, domain(coreJar),
                originals.get(coreName));
        require(guardedCore != null && countPresentationCalls(guardedCore) == 3,
                "valid core container hash did not pass the guard");
        require("APPLIED".equals(System.getProperty(coreStatus)),
                "valid core container did not report APPLIED");

        System.clearProperty(apiStatus);
        byte[] guardedApi = guarded.transform(null, apiName, null, domain(apiJar),
                originals.get(apiName));
        require(guardedApi != null && countPresentationCalls(guardedApi) == 1,
                "valid API container hash did not pass the guard");
        require("APPLIED".equals(System.getProperty(apiStatus)),
                "valid API container did not report APPLIED");

        ClassLoader resourceOnlyLoader = resourceLoader(coreJar, coreName + ".class");
        System.clearProperty(coreStatus);
        require(guarded.transform(resourceOnlyLoader, coreName, null, domain(apiJar),
                        originals.get(coreName)) == null,
                "defining-loader resource bypassed an exposed wrong CodeSource");
        require("SKIPPED_CONTAINER_HASH".equals(System.getProperty(coreStatus)),
                "wrong CodeSource with a valid resource did not remain fail-closed");

        System.clearProperty(coreStatus);
        byte[] resourceGuardedCore = guarded.transform(resourceOnlyLoader, coreName,
                null, null, originals.get(coreName));
        require(resourceGuardedCore != null
                        && countPresentationCalls(resourceGuardedCore) == 3,
                "valid defining-loader resource did not replace a missing CodeSource");
        require("APPLIED".equals(System.getProperty(coreStatus)),
                "valid defining-loader resource did not report APPLIED");

        Properties unguardedProperties = profileProperties(false);
        unguardedProperties.setProperty("fastForward.guardJar", "false");
        FastForwardPresentationTransformer unguarded =
                new FastForwardPresentationTransformer(config(unguardedProperties));
        require(unguarded.transform(null, coreName, null, null,
                        originals.get(coreName)) != null,
                "guardJar=false did not bypass the missing container domain");
    }

    private static ProtectionDomain domain(Path jar) throws Exception {
        CodeSource source = new CodeSource(
                jar.toUri().toURL(), (Certificate[]) null);
        return new ProtectionDomain(source, null);
    }

    private static ClassLoader resourceLoader(Path jar, String resourceName)
            throws Exception {
        URL resource = new URL("jar:" + jar.toUri().toURL() + "!/" + resourceName);
        return new ClassLoader(null) {
            @Override
            public URL getResource(String name) {
                return resourceName.equals(name) ? resource : null;
            }
        };
    }

    private static String statusKey(String name) {
        return "starsector.prepatcher.patchStatus."
                + name.replace('/', '.') + ".fastForwardPresentation";
    }

    private static void verifyTransformerChain(Map<String, byte[]> originals)
            throws Exception {
        PrepatcherConfig config = config(chainProperties());
        FastForwardPresentationTransformer presentation =
                new FastForwardPresentationTransformer(config);
        Set<String> overlaps = new LinkedHashSet<>(
                presentation.targetClassesForTest());
        overlaps.retainAll(PrepatcherTransformer.TARGET_CLASSES);
        require(overlaps.equals(CHAIN_MARKERS.keySet()),
                "presentation/structural target intersection changed: " + overlaps);

        for (Map.Entry<String, Set<String>> entry : CHAIN_MARKERS.entrySet()) {
            String name = entry.getKey();
            byte[] original = originals.get(name);
            byte[] presentationBytes = presentation.transformBytesForTest(name, original);
            require(presentationBytes != null,
                    "presentation stage did not transform overlapping target " + name);
            int presentationCalls = countPresentationCalls(presentationBytes);
            require(presentationCalls == EXPECTED.get(name).safeCalls(),
                    "presentation stage call count changed for " + name);

            byte[] direct = new PrepatcherTransformer(config).transform(
                    null, name, null, null, original);
            require(direct != null,
                    "structural transformer did not transform vanilla overlap " + name);

            byte[] chained = new PrepatcherTransformer(config).transform(
                    null, name, null, null, presentationBytes);
            require(chained != null,
                    "structural transformer rejected presentation-patched overlap " + name);
            require(countPresentationCalls(chained) == presentationCalls,
                    "structural transformer lost presentation hooks in " + name);
            require(patchMarkers(direct).equals(entry.getValue()),
                    "unexpected direct structural markers in " + name + ": "
                            + patchMarkers(direct));
            require(patchMarkers(chained).equals(entry.getValue()),
                    "chained structural markers changed in " + name + ": "
                            + patchMarkers(chained));
            require(prepatcherHookCalls(chained).equals(prepatcherHookCalls(direct)),
                    "chained structural hook postcondition differs in " + name
                            + ": direct=" + prepatcherHookCalls(direct)
                            + ", chained=" + prepatcherHookCalls(chained));
            require(classVersion(original) == classVersion(chained),
                    "transformer chain changed class version for " + name);
            verifyMethods(chained);
        }
    }

    private static Properties chainProperties() {
        Properties properties = profileProperties(false);

        // Exercise one stable existing structural patch on each overlap. The
        // CampaignEngine lifecycle companion is a required dependency of its
        // listener throttle and is therefore expected as a second marker.
        properties.setProperty("patch.campaignListenerThrottle", "true");
        properties.setProperty("patch.campaignSnapshotReuse", "true");
        properties.setProperty("patch.entityScriptSnapshotReuse", "true");
        properties.setProperty("patch.skipNoOpTerrainLayer", "true");
        properties.setProperty("patch.terrainRandomReuse", "false");
        properties.setProperty("patch.automatonBufferReuse", "false");

        properties.setProperty("patch.labelSpatialCandidates", "false");
        properties.setProperty("patch.intelCallbackCache", "false");
        properties.setProperty("patch.intelEntityIndex", "false");
        properties.setProperty("patch.hoverHitTestCache", "false");
        properties.setProperty("patch.systemNebulaCache", "false");
        properties.setProperty("patch.sampleCacheClearThrottle", "false");
        properties.setProperty("patch.routeJumpPointIndex", "false");
        properties.setProperty("patch.economyLocationCache", "false");
        properties.setProperty("patch.remoteMarketScheduler", "false");
        properties.setProperty("patch.planetConditionMarketScheduler", "false");
        properties.setProperty("patch.directMarketObservation", "false");
        properties.setProperty("patch.commRelaySystemIndex", "false");
        properties.setProperty("patch.commodityTemporalFastPath", "false");
        return properties;
    }

    private static Map<String, byte[]> readOriginals(Path corePath, Path apiPath)
            throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (JarFile core = new JarFile(corePath.toFile());
             JarFile api = new JarFile(apiPath.toFile())) {
            for (Map.Entry<String, Expected> entry : EXPECTED.entrySet()) {
                JarFile jar = entry.getValue().jar().equals("starfarer_obf.jar")
                        ? core : api;
                var jarEntry = jar.getJarEntry(entry.getKey() + ".class");
                require(jarEntry != null,
                        "target class missing from " + entry.getValue().jar()
                                + ": " + entry.getKey());
                byte[] bytes;
                try (var input = jar.getInputStream(jarEntry)) {
                    bytes = input.readAllBytes();
                }
                require(result.put(entry.getKey(), bytes) == null,
                        "duplicate target entry " + entry.getKey());
            }
        }
        require(result.size() == 24, "expected exactly 24 target classes");
        return result;
    }

    private static int countPresentationCalls(byte[] bytes) {
        ClassNode node = read(bytes);
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.equals(PRESENTATION_HOOKS)) count++;
            }
        }
        return count;
    }

    private static Map<String, Integer> prepatcherHookCalls(byte[] bytes) {
        Map<String, Integer> calls = new TreeMap<>();
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.owner.startsWith(PREPATCHER_HOOK_PREFIX)
                        && !call.owner.equals(PRESENTATION_HOOKS)) {
                    String key = call.owner + "." + call.name + call.desc;
                    calls.merge(key, 1, Integer::sum);
                }
            }
        }
        return calls;
    }

    private static Set<String> patchMarkers(byte[] bytes) {
        Set<String> markers = new LinkedHashSet<>();
        for (FieldNode field : read(bytes).fields) {
            if (field.name.startsWith(PATCH_MARKER_PREFIX)) markers.add(field.name);
        }
        return markers;
    }

    private static void verifyMethods(byte[] bytes) throws Exception {
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException failure) {
                throw new AssertionError("BasicVerifier rejected " + node.name + "."
                        + method.name + method.desc + ": " + failure.getMessage(), failure);
            }
        }
    }

    private static int classVersion(byte[] bytes) {
        return new ClassReader(bytes).readShort(6);
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static PrepatcherConfig config(Properties properties) throws Exception {
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static Properties profileProperties(boolean aggressive) {
        Properties properties = new Properties();
        properties.setProperty("patch.fastForwardPresentation", "true");
        properties.setProperty("patch.fastForwardFrameMarker", "true");
        properties.setProperty("fastForward.guardJar", "false");
        properties.setProperty("fastForward.verbose", "false");
        properties.setProperty("fastForward.metrics", "false");
        properties.setProperty("fastForward.visualTime", "realtime");
        setPresentationGroups(properties, true);
        properties.setProperty("patch.fastForwardGlobalAnimations",
                Boolean.toString(aggressive));
        properties.setProperty("patch.fastForwardSensorFaders",
                Boolean.toString(aggressive));
        properties.setProperty("patch.fastForwardSlipstreamParticles",
                Boolean.toString(aggressive));
        properties.setProperty("patch.fastForwardParticleEmitters",
                Boolean.toString(aggressive));
        return properties;
    }

    private static void setPresentationGroups(Properties properties, boolean enabled) {
        String value = Boolean.toString(enabled);
        properties.setProperty("patch.fastForwardActionIndicators", value);
        properties.setProperty("patch.fastForwardLocationVisuals", value);
        properties.setProperty("patch.fastForwardFloatingText", value);
        properties.setProperty("patch.fastForwardFleetView", value);
        properties.setProperty("patch.fastForwardFleetPresentation", value);
        properties.setProperty("patch.fastForwardSensorIndicators", value);
        properties.setProperty("patch.fastForwardCelestialVisuals", value);
        properties.setProperty("patch.fastForwardAuroraAnimation", value);
        properties.setProperty("patch.fastForwardContinuousSound", value);
        properties.setProperty("patch.fastForwardGateJitter", value);
        properties.setProperty("patch.fastForwardGlobalAnimations", value);
        properties.setProperty("patch.fastForwardSensorFaders", value);
        properties.setProperty("patch.fastForwardSlipstreamParticles", value);
        properties.setProperty("patch.fastForwardParticleEmitters", value);
    }

    private static Map<String, Expected> expectedTargets() {
        Map<String, Expected> result = new LinkedHashMap<>();
        add(result, "com/fs/starfarer/campaign/CampaignState", "starfarer_obf.jar", 3, 3);
        add(result, "com/fs/starfarer/campaign/CampaignEngine", "starfarer_obf.jar", 1, 3);
        add(result, "com/fs/starfarer/campaign/BaseLocation", "starfarer_obf.jar", 3, 3);
        add(result, "com/fs/starfarer/campaign/BaseCampaignEntity", "starfarer_obf.jar", 3, 5);
        add(result, "com/fs/starfarer/campaign/fleet/CampaignFleet", "starfarer_obf.jar", 5, 5);
        add(result, "com/fs/starfarer/campaign/SensorContactIndicatorManager", "starfarer_obf.jar", 1, 1);
        add(result, "com/fs/starfarer/campaign/CampaignPlanet", "starfarer_obf.jar", 1, 1);
        add(result, "com/fs/starfarer/campaign/JumpPoint", "starfarer_obf.jar", 5, 5);

        add(result, "com/fs/starfarer/api/impl/campaign/terrain/StarCoronaTerrainPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/MagneticFieldTerrainPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/SpatialAnomalyTerrainPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/RadioChatterTerrainPlugin", "starfarer.api.jar", 2, 2);
        add(result, "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2", "starfarer.api.jar", 4, 6);
        add(result, "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2", "starfarer.api.jar", 0, 2);
        add(result, "com/fs/starfarer/api/impl/campaign/terrain/BaseTerrain", "starfarer.api.jar", 8, 8);
        add(result, "com/fs/starfarer/api/impl/campaign/abilities/BaseDurationAbility", "starfarer.api.jar", 4, 4);
        add(result, "com/fs/starfarer/api/impl/campaign/abilities/BaseToggleAbility", "starfarer.api.jar", 4, 4);
        add(result, "com/fs/starfarer/api/impl/campaign/abilities/GenerateSlipsurgeAbility$SlipsurgeEffectScript", "starfarer.api.jar", 2, 2);
        add(result, "com/fs/starfarer/api/impl/campaign/entities/GateHaulerEntityPlugin", "starfarer.api.jar", 1, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/GateEntityPlugin", "starfarer.api.jar", 8, 9);
        add(result, "com/fs/starfarer/api/impl/campaign/world/MoteParticleScript", "starfarer.api.jar", 0, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/CoronalTapParticleScript", "starfarer.api.jar", 0, 1);
        add(result, "com/fs/starfarer/api/impl/campaign/world/ZigLeashAssignmentAI", "starfarer.api.jar", 0, 1);
        return Map.copyOf(result);
    }

    private static void add(Map<String, Expected> targets, String name, String jar,
                            int safeCalls, int aggressiveCalls) {
        require(targets.put(name, new Expected(jar, safeCalls, aggressiveCalls)) == null,
                "duplicate expected target " + name);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Expected(String jar, int safeCalls, int aggressiveCalls) {}
    private record ProfileResult(int classes, int callSites) {}
}
