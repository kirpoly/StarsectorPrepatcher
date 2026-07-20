package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Exact-build presentation coalescing imported from FastForward Presentation
 * Patch 1.1.0. This transformer deliberately runs before the structural
 * prepatcher transformer so its whole-class hashes always see vanilla bytes.
 */
public final class FastForwardPresentationTransformer implements ClassFileTransformer {
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherPresentationHooks";

    private enum Container {
        CORE("starfarer_obf.jar", "5dd222b9e266d2ac2d63b3dad4983eb05caaf5a247d7dfb82aaeba47ea774cc8"),
        API("starfarer.api.jar", "a7ba18f3476ffe704729bd0a7a47443f035fea98a32ac2930eae8b391d013c2a");

        final String fileName;
        final String sha256;
        Container(String fileName, String sha256) {
            this.fileName = fileName;
            this.sha256 = sha256;
        }
    }

    private record Target(String classSha256, Container container) {}

    private static final Map<String, Target> TARGETS;
    static final Set<String> TARGET_CLASSES;
    static {
        Map<String, Target> hashes = new HashMap<>();
        hashes.put("com/fs/starfarer/campaign/CampaignState", new Target("3823d61bdc91484248c5aecf58fff7726d38e498bccf9884eca1a53b668be431", Container.CORE));
        hashes.put("com/fs/starfarer/campaign/CampaignEngine", new Target("9888ba1d2493f8d1d41106b57d9843c266f7073355aa9d2c41e73c14c3527cab", Container.CORE));
        hashes.put("com/fs/starfarer/campaign/BaseLocation", new Target("76b19cd60a3e1d8c9dbcd1cccc78dc8587ed1c3ae1539089f54625a93bc8fe38", Container.CORE));
        hashes.put("com/fs/starfarer/campaign/BaseCampaignEntity", new Target("e1fc45909ea1300d4b82aa4a22f22af5fb01fe327136e47abf3fe3574ee031f7", Container.CORE));
        hashes.put("com/fs/starfarer/campaign/fleet/CampaignFleet", new Target("7707b678ee754fa256c270743a9243e7a4398e75707c8b6b085a2324ef25d341", Container.CORE));
        hashes.put("com/fs/starfarer/campaign/SensorContactIndicatorManager", new Target("34069cd7728684a08f13cb897f80bc810d8c16eb31e0e3bb9b16ca05c97226c8", Container.CORE));
        hashes.put("com/fs/starfarer/campaign/CampaignPlanet", new Target("377a6778c2db17a66cb707c2f040b5bad45ad8e5cc52e0316ed7cf982ee30b0d", Container.CORE));
        hashes.put("com/fs/starfarer/campaign/JumpPoint", new Target("047d460ae7c48acdd031ba2f285a307a4f6bb55336379f0a8c197ae6159deee2", Container.CORE));

        hashes.put("com/fs/starfarer/api/impl/campaign/terrain/StarCoronaTerrainPlugin", new Target("a500462f64b1f2a4984df6df10d64d4a715307f021942f907abf8e4b09d25659", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/terrain/MagneticFieldTerrainPlugin", new Target("318dfc03b0f67ac78133e87f5085dbd52e133675805b0564bdaf4a8b818f6c98", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/terrain/SpatialAnomalyTerrainPlugin", new Target("f9e680a61b5f1d814d2e688b1f8fcbcdccbfb47938f511d85d646338d173e202", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin", new Target("40f504bdca41cf45fa223ec6d9a201a17945f624dad36300f7b40d1161916931", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/terrain/RadioChatterTerrainPlugin", new Target("80b54746bb0622536a7ab5be8c6d08d9c6599b98cf5ccfc57e8c0e5454242c53", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2", new Target("3b87fc4796bed4a3658285d51c3aade36968c0b9b2a08e4cecee7f3c0004df7a", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2", new Target("cea817e302adc9535daadf41aaa801fa555d48f6dc621b94dcf94899bb67c2f3", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/terrain/BaseTerrain", new Target("cfa0ef70bd4ffcbc593e6788086f704a544c6ff46990a02cf11ef4cb75611287", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/abilities/BaseDurationAbility", new Target("1c13ce637fd278e9a4caa9dfaa08e7dec8aeb3800192887f5047d21a56f56bbc", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/abilities/BaseToggleAbility", new Target("d41dac331c420400b6a8b2a8c7dd9a8e65bbd85c3c02ad8ea3add4d5c0ac7b8a", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/abilities/GenerateSlipsurgeAbility$SlipsurgeEffectScript", new Target("dca1ed72b810db22c35802029ef4ec014a2afa322c56e712d13f239463771c69", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/entities/GateHaulerEntityPlugin", new Target("737233beac85d7e6fd4d59ac4497070ce7fdb8f0ab7a12f5eac5e4c3cebfac92", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/GateEntityPlugin", new Target("48f0b2d2a6ca25ef8d9f07d86bcc80eb81ad3aa9450e002ec83f1baaaa04a8e5", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/world/MoteParticleScript", new Target("ba3cc6fa3000b67def30fa00739e6c3bdbc5c662e7dcd36b9f2c94c381c61ff3", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/CoronalTapParticleScript", new Target("b5843c7d02b58a93f89dacb5243d6becd9f622802930d2bd8b8c314569dafff7", Container.API));
        hashes.put("com/fs/starfarer/api/impl/campaign/world/ZigLeashAssignmentAI", new Target("f34c810493ad970bf7d842af07c01becf71a73d4fcdbe3db83b11a67218238d5", Container.API));
        TARGETS = Collections.unmodifiableMap(hashes);
        TARGET_CLASSES = TARGETS.keySet();
    }

    private static final Map<Path, String> CONTAINER_HASH_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private final PrepatcherConfig config;
    private final ClassLoader runtimeLoader;

    public FastForwardPresentationTransformer(PrepatcherConfig config) {
        this(config, null);
    }

    public FastForwardPresentationTransformer(PrepatcherConfig config,
                                              ClassLoader runtimeLoader) {
        this.config = config;
        this.runtimeLoader = runtimeLoader;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        Target target;
        if (className == null || (target = TARGETS.get(className)) == null
                || !isTargetEnabled(className)) {
            return null;
        }
        if (runtimeLoader != null && loader != runtimeLoader) {
            recordStatus(className, "SKIPPED_LOADER");
            logOnce(className + ":loader", "Fast-forward presentation patch skipped "
                    + className + ": target loader=" + loaderName(loader)
                    + ", runtime loader=" + loaderName(runtimeLoader));
            return null;
        }
        try {
            if (!sha256(classfileBuffer).equals(target.classSha256())) {
                recordStatus(className, "SKIPPED_CLASS_HASH");
                logOnce(className + ":hash", "Fast-forward presentation patch skipped "
                        + className + ": class SHA-256 mismatch");
                return null;
            }
            if (config.fastForwardGuardJar
                    && !validContainer(loader, className, classfileBuffer,
                    protectionDomain, target.container())) {
                recordStatus(className, "SKIPPED_CONTAINER_HASH");
                logOnce(className + ":jar", "Fast-forward presentation patch skipped "
                        + className + ": "
                        + target.container().fileName + " guard failed");
                return null;
            }
            byte[] transformed = transformVerified(className, classfileBuffer);
            if (transformed != null) {
                recordStatus(className, "APPLIED");
                if (config.fastForwardVerbose) {
                    PrepatcherLog.info("APPLIED fast-forward presentation coalescing to "
                            + className + ", bytes " + classfileBuffer.length
                            + " -> " + transformed.length);
                }
            }
            return transformed;
        } catch (Throwable failure) {
            recordStatus(className, "SKIPPED_ERROR");
            PrepatcherLog.error("Fast-forward presentation patch failed for "
                    + className + "; using vanilla bytes", failure);
            return null;
        }
    }

    /** Test entry point: exact class hash is required; container check is bypassed. */
    public byte[] transformBytesForTest(String className, byte[] classfileBuffer) {
        Target target = TARGETS.get(className);
        if (target == null) throw new IllegalArgumentException("not a target: " + className);
        if (!sha256(classfileBuffer).equals(target.classSha256())) {
            throw new IllegalArgumentException("class hash mismatch: " + className);
        }
        return transformVerified(className, classfileBuffer);
    }

    public Set<String> targetClassesForTest() {
        return TARGETS.keySet();
    }

    public String targetJarForTest(String className) {
        Target target = TARGETS.get(className);
        if (target == null) throw new IllegalArgumentException("not a target: " + className);
        return target.container().fileName;
    }

    private byte[] transformVerified(String className, byte[] original) {
        if (className.equals("com/fs/starfarer/campaign/CampaignState")
                && (!config.fastForwardFrameMarker || !anyPresentationPatch())) {
            return null;
        }

        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(original).accept(node, 0);
        int changes = switch (className) {
            case "com/fs/starfarer/campaign/CampaignState" -> patchCampaignState(node);
            case "com/fs/starfarer/campaign/CampaignEngine" -> patchCampaignEngine(node);
            case "com/fs/starfarer/campaign/BaseLocation" -> patchBaseLocation(node);
            case "com/fs/starfarer/campaign/BaseCampaignEntity" -> patchBaseCampaignEntity(node);
            case "com/fs/starfarer/campaign/fleet/CampaignFleet" -> patchCampaignFleet(node);
            case "com/fs/starfarer/campaign/SensorContactIndicatorManager" -> patchSensorContactManager(node);
            case "com/fs/starfarer/campaign/CampaignPlanet" -> patchCampaignPlanet(node);
            case "com/fs/starfarer/campaign/JumpPoint" -> patchJumpPoint(node);

            case "com/fs/starfarer/api/impl/campaign/terrain/StarCoronaTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/MagneticFieldTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/SpatialAnomalyTerrainPlugin" -> patchAuroraCaller(node);
            case "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin" -> patchHyperspaceTerrain(node);
            case "com/fs/starfarer/api/impl/campaign/terrain/RadioChatterTerrainPlugin" -> patchRadioChatter(node);
            case "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2" -> patchSlipstreamTerrain(node);
            case "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2" -> patchSlipstreamEntity(node);
            case "com/fs/starfarer/api/impl/campaign/terrain/BaseTerrain" -> patchBaseTerrain(node);
            case "com/fs/starfarer/api/impl/campaign/abilities/BaseDurationAbility",
                 "com/fs/starfarer/api/impl/campaign/abilities/BaseToggleAbility" -> patchBaseAbility(node);
            case "com/fs/starfarer/api/impl/campaign/abilities/GenerateSlipsurgeAbility$SlipsurgeEffectScript" -> patchSlipsurge(node);
            case "com/fs/starfarer/api/impl/campaign/entities/GateHaulerEntityPlugin" -> patchGateHauler(node);
            case "com/fs/starfarer/api/impl/campaign/GateEntityPlugin" -> patchGate(node);
            case "com/fs/starfarer/api/impl/campaign/world/MoteParticleScript",
                 "com/fs/starfarer/api/impl/campaign/CoronalTapParticleScript",
                 "com/fs/starfarer/api/impl/campaign/world/ZigLeashAssignmentAI" -> patchEmitter(node);
            default -> 0;
        };
        if (changes == 0) return null;

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private int patchCampaignState(ClassNode node) {
        MethodNode method = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        java.util.List<MethodInsnNode> flagCalls = findCalls(method,
                "com/fs/starfarer/campaign/CampaignEngine", "setFastForwardIteration", "(Z)V");
        java.util.List<MethodInsnNode> advanceCalls = findCalls(method,
                "com/fs/starfarer/campaign/CampaignEngine", "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        requireCount(node.name + ".advance setFastForwardIteration", flagCalls.size(), 3);
        requireCount(node.name + ".advance CampaignEngine.advance", advanceCalls.size(), 1);
        if (method.maxLocals <= 13) throw new IllegalStateException("CampaignState.advance local layout changed");

        InsnList begin = new InsnList();
        begin.add(new VarInsnNode(Opcodes.ILOAD, 9));
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "beginOuterFrame", "(I)V", false));
        method.instructions.insertBefore(flagCalls.get(0), begin);

        InsnList beforeStep = new InsnList();
        beforeStep.add(new VarInsnNode(Opcodes.ILOAD, 13));
        beforeStep.add(new VarInsnNode(Opcodes.ILOAD, 9));
        beforeStep.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "beforeSubstep", "(II)V", false));
        method.instructions.insertBefore(advanceCalls.get(0), beforeStep);

        InsnList end = new InsnList();
        end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "endOuterFrame", "()V", false));
        method.instructions.insert(flagCalls.get(flagCalls.size() - 1), end);
        return 3;
    }

    private int patchCampaignEngine(ClassNode node) {
        MethodNode method = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        int changes = 0;
        if (config.fastForwardActionIndicators) {
            changes += replaceCalls(node.name, method,
                    "com/fs/starfarer/campaign/ActionIndicator", "advance", "(F)V", 1,
                    "advanceActionIndicator", "(Lcom/fs/starfarer/campaign/ActionIndicator;F)V");
        }
        if (config.fastForwardGlobalAnimations) {
            changes += replaceCalls(node.name, method,
                    "com/fs/graphics/anim/AnimationManager", "advanceAll", "(F)V", 2,
                    "advanceAnimationManager", "(Lcom/fs/graphics/anim/AnimationManager;F)V");
        }
        return changes;
    }

    private int patchBaseLocation(ClassNode node) {
        if (!config.fastForwardLocationVisuals) return 0;
        MethodNode method = requireMethod(node, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
        int changes = 0;
        changes += replaceCalls(node.name, method,
                "com/fs/graphics/util/Fader", "advance", "(F)V", 1,
                "advanceLocationLightFader", "(Lcom/fs/graphics/util/Fader;F)V");
        changes += replaceCalls(node.name, method,
                "com/fs/starfarer/campaign/BackgroundAndStars", "advance", "(F)V", 1,
                "advanceBackground", "(Lcom/fs/starfarer/campaign/BackgroundAndStars;F)V");
        changes += replaceCalls(node.name, method,
                "com/fs/graphics/particle/DynamicParticleGroup", "advance", "(F)V", 1,
                "advanceParticleGroup", "(Lcom/fs/graphics/particle/DynamicParticleGroup;F)V");
        return changes;
    }

    private int patchBaseCampaignEntity(ClassNode node) {
        int changes = 0;
        MethodNode advance = requireMethod(node, "advance", "(F)V");
        MethodNode paused = requireMethod(node, "advanceEvenIfPaused", "(F)V");
        if (config.fastForwardFloatingText) {
            changes += replaceCalls(node.name, advance,
                    "com/fs/starfarer/campaign/fleet/CampaignFloatingText", "advance", "(F)V", 1,
                    "advanceFloatingText", "(Lcom/fs/starfarer/campaign/fleet/CampaignFloatingText;F)V");
            changes += replaceCalls(node.name, paused,
                    "com/fs/starfarer/campaign/fleet/CampaignFloatingText", "advance", "(F)V", 1,
                    "advanceFloatingText", "(Lcom/fs/starfarer/campaign/fleet/CampaignFloatingText;F)V");
        }
        if (config.fastForwardSensorIndicators) {
            changes += replaceCalls(node.name, advance,
                    "com/fs/starfarer/campaign/ui/oOO0", "new", "(F)V", 1,
                    "advanceSelectionIndicator", "(Ljava/lang/Object;F)V");
        }
        if (config.fastForwardSensorFaders) {
            changes += replaceCalls(node.name, advance,
                    "com/fs/graphics/util/Fader", "advance", "(F)V", 2,
                    "advanceSensorFader", "(Lcom/fs/graphics/util/Fader;F)V");
        }
        return changes;
    }

    private int patchCampaignFleet(ClassNode node) {
        MethodNode method = requireMethod(node, "advance", "(F)V");
        int changes = 0;
        if (config.fastForwardFleetView) {
            changes += replaceCalls(node.name, method,
                    "com/fs/starfarer/campaign/fleet/CampaignFleetView", "advance", "(F)V", 1,
                    "advanceFleetView", "(Lcom/fs/starfarer/campaign/fleet/CampaignFleetView;F)V");
        }
        if (config.fastForwardFleetPresentation) {
            changes += replaceCalls(node.name, method,
                    "com/fs/starfarer/campaign/fleet/FleetAbilityRenderer", "updateLayers", "()V", 1,
                    "updateFleetAbilityLayers", "(Lcom/fs/starfarer/campaign/fleet/FleetAbilityRenderer;)V");
            changes += replaceCalls(node.name, method,
                    "com/fs/starfarer/campaign/fleet/CampaignFleetView", "clear", "()V", 1,
                    "clearFleetView", "(Lcom/fs/starfarer/campaign/fleet/CampaignFleetView;)V");
            changes += replaceCalls(node.name, method,
                    "com/fs/starfarer/campaign/E", "Ò00000", "(F)V", 1,
                    "advanceSensorRangeIndicator", "(Lcom/fs/starfarer/campaign/E;F)V");
            java.util.List<MethodInsnNode> fleetFaders = findCalls(method,
                    "com/fs/graphics/util/Fader", "advance", "(F)V");
            requireCount(node.name + ".advance fleet Faders", fleetFaders.size(), 2);
            replaceOneCall(fleetFaders.get(1), "advanceFleetPulseFader",
                    "(Lcom/fs/graphics/util/Fader;F)V");
            changes++;
        }
        return changes;
    }

    private int patchSensorContactManager(ClassNode node) {
        if (!config.fastForwardSensorIndicators) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceCalls(node.name, method,
                "com/fs/starfarer/campaign/SensorContactIndicatorManager$SensorContactIndicator",
                "advance", "(F)V", 1,
                "advanceSensorContactIndicator",
                "(Lcom/fs/starfarer/campaign/SensorContactIndicatorManager$SensorContactIndicator;F)V");
    }

    private int patchCampaignPlanet(ClassNode node) {
        if (!config.fastForwardCelestialVisuals) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceCalls(node.name, method,
                "com/fs/starfarer/combat/entities/terrain/Planet", "advance", "(F)V", 1,
                "advancePlanetGraphics", "(Lcom/fs/starfarer/combat/entities/terrain/Planet;F)V");
    }

    private int patchJumpPoint(ClassNode node) {
        if (!config.fastForwardCelestialVisuals) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        int changes = 0;
        changes += replaceCalls(node.name, method,
                "com/fs/starfarer/campaign/DynamicRingBand", "advance", "(F)V", 1,
                "advanceDynamicRingBand", "(Lcom/fs/starfarer/campaign/DynamicRingBand;F)V");
        changes += replaceCalls(node.name, method,
                "com/fs/starfarer/campaign/JumpPoint$RingData", "advance", "(F)V", 2,
                "advanceJumpRingData", "(Lcom/fs/starfarer/campaign/JumpPoint$RingData;F)V");
        changes += replaceCalls(node.name, method,
                "com/fs/starfarer/campaign/JumpPoint$CoronaData", "advance", "(F)V", 1,
                "advanceJumpCoronaData", "(Lcom/fs/starfarer/campaign/JumpPoint$CoronaData;F)V");
        changes += replaceCalls(node.name, method,
                "com/fs/starfarer/combat/entities/terrain/Planet", "advance", "(F)V", 1,
                "advancePlanetGraphics", "(Lcom/fs/starfarer/combat/entities/terrain/Planet;F)V");
        return changes;
    }

    private int patchAuroraCaller(ClassNode node) {
        if (!config.fastForwardAuroraAnimation) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceCalls(node.name, method,
                "com/fs/starfarer/api/impl/campaign/terrain/AuroraRenderer", "advance", "(F)V", 1,
                "advanceAurora", "(Lcom/fs/starfarer/api/impl/campaign/terrain/AuroraRenderer;F)V");
    }

    private int patchHyperspaceTerrain(ClassNode node) {
        if (!config.fastForwardContinuousSound) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceLowPass(node.name, method, 1);
    }

    private int patchRadioChatter(ClassNode node) {
        if (!config.fastForwardContinuousSound) return 0;
        MethodNode method = requireMethod(node, "applyEffect",
                "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;F)V");
        return replaceSuppressMusic(node.name, method, 1)
                + replaceStandardPlayLoop(node.name, method, 1);
    }

    private int patchSlipstreamTerrain(ClassNode node) {
        int changes = 0;
        if (config.fastForwardSlipstreamParticles) {
            MethodNode advance = requireMethod(node, "advance", "(F)V");
            changes += replaceCalls(node.name, advance,
                    node.name, "addParticles", "()V", 1,
                    "addSlipstreamTerrainParticles",
                    "(Lcom/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2;)V");
            changes += replaceCalls(node.name, advance,
                    node.name, "advanceParticles", "(F)V", 1,
                    "advanceSlipstreamTerrainParticles",
                    "(Lcom/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2;F)V");
        }
        if (config.fastForwardContinuousSound) {
            MethodNode sound = requireMethod(node, "doSoundPlayback", "(F)V");
            changes += replaceLowPass(node.name, sound, 1);
            changes += replaceCalls(node.name, sound,
                    "com/fs/starfarer/api/SoundPlayerAPI", "playLoop",
                    "(Ljava/lang/String;Ljava/lang/Object;FFLorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;FF)V", 2,
                    "playLoopWithRange",
                    "(Lcom/fs/starfarer/api/SoundPlayerAPI;Ljava/lang/String;Ljava/lang/Object;FFLorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;FF)V");
            changes += replaceSuppressMusic(node.name, sound, 1);
        }
        return changes;
    }

    private int patchSlipstreamEntity(ClassNode node) {
        if (!config.fastForwardSlipstreamParticles) return 0;
        MethodNode advance = requireMethod(node, "advance", "(F)V");
        int changes = 0;
        changes += replaceCalls(node.name, advance,
                node.name, "addParticles", "()V", 1,
                "addSlipstreamEntityParticles",
                "(Lcom/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2;)V");
        changes += replaceCalls(node.name, advance,
                node.name, "advanceParticles", "(F)V", 1,
                "advanceSlipstreamEntityParticles",
                "(Lcom/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2;F)V");
        return changes;
    }

    private int patchBaseTerrain(ClassNode node) {
        if (!config.fastForwardContinuousSound) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceSuppressMusic(node.name, method, 4) + replaceStandardPlayLoop(node.name, method, 4);
    }

    private int patchBaseAbility(ClassNode node) {
        if (!config.fastForwardContinuousSound) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceSuppressMusic(node.name, method, 2) + replaceStandardPlayLoop(node.name, method, 2);
    }

    private int patchSlipsurge(ClassNode node) {
        if (!config.fastForwardContinuousSound) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceSuppressMusic(node.name, method, 1) + replaceStandardPlayLoop(node.name, method, 1);
    }

    private int patchGateHauler(ClassNode node) {
        if (!config.fastForwardContinuousSound) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceStandardPlayLoop(node.name, method, 1);
    }

    private int patchGate(ClassNode node) {
        int changes = 0;
        if (config.fastForwardContinuousSound) {
            MethodNode sound = requireMethod(node, "playProximityLoop", "()V");
            changes += replaceSuppressMusic(node.name, sound, 1);
            changes += replaceStandardPlayLoop(node.name, sound, 2);
        }
        MethodNode advance = requireMethod(node, "advance", "(F)V");
        if (config.fastForwardGateJitter) {
            changes += replaceCalls(node.name, advance,
                    "com/fs/starfarer/api/util/FaderUtil", "advance", "(F)V", 3,
                    "advanceGateFader", "(Lcom/fs/starfarer/api/util/FaderUtil;F)V");
            changes += replaceCalls(node.name, advance,
                    "com/fs/starfarer/api/util/WarpingSpriteRendererUtil", "advance", "(F)V", 1,
                    "advanceGateWarp", "(Lcom/fs/starfarer/api/util/WarpingSpriteRendererUtil;F)V");
            changes += replaceCalls(node.name, advance,
                    "com/fs/starfarer/api/util/JitterUtil", "updateSeed", "()V", 1,
                    "updateGateJitter", "(Lcom/fs/starfarer/api/util/JitterUtil;)V");
        }
        if (config.fastForwardParticleEmitters) {
            changes += replaceEmitterInterval(node.name, advance, 1);
        }
        return changes;
    }

    private int patchEmitter(ClassNode node) {
        if (!config.fastForwardParticleEmitters) return 0;
        MethodNode method = requireMethod(node, "advance", "(F)V");
        return replaceEmitterInterval(node.name, method, 1);
    }

    private static int replaceStandardPlayLoop(String className, MethodNode method, int expected) {
        return replaceCalls(className, method,
                "com/fs/starfarer/api/SoundPlayerAPI", "playLoop",
                "(Ljava/lang/String;Ljava/lang/Object;FFLorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)V", expected,
                "playLoop",
                "(Lcom/fs/starfarer/api/SoundPlayerAPI;Ljava/lang/String;Ljava/lang/Object;FFLorg/lwjgl/util/vector/Vector2f;Lorg/lwjgl/util/vector/Vector2f;)V");
    }

    private static int replaceSuppressMusic(String className, MethodNode method, int expected) {
        return replaceCalls(className, method,
                "com/fs/starfarer/api/campaign/CampaignUIAPI", "suppressMusic", "(F)V", expected,
                "suppressMusic", "(Lcom/fs/starfarer/api/campaign/CampaignUIAPI;F)V");
    }

    private static int replaceLowPass(String className, MethodNode method, int expected) {
        return replaceCalls(className, method,
                "com/fs/starfarer/api/SoundPlayerAPI", "applyLowPassFilter", "(FF)V", expected,
                "applyLowPassFilter", "(Lcom/fs/starfarer/api/SoundPlayerAPI;FF)V");
    }

    private static int replaceEmitterInterval(String className, MethodNode method, int expected) {
        return replaceCalls(className, method,
                "com/fs/starfarer/api/util/IntervalUtil", "advance", "(F)V", expected,
                "advanceEmitterInterval", "(Lcom/fs/starfarer/api/util/IntervalUtil;F)V");
    }

    private static void replaceOneCall(MethodInsnNode call,
                                       String wrapperName, String wrapperDescriptor) {
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.owner = RUNTIME;
        call.name = wrapperName;
        call.desc = wrapperDescriptor;
        call.itf = false;
    }

    private static int replaceCalls(String className, MethodNode method,
                                    String owner, String name, String descriptor, int expected,
                                    String wrapperName, String wrapperDescriptor) {
        java.util.List<MethodInsnNode> calls = findCalls(method, owner, name, descriptor);
        requireCount(className + "." + method.name + method.desc + " -> " + owner + "." + name,
                calls.size(), expected);
        for (MethodInsnNode call : calls) {
            call.setOpcode(Opcodes.INVOKESTATIC);
            call.owner = RUNTIME;
            call.name = wrapperName;
            call.desc = wrapperDescriptor;
            call.itf = false;
        }
        return calls.size();
    }

    private static java.util.List<MethodInsnNode> findCalls(MethodNode method,
                                                             String owner, String name, String descriptor) {
        java.util.ArrayList<MethodInsnNode> result = new java.util.ArrayList<>();
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals(owner)
                    && call.name.equals(name)
                    && call.desc.equals(descriptor)) {
                result.add(call);
            }
        }
        return result;
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) return method;
        }
        throw new IllegalStateException("method not found: " + node.name + "." + name + descriptor);
    }

    private static void requireCount(String what, int actual, int expected) {
        if (actual != expected) throw new IllegalStateException(what + ": expected " + expected + ", found " + actual);
    }

    boolean isTargetEnabled(String className) {
        if (!config.fastForwardEnabled || !config.fastForwardFrameMarker
                || !anyPresentationPatch()) {
            return false;
        }
        return switch (className) {
            case "com/fs/starfarer/campaign/CampaignState" -> true;
            case "com/fs/starfarer/campaign/CampaignEngine" ->
                    config.fastForwardActionIndicators || config.fastForwardGlobalAnimations;
            case "com/fs/starfarer/campaign/BaseLocation" ->
                    config.fastForwardLocationVisuals;
            case "com/fs/starfarer/campaign/BaseCampaignEntity" ->
                    config.fastForwardFloatingText || config.fastForwardSensorIndicators
                            || config.fastForwardSensorFaders;
            case "com/fs/starfarer/campaign/fleet/CampaignFleet" ->
                    config.fastForwardFleetView || config.fastForwardFleetPresentation;
            case "com/fs/starfarer/campaign/SensorContactIndicatorManager" ->
                    config.fastForwardSensorIndicators;
            case "com/fs/starfarer/campaign/CampaignPlanet",
                 "com/fs/starfarer/campaign/JumpPoint" ->
                    config.fastForwardCelestialVisuals;
            case "com/fs/starfarer/api/impl/campaign/terrain/StarCoronaTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/MagneticFieldTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/SpatialAnomalyTerrainPlugin" ->
                    config.fastForwardAuroraAnimation;
            case "com/fs/starfarer/api/impl/campaign/terrain/HyperspaceTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/RadioChatterTerrainPlugin",
                 "com/fs/starfarer/api/impl/campaign/terrain/BaseTerrain",
                 "com/fs/starfarer/api/impl/campaign/abilities/BaseDurationAbility",
                 "com/fs/starfarer/api/impl/campaign/abilities/BaseToggleAbility",
                 "com/fs/starfarer/api/impl/campaign/abilities/GenerateSlipsurgeAbility$SlipsurgeEffectScript",
                 "com/fs/starfarer/api/impl/campaign/entities/GateHaulerEntityPlugin" ->
                    config.fastForwardContinuousSound;
            case "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamTerrainPlugin2" ->
                    config.fastForwardContinuousSound
                            || config.fastForwardSlipstreamParticles;
            case "com/fs/starfarer/api/impl/campaign/velfield/SlipstreamEntityPlugin2" ->
                    config.fastForwardSlipstreamParticles;
            case "com/fs/starfarer/api/impl/campaign/GateEntityPlugin" ->
                    config.fastForwardContinuousSound || config.fastForwardGateJitter
                            || config.fastForwardParticleEmitters;
            case "com/fs/starfarer/api/impl/campaign/world/MoteParticleScript",
                 "com/fs/starfarer/api/impl/campaign/CoronalTapParticleScript",
                 "com/fs/starfarer/api/impl/campaign/world/ZigLeashAssignmentAI" ->
                    config.fastForwardParticleEmitters;
            default -> false;
        };
    }

    private boolean anyPresentationPatch() {
        return config.fastForwardActionIndicators || config.fastForwardLocationVisuals
                || config.fastForwardFloatingText || config.fastForwardFleetView
                || config.fastForwardFleetPresentation || config.fastForwardSensorIndicators
                || config.fastForwardCelestialVisuals || config.fastForwardAuroraAnimation
                || config.fastForwardContinuousSound || config.fastForwardGateJitter
                || config.fastForwardGlobalAnimations || config.fastForwardSensorFaders
                || config.fastForwardSlipstreamParticles || config.fastForwardParticleEmitters;
    }

    private static void recordStatus(String className, String status) {
        System.setProperty("starsector.prepatcher.patchStatus."
                + className.replace('/', '.') + ".fastForwardPresentation", status);
    }

    private static String loaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }

    private static boolean validContainer(ClassLoader loader, String className,
                                          byte[] classfileBuffer,
                                          ProtectionDomain protectionDomain,
                                          Container expected) {
        String resourceName = className + ".class";

        // Normal JVM loaders expose the defining JAR here.
        if (protectionDomain != null && protectionDomain.getCodeSource() != null
                && protectionDomain.getCodeSource().getLocation() != null) {
            try {
                Path codeSource = Path.of(
                                protectionDomain.getCodeSource().getLocation().toURI())
                        .toAbsolutePath().normalize();
                return matchesContainer(
                        codeSource, resourceName, classfileBuffer, expected);
            } catch (Throwable ignored) {
                // An exposed but unusable CodeSource is not safe to bypass.
                return false;
            }
        }

        // FasterRendering defines com.fs classes with a null CodeSource, but reads
        // their bytes through this same resource lookup. Resolve that resource to
        // its JAR and then verify both the entire container and the exact entry.
        try {
            URL resource = loader == null
                    ? ClassLoader.getSystemResource(resourceName)
                    : loader.getResource(resourceName);
            Path resourceJar = jarPath(resource, resourceName);
            return resourceJar != null && matchesContainer(
                    resourceJar, resourceName, classfileBuffer, expected);
        } catch (Throwable ignored) {
            // A loader that cannot expose its source remains fail-closed.
            return false;
        }
    }

    private static Path jarPath(URL resource, String expectedEntry) {
        try {
            if (resource == null || !"jar".equalsIgnoreCase(resource.getProtocol())) return null;
            JarURLConnection connection = (JarURLConnection) resource.openConnection();
            connection.setUseCaches(false);
            if (!expectedEntry.equals(connection.getEntryName())) return null;
            URL jarUrl = connection.getJarFileURL();
            if (!"file".equalsIgnoreCase(jarUrl.getProtocol())) return null;
            return Path.of(jarUrl.toURI()).toAbsolutePath().normalize();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean matchesContainer(Path path, String resourceName,
                                            byte[] classfileBuffer,
                                            Container expected) {
        try {
            if (!Files.isRegularFile(path)
                    || !path.getFileName().toString().equalsIgnoreCase(expected.fileName)) {
                return false;
            }
            String actual = CONTAINER_HASH_CACHE.computeIfAbsent(
                    path, FastForwardPresentationTransformer::hashFile);
            if (!actual.equals(expected.sha256)) return false;

            try (JarFile jar = new JarFile(path.toFile())) {
                JarEntry entry = jar.getJarEntry(resourceName);
                if (entry == null || entry.isDirectory()) return false;
                try (InputStream input = jar.getInputStream(entry)) {
                    return MessageDigest.isEqual(input.readAllBytes(), classfileBuffer);
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String hashFile(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[131072];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void logOnce(String key, String message) {
        if (LOGGED.add(key)) PrepatcherLog.warn(message);
    }
}
