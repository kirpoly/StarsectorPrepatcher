package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.AnnotationNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FieldInsnNode;
import jdk.internal.org.objectweb.asm.tree.FrameNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import jdk.internal.org.objectweb.asm.tree.JumpInsnNode;
import jdk.internal.org.objectweb.asm.tree.LabelNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.TypeInsnNode;
import jdk.internal.org.objectweb.asm.tree.TryCatchBlockNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.jar.JarFile;

/** In-memory regression harness; it never writes modified game classes. */
public final class StructuralCompatibilityTest {
    private static final String HOOKS = "com/starsector/prepatcher/runtime/PrepatcherHooks";
    private static final String HYPERSPACE_HOOKS =
            "com/starsector/prepatcher/hyperspace/HyperspaceHooks";

    private StructuralCompatibilityTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: StructuralCompatibilityTest <prepatcher.properties> <core.jar>...");
        }
        Path configPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path configRoot = configPath.getParent();
        if (!Files.isDirectory(configRoot.resolve("source"))
                && configRoot.getParent() != null
                && Files.isDirectory(configRoot.getParent().resolve("source"))) {
            configRoot = configRoot.getParent();
        }
        Path testLog = configRoot.resolve(".build").resolve("structural-test.log");
        Files.createDirectories(testLog.getParent());
        PrepatcherLog.initialize(testLog);
        PrepatcherConfig defaults = PrepatcherConfig.load(null);
        require(!defaults.loadingTextReader,
                "patch.loadingTextReader must remain disabled when configuration is missing");
        require(!defaults.startupLogAggregation,
                "patch.startupLogAggregation must remain disabled when configuration is missing");
        PrepatcherConfig config = PrepatcherConfig.load(configPath);

        int jars = 0;
        int classes = 0;
        int methods = 0;
        Set<String> foundTargets = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            Path jar = Path.of(args[i]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("JAR not found: " + jar);
            Result result = verifyJar(config, jar, foundTargets);
            jars++;
            classes += result.classes;
            methods += result.methods;
            System.out.println("OK structural jar=" + jar + " classes=" + result.classes
                    + " verifiedMethods=" + result.methods);
        }
        Set<String> missingTargets = new LinkedHashSet<>(PrepatcherTransformer.TARGET_CLASSES);
        missingTargets.removeAll(foundTargets);
        require(missingTargets.isEmpty(), "target classes missing from supplied JARs: " + missingTargets);
        require(foundTargets.size() == PrepatcherTransformer.TARGET_CLASSES.size(),
                "duplicate or unexpected target-class accounting");

        require(classes == PrepatcherTransformer.TARGET_CLASSES.size(),
                "not all optimizer target classes were found across supplied JARs: expected "
                        + PrepatcherTransformer.TARGET_CLASSES.size() + ", found " + classes);
        runNegativeRetainAllTests(config, Path.of(args[1]).toAbsolutePath().normalize());
        runNegativeLifecycleTests(config, Path.of(args[1]).toAbsolutePath().normalize());
        runExp6OwnershipTests(config, Path.of(args[1]).toAbsolutePath().normalize());
        runInlineFastPathNegativeTests(config,
                Path.of(args[1]).toAbsolutePath().normalize());
        runExp8OwnershipTests(config, Arrays.stream(args).skip(1)
                .map(value -> Path.of(value).toAbsolutePath().normalize()).toList());
        System.out.println("OK negative-tests retainAll missing ambiguous unrelated-call unrelated-change"
                + " marker-ownership scratch-scope-tamper wrapper-tamper wrapper-metadata idempotency"
                + " lifecycle-missing-write lifecycle-wrong-source exp6-marker-ownership"
                + " exp6-scratch-scope-tamper exp8-marker-ownership exp8-scratch-scope-tamper"
                + " exp8-late-postcondition-tamper entity-fastpath-prologue"
                + " entity-fastpath-epilogue memory-fastpath-source"
                + " memory-fastpath-order-placement inline-marker-receiver"
                + " inline-marker-branch inline-marker-order safe-missing-config-defaults");
        System.out.println("SUMMARY jars=" + jars + " transformedClasses=" + classes
                + " verifiedMethods=" + methods);
    }

    private static Result verifyJar(PrepatcherConfig config, Path jarPath,
                                    Set<String> foundTargets) throws Exception {
        int classes = 0;
        int methods = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String className : PrepatcherTransformer.TARGET_CLASSES) {
                var entry = jar.getJarEntry(className + ".class");
                if (entry == null) continue;
                require(foundTargets.add(className), "target class occurs in more than one supplied JAR: " + className);                byte[] original = jar.getInputStream(entry).readAllBytes();
                PrepatcherTransformer transformer = new PrepatcherTransformer(config);
                byte[] transformed = transformer.transform(null, className, null, null, original);
                require(transformed != null, "no patch applied to " + className + " from " + jarPath);
                assertExpectedHooks(className, transformed);
                assertHookTargetsExist(transformed);
                methods += verifyBytecode(transformed);
                int skippedBefore = integerProperty("starsector.prepatcher.skippedPatches");
                byte[] second = transformer.transform(null, className, null, null, transformed);
                require(second == null, "transform is not idempotent for " + className);
                require(integerProperty("starsector.prepatcher.skippedPatches") == skippedBefore,
                        "already-patched class was reported as structurally incompatible: " + className);
                classes++;
            }
        }
        return new Result(classes, methods);
    }

    private static void runNegativeRetainAllTests(PrepatcherConfig config, Path jarPath) throws Exception {
        byte[] h;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            h = jar.getInputStream(jar.getJarEntry(PrepatcherTransformer.H + ".class")).readAllBytes();
        }

        byte[] unrelated = mutateH(h, Mutation.UNRELATED_NOP);
        byte[] unrelatedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, unrelated);
        require(unrelatedResult != null, "unrelated H change blocked all patches");
        require(countHook(unrelatedResult, "retainAllFast") == 1,
                "unrelated H change blocked retainAllFast");
        verifyBytecode(unrelatedResult);

        byte[] unrelatedCall = mutateH(h, Mutation.UNRELATED_RETAIN);
        byte[] unrelatedCallResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, unrelatedCall);
        require(unrelatedCallResult != null, "unrelated retainAll call blocked all H patches");
        require(countHook(unrelatedCallResult, "retainAllFast") == 1,
                "unrelated retainAll call was confused with map reconciliation");
        verifyBytecode(unrelatedCallResult);

        byte[] missing = mutateH(h, Mutation.MISSING_RETAIN);
        byte[] missingResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, missing);
        require(missingResult != null, "other H patches were lost when retainAll was missing");
        require(countHook(missingResult, "retainAllFast") == 0,
                "missing retainAll site was patched unexpectedly");
        assertHNonRetainHooks(missingResult, "missing retainAll site");
        verifyBytecode(missingResult);

        byte[] ambiguous = mutateH(h, Mutation.DUPLICATE_RETAIN);
        byte[] ambiguousResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, ambiguous);
        require(ambiguousResult != null, "other H patches were lost for ambiguous retainAll");
        require(countHook(ambiguousResult, "retainAllFast") == 0,
                "ambiguous retainAll sites were patched unexpectedly");
        assertHNonRetainHooks(ambiguousResult, "ambiguous retainAll sites");
        verifyBytecode(ambiguousResult);

        runOwnershipAndWrapperTests(config, h);
    }

    private static void runNegativeLifecycleTests(PrepatcherConfig config, Path jarPath) throws Exception {
        byte[] campaign;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            campaign = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.CAMPAIGN_ENGINE + ".class")).readAllBytes();
        }

        ClassNode missingWrite = read(campaign);
        MethodNode reset = method(missingWrite, "resetInstance", "()V");
        FieldInsnNode resetWrite = singletonWrite(missingWrite, reset);
        reset.instructions.set(resetWrite, new InsnNode(Opcodes.POP));
        assertLifecycleRejectedButIndependentPatchSurvives(config, write(missingWrite),
                "missing resetInstance singleton write");

        ClassNode wrongSource = read(campaign);
        MethodNode set = method(wrongSource, "setInstance",
                "(L" + PrepatcherTransformer.CAMPAIGN_ENGINE + ";)V");
        FieldInsnNode setWrite = singletonWrite(wrongSource, set);
        AbstractInsnNode producer = previousMeaningful(setWrite);
        require(producer instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD,
                "setInstance singleton write producer changed in test input");
        set.instructions.set(producer, new InsnNode(Opcodes.ACONST_NULL));
        assertLifecycleRejectedButIndependentPatchSurvives(config, write(wrongSource),
                "wrong setInstance singleton source");
    }

    private static void runExp6OwnershipTests(PrepatcherConfig config, Path jarPath) throws Exception {
        Map<String, String> patches = new LinkedHashMap<>();
        patches.put(PrepatcherTransformer.BASE_LOCATION, "campaignSnapshotReuse");
        patches.put(PrepatcherTransformer.BASE_CAMPAIGN_ENTITY, "entityScriptSnapshotReuse");
        patches.put(PrepatcherTransformer.MEMORY, "emptyMemoryAdvanceFastPath");

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (Map.Entry<String, String> entry : patches.entrySet()) {
                String className = entry.getKey();
                String patchId = entry.getValue();
                byte[] original = jar.getInputStream(jar.getJarEntry(className + ".class")).readAllBytes();
                byte[] patched = new PrepatcherTransformer(config)
                        .transform(null, className, null, null, original);
                require(patched != null, className + " baseline transform failed in ownership test");

                ClassNode unowned = read(patched);
                require(unowned.fields.removeIf(field -> field.name.equals("smo$patched$" + patchId)),
                        className + " ownership marker was not emitted for " + patchId);
                byte[] result = new PrepatcherTransformer(config)
                        .transform(null, className, null, null, write(unowned));
                require(result == null, className + " foreign hook-shaped state was modified");
                require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                                "starsector.prepatcher.patchStatus."
                                        + className.replace('/', '.') + "." + patchId)),
                        className + " foreign hook-shaped state was not rejected structurally");

                if (className.equals(PrepatcherTransformer.BASE_LOCATION)) {
                    ClassNode unscoped = read(patched);
                    removeScratchScope(method(unscoped, "advance",
                            "(FLcom/fs/starfarer/util/A/new;)V"));
                    byte[] unscopedResult = new PrepatcherTransformer(config)
                            .transform(null, className, null, null, write(unscoped));
                    require(unscopedResult == null,
                            "BaseLocation snapshot hooks without an advance scope were modified");
                    require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                                    "starsector.prepatcher.patchStatus."
                                            + className.replace('/', '.') + "." + patchId)),
                            "BaseLocation snapshot hooks without an advance scope were accepted");
                }
            }
        }
    }

    private static void runInlineFastPathNegativeTests(
            PrepatcherConfig config, Path jarPath) throws Exception {
        byte[] entity;
        byte[] memory;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            entity = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.BASE_CAMPAIGN_ENTITY + ".class")).readAllBytes();
            memory = jar.getInputStream(jar.getJarEntry(
                    PrepatcherTransformer.MEMORY + ".class")).readAllBytes();
        }

        ClassNode prologue = read(entity);
        MethodNode prologueRun = method(prologue, "runScripts", "(F)V");
        prologueRun.instructions.insertBefore(prologueRun.instructions.getFirst(), systemGcCall());
        assertInlinePatchRejected(config, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                "entityScriptSnapshotReuse", write(prologue),
                "runScripts foreign entry prologue");

        ClassNode epilogue = read(entity);
        MethodNode epilogueRun = method(epilogue, "runScripts", "(F)V");
        AbstractInsnNode terminalReturn = null;
        for (AbstractInsnNode insn : epilogueRun.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.RETURN) terminalReturn = insn;
        }
        require(terminalReturn != null, "runScripts terminal return missing in test input");
        epilogueRun.instructions.insertBefore(terminalReturn, systemGcCall());
        assertInlinePatchRejected(config, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                "entityScriptSnapshotReuse", write(epilogue),
                "runScripts foreign terminal epilogue");

        ClassNode wrongSource = read(memory);
        MethodNode wrongSourceAdvance = method(wrongSource, "advance", "(F)V");
        MethodInsnNode values = uniqueCall(wrongSourceAdvance, Opcodes.INVOKEVIRTUAL,
                "java/util/LinkedHashMap", "values", "()Ljava/util/Collection;");
        AbstractInsnNode valuesReceiver = previousMeaningful(values);
        require(valuesReceiver instanceof FieldInsnNode requireField
                        && requireField.getOpcode() == Opcodes.GETFIELD
                        && requireField.name.equals("require"),
                "Memory.advance require.values source changed in test input");
        ((FieldInsnNode) valuesReceiver).name = "data";
        assertInlinePatchRejected(config, PrepatcherTransformer.MEMORY,
                "emptyMemoryAdvanceFastPath", write(wrongSource),
                "Memory.advance foreign require.values source");

        ClassNode displacedWork = read(memory);
        MethodNode displacedAdvance = method(displacedWork, "advance", "(F)V");
        MethodInsnNode convert = uniqueCall(displacedAdvance, Opcodes.INVOKEVIRTUAL,
                "com/fs/starfarer/campaign/CampaignClock", "convertToDays", "(F)F");
        displacedAdvance.instructions.insertBefore(convert, systemGcCall());
        assertInlinePatchRejected(config, PrepatcherTransformer.MEMORY,
                "emptyMemoryAdvanceFastPath", write(displacedWork),
                "Memory.advance foreign post-pause work in clock ordering");

        byte[] patchedEntity = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY, null, null, entity);
        require(patchedEntity != null, "runScripts baseline inline transform failed");

        ClassNode damagedEntityReceiver = read(patchedEntity);
        require(hasOwnershipMarker(damagedEntityReceiver, "entityScriptSnapshotReuse"),
                "runScripts inline ownership marker missing from test fixture");
        MethodNode damagedEntityReceiverRun = method(
                damagedEntityReceiver, "runScripts", "(F)V");
        List<AbstractInsnNode> damagedEntityReceiverCode =
                meaningfulInstructions(damagedEntityReceiverRun);
        require(damagedEntityReceiverCode.get(1) instanceof FieldInsnNode,
                "runScripts installed guard receiver missing from test fixture");
        ((FieldInsnNode) damagedEntityReceiverCode.get(1)).name = "smo$damaged$scripts";
        assertInlinePatchRejected(config, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                "entityScriptSnapshotReuse", write(damagedEntityReceiver),
                "runScripts marker-preserved guard receiver mutation");

        ClassNode damagedEntityBranch = read(patchedEntity);
        require(hasOwnershipMarker(damagedEntityBranch, "entityScriptSnapshotReuse"),
                "runScripts inline ownership marker missing from branch fixture");
        MethodNode damagedEntityBranchRun = method(damagedEntityBranch, "runScripts", "(F)V");
        List<AbstractInsnNode> damagedEntityBranchCode = meaningfulInstructions(damagedEntityBranchRun);
        require(damagedEntityBranchCode.get(3) instanceof JumpInsnNode,
                "runScripts installed guard branch missing from test fixture");
        LabelNode wrongTarget = new LabelNode();
        damagedEntityBranchRun.instructions.insertBefore(damagedEntityBranchCode.get(4), wrongTarget);
        ((JumpInsnNode) damagedEntityBranchCode.get(3)).label = wrongTarget;
        assertInlinePatchRejected(config, PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                "entityScriptSnapshotReuse", write(damagedEntityBranch),
                "runScripts marker-preserved branch target mutation");

        byte[] patchedMemory = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.MEMORY, null, null, memory);
        require(patchedMemory != null, "Memory.advance baseline inline transform failed");

        ClassNode damagedMemoryReceiver = read(patchedMemory);
        require(hasOwnershipMarker(damagedMemoryReceiver, "emptyMemoryAdvanceFastPath"),
                "Memory.advance inline ownership marker missing from receiver fixture");
        MethodNode damagedMemoryReceiverAdvance = method(damagedMemoryReceiver, "advance", "(F)V");
        MethodInsnNode requireEmpty = uniqueCall(damagedMemoryReceiverAdvance,
                Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty", "()Z");
        AbstractInsnNode requireEmptyReceiver = previousMeaningful(requireEmpty);
        require(requireEmptyReceiver instanceof FieldInsnNode,
                "Memory.advance installed require guard receiver missing from test fixture");
        ((FieldInsnNode) requireEmptyReceiver).name = "data";
        assertInlinePatchRejected(config, PrepatcherTransformer.MEMORY,
                "emptyMemoryAdvanceFastPath", write(damagedMemoryReceiver),
                "Memory.advance marker-preserved guard receiver mutation");

        ClassNode damagedMemoryOrder = read(patchedMemory);
        require(hasOwnershipMarker(damagedMemoryOrder, "emptyMemoryAdvanceFastPath"),
                "Memory.advance inline ownership marker missing from order fixture");
        MethodNode damagedMemoryOrderAdvance = method(damagedMemoryOrder, "advance", "(F)V");
        List<MethodInsnNode> engineCalls = calls(damagedMemoryOrderAdvance,
                Opcodes.INVOKESTATIC, "com/fs/starfarer/campaign/CampaignEngine", "getInstance",
                "()Lcom/fs/starfarer/campaign/CampaignEngine;");
        require(engineCalls.size() == 2,
                "Memory.advance installed guard work boundary missing from test fixture");
        damagedMemoryOrderAdvance.instructions.insertBefore(engineCalls.get(1), systemGcCall());
        assertInlinePatchRejected(config, PrepatcherTransformer.MEMORY,
                "emptyMemoryAdvanceFastPath", write(damagedMemoryOrder),
                "Memory.advance marker-preserved work ordering mutation");
    }

    private static boolean hasOwnershipMarker(ClassNode node, String patchId) {
        return node.fields.stream().anyMatch(field -> field.name.equals("smo$patched$" + patchId));
    }

    private static InsnList systemGcCall() {
        InsnList result = new InsnList();
        result.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/System", "gc", "()V", false));
        return result;
    }

    private static MethodInsnNode uniqueCall(MethodNode method, int opcode,
                                             String owner, String name, String desc) {
        List<MethodInsnNode> calls = calls(method, opcode, owner, name, desc);
        require(calls.size() == 1, owner + "." + name + desc
                + " expected once in test input, found " + calls.size());
        return calls.get(0);
    }

    private static List<MethodInsnNode> calls(MethodNode method, int opcode,
                                               String owner, String name, String desc) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != opcode
                    || !call.owner.equals(owner) || !call.name.equals(name)
                    || !call.desc.equals(desc)) continue;
            result.add(call);
        }
        return result;
    }

    private static void assertInlinePatchRejected(
            PrepatcherConfig config, String className, String patchId,
            byte[] input, String scenario) {
        byte[] result = new PrepatcherTransformer(config)
                .transform(null, className, null, null, input);
        require(result == null, scenario + " was patched unexpectedly");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus."
                                + className.replace('/', '.') + "." + patchId)),
                scenario + " was not rejected structurally");
    }

    private static void runExp8OwnershipTests(PrepatcherConfig config, List<Path> jarPaths)
            throws Exception {
        Map<String, List<String>> patches = new LinkedHashMap<>();
        patches.put(PrepatcherTransformer.ECONOMY,
                List.of("economyLocationCache", "economySnapshotReuse"));
        patches.put(PrepatcherTransformer.MARKET, List.of("economySnapshotReuse"));
        patches.put(PrepatcherTransformer.INTEL_MANAGER, List.of("commRelaySystemIndex"));
        patches.put(PrepatcherTransformer.SHIP, List.of("shipAdvanceScratch"));
        patches.put(PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP, List.of("particleCleanup"));

        for (Map.Entry<String, List<String>> entry : patches.entrySet()) {
            String className = entry.getKey();
            byte[] original = classBytes(jarPaths, className);
            byte[] patched = new PrepatcherTransformer(config)
                    .transform(null, className, null, null, original);
            require(patched != null, className + " baseline exp8 transform failed");

            for (String patchId : entry.getValue()) {
                ClassNode unowned = read(patched);
                require(unowned.fields.removeIf(field -> field.name.equals("smo$patched$" + patchId)),
                        className + " exp8 ownership marker missing for " + patchId);
                byte[] result = new PrepatcherTransformer(config)
                        .transform(null, className, null, null, write(unowned));
                require(result == null, className + " foreign exp8 hook-shaped state was modified");
                require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                                "starsector.prepatcher.patchStatus."
                                        + className.replace('/', '.') + "." + patchId)),
                        className + " foreign exp8 state was not rejected for " + patchId);
            }
        }

        Map<String, MethodKey> scoped = new LinkedHashMap<>();
        scoped.put(PrepatcherTransformer.ECONOMY, new MethodKey("advance", "(F)V",
                "economySnapshotReuse"));
        scoped.put(PrepatcherTransformer.MARKET, new MethodKey("advance", "(F)V",
                "economySnapshotReuse"));
        scoped.put(PrepatcherTransformer.INTEL_MANAGER, new MethodKey(
                "findNearestCommRelayToReceive",
                "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)"
                        + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;",
                "commRelaySystemIndex"));
        scoped.put(PrepatcherTransformer.SHIP, new MethodKey("advance", "(F)V",
                "shipAdvanceScratch"));
        scoped.put(PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP,
                new MethodKey("advance", "(F)V", "particleCleanup"));

        for (Map.Entry<String, MethodKey> entry : scoped.entrySet()) {
            String className = entry.getKey();
            MethodKey key = entry.getValue();
            byte[] patched = new PrepatcherTransformer(config).transform(
                    null, className, null, null, classBytes(jarPaths, className));
            require(patched != null, className + " baseline exp8 scope transform failed");
            ClassNode unscoped = read(patched);
            removeScratchScope(method(unscoped, key.name, key.desc));
            byte[] result = new PrepatcherTransformer(config)
                    .transform(null, className, null, null, write(unscoped));
            require(result == null, className + " exp8 hooks without scope were modified");
            require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                            "starsector.prepatcher.patchStatus."
                                    + className.replace('/', '.') + "." + key.patchId)),
                    className + " exp8 hooks without scope were accepted");
        }

        Map<String, HookTamper> latePostconditions = new LinkedHashMap<>();
        latePostconditions.put(PrepatcherTransformer.ECONOMY,
                new HookTamper("borrowEconomyCollectionSnapshot", "economySnapshotReuse"));
        latePostconditions.put(PrepatcherTransformer.SHIP,
                new HookTamper("borrowShipListSnapshot", "shipAdvanceScratch"));
        latePostconditions.put(PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP,
                new HookTamper("removeExpiredParticles", "particleCleanup"));
        for (Map.Entry<String, HookTamper> entry : latePostconditions.entrySet()) {
            String className = entry.getKey();
            HookTamper tamper = entry.getValue();
            byte[] patched = new PrepatcherTransformer(config).transform(
                    null, className, null, null, classBytes(jarPaths, className));
            require(patched != null, className + " baseline exp8 postcondition transform failed");
            ClassNode damaged = read(patched);
            MethodInsnNode hook = uniqueHook(damaged, tamper.hookName);
            hook.name = "smo$tampered$" + hook.name;
            byte[] result = new PrepatcherTransformer(config)
                    .transform(null, className, null, null, write(damaged));
            require(result == null, className + " damaged late postcondition was modified");
            require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                            "starsector.prepatcher.patchStatus."
                                    + className.replace('/', '.') + "." + tamper.patchId)),
                    className + " damaged late postcondition was accepted as idempotent");
        }
    }

    private static MethodInsnNode uniqueHook(ClassNode node, String name) {
        MethodInsnNode result = null;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)
                        || call.getOpcode() != Opcodes.INVOKESTATIC
                        || !call.owner.equals(HOOKS) || !call.name.equals(name)) continue;
                require(result == null, "hook " + name + " is not unique in test input");
                result = call;
            }
        }
        require(result != null, "hook " + name + " is missing in test input");
        return result;
    }

    private static byte[] classBytes(List<Path> jars, String className) throws Exception {
        for (Path jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                var entry = jar.getJarEntry(className + ".class");
                if (entry != null) return jar.getInputStream(entry).readAllBytes();
            }
        }
        throw new AssertionError("Target class not found in supplied JARs: " + className);
    }

    private static void assertLifecycleRejectedButIndependentPatchSurvives(
            PrepatcherConfig config, byte[] input, String scenario) {
        byte[] result = new PrepatcherTransformer(config).transform(
                null, PrepatcherTransformer.CAMPAIGN_ENGINE, null, null, input);
        require(result != null, scenario + " blocked independent CampaignEngine patches");
        require(countHook(result, "beginCampaignEngineChange") == 0
                        && countHook(result, "completeCampaignEngineChange") == 0,
                scenario + " was accepted by lifecycle matcher");
        require(countHook(result, "readdChangeListenersIfNeeded") == 1,
                scenario + " blocked campaign listener throttle");
        verifyBytecode(result);
    }

    private static FieldInsnNode singletonWrite(ClassNode owner, MethodNode method) {
        String desc = "L" + PrepatcherTransformer.CAMPAIGN_ENGINE + ";";
        FieldInsnNode result = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.PUTSTATIC
                    || !field.owner.equals(owner.name) || !field.desc.equals(desc)) continue;
            require(result == null, method.name + " has multiple singleton writes in test input");
            result = field;
        }
        require(result != null, method.name + " singleton write missing in test input");
        return result;
    }

    private static void runOwnershipAndWrapperTests(PrepatcherConfig config, byte[] h) {
        byte[] patched = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, h);
        require(patched != null, "H baseline transform failed in ownership test");

        ClassNode unowned = read(patched);
        require(unowned.fields.removeIf(field -> field.name.equals("smo$patched$retainAll")),
                "retainAll ownership marker was not emitted");
        byte[] unownedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(unowned));
        require(unownedResult == null, "foreign hook-shaped retainAll state was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus.com.fs.starfarer.coreui.A.H.retainAll")),
                "foreign hook-shaped retainAll state was not rejected structurally");

        ClassNode unscoped = read(patched);
        removeScratchScope(method(unscoped, "renderStuff", "(FZ)V"));
        byte[] unscopedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(unscoped));
        require(unscopedResult == null, "owned scratch hooks without a scope were modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus.com.fs.starfarer.coreui.A.H.retainAll")),
                "owned retainAll hook without a scratch scope was accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus.com.fs.starfarer.coreui.A.H.scratchCollections")),
                "owned scratch allocation hooks without a scope were accepted");

        ClassNode tampered = read(patched);
        method(tampered, "getIntelIconEntity",
                "(Lcom/fs/starfarer/api/campaign/comm/IntelInfoPlugin;)"
                        + "Lcom/fs/starfarer/campaign/CustomCampaignEntity;")
                .instructions.insert(new InsnNode(Opcodes.NOP));
        byte[] tamperedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(tampered));
        require(tamperedResult == null, "tampered wrapper was modified instead of rejected");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.prepatcher.patchStatus.com.fs.starfarer.coreui.A.H.intelEntityIndex")),
                "tampered wrapper was not rejected structurally");

        ClassNode annotated = read(h);
        MethodNode annotatedIntel = method(annotated, "getIntelIconEntity",
                "(Lcom/fs/starfarer/api/campaign/comm/IntelInfoPlugin;)"
                        + "Lcom/fs/starfarer/campaign/CustomCampaignEntity;");
        annotatedIntel.visibleAnnotations = new ArrayList<>();
        annotatedIntel.visibleAnnotations.add(new AnnotationNode("Ltest/StructuralMarker;"));
        byte[] annotatedResult = new PrepatcherTransformer(config)
                .transform(null, PrepatcherTransformer.H, null, null, write(annotated));
        require(annotatedResult != null, "declaration metadata blocked compatible wrapper patch");
        ClassNode wrapped = read(annotatedResult);
        MethodNode publicWrapper = method(wrapped, "getIntelIconEntity", annotatedIntel.desc);
        MethodNode privateOriginal = method(wrapped, "smo$originalGetIntelIconEntity", annotatedIntel.desc);
        require(publicWrapper.visibleAnnotations != null
                        && publicWrapper.visibleAnnotations.stream()
                        .anyMatch(annotation -> annotation.desc.equals("Ltest/StructuralMarker;")),
                "wrapper lost original declaration annotation");
        require(privateOriginal.visibleAnnotations == null || privateOriginal.visibleAnnotations.isEmpty(),
                "declaration annotation was duplicated on the private fallback");
        verifyBytecode(annotatedResult);
    }

    private static void removeScratchScope(MethodNode method) {
        TryCatchBlockNode scratchHandler = null;
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            AbstractInsnNode first = nextMeaningful(block.handler);
            if (block.type == null && first instanceof MethodInsnNode call
                    && call.owner.equals(HOOKS) && call.name.equals("endScratchScope")
                    && call.desc.equals("()V")) {
                require(scratchHandler == null, "multiple scratch catch-all handlers in test input");
                scratchHandler = block;
            }
        }
        require(scratchHandler != null, "scratch catch-all handler is missing in test input");
        method.tryCatchBlocks.remove(scratchHandler);

        AbstractInsnNode cursor = scratchHandler.handler;
        while (cursor != null) {
            AbstractInsnNode next = cursor.getNext();
            boolean done = cursor.getOpcode() == Opcodes.ATHROW;
            method.instructions.remove(cursor);
            if (done) break;
            cursor = next;
        }

        int removedCalls = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && (call.name.equals("beginScratchScope") || call.name.equals("endScratchScope"))
                    && call.desc.equals("()V")) {
                method.instructions.remove(call);
                removedCalls++;
            }
        }
        require(removedCalls >= 2, "scratch scope calls are missing in test input");
    }

    private static void assertHNonRetainHooks(byte[] bytes, String scenario) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        expected.put("borrowEntityList", 1);
        expected.put("borrowClassSet", 1);
        expected.put("nearbyLabelIcons", 1);
        expected.put("getIntelIconEntityIndexed", 1);
        expected.put("updateSystemNebulasCached", 1);
        expected.put("forceClearSampleCacheThrottled", 1);
        expected.put("gridSpacing", 5);
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            require(countHook(bytes, entry.getKey()) == entry.getValue(),
                    scenario + " incorrectly cancelled independent H hook " + entry.getKey());
        }
    }

    private static byte[] mutateH(byte[] source, Mutation mutation) {
        ClassNode node = read(source);
        MethodNode render = method(node, "renderStuff", "(FZ)V");
        if (mutation == Mutation.UNRELATED_NOP) {
            render.instructions.insert(new InsnNode(Opcodes.NOP));
            return write(node);
        }
        if (mutation == Mutation.UNRELATED_RETAIN) {
            InsnList unrelated = new InsnList();
            unrelated.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashSet"));
            unrelated.add(new InsnNode(Opcodes.DUP));
            unrelated.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/util/HashSet", "<init>", "()V", false));
            unrelated.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
            unrelated.add(new InsnNode(Opcodes.DUP));
            unrelated.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/util/ArrayList", "<init>", "()V", false));
            unrelated.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z", true));
            unrelated.add(new InsnNode(Opcodes.POP));
            render.instructions.insert(unrelated);
            return write(node);
        }

        MethodInsnNode retain = null;
        for (AbstractInsnNode insn : render.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
                    && call.owner.equals("java/util/Set") && call.name.equals("retainAll")
                    && call.desc.equals("(Ljava/util/Collection;)Z")) {
                retain = call;
                break;
            }
        }
        require(retain != null, "vanilla retainAll site not found in test input");
        if (mutation == Mutation.MISSING_RETAIN) {
            retain.name = "removeAll";
            return write(node);
        }

        AbstractInsnNode keepLoad = previousMeaningful(retain);
        MethodInsnNode keySet = (MethodInsnNode) previousMeaningful(keepLoad);
        FieldInsnNode icons = (FieldInsnNode) previousMeaningful(keySet);
        int keepLocal = ((VarInsnNode) keepLoad).var;
        InsnList duplicate = new InsnList();
        duplicate.add(new VarInsnNode(Opcodes.ALOAD, 0));
        duplicate.add(new FieldInsnNode(Opcodes.GETFIELD, icons.owner, icons.name, icons.desc));
        duplicate.add(new MethodInsnNode(keySet.getOpcode(), keySet.owner, keySet.name, keySet.desc, keySet.itf));
        duplicate.add(new VarInsnNode(Opcodes.ALOAD, keepLocal));
        duplicate.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "java/util/Set", "retainAll", "(Ljava/util/Collection;)Z", true));
        duplicate.add(new InsnNode(Opcodes.POP));
        render.instructions.insertBefore(retain, duplicate);
        return write(node);
    }

    private static void assertExpectedHooks(String className, byte[] bytes) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        switch (className) {
            case PrepatcherTransformer.H -> {
                expected.put("retainAllFast", 1);
                expected.put("borrowEntityList", 1);
                expected.put("borrowClassSet", 1);
                expected.put("nearbyLabelIcons", 1);
                expected.put("getIntelIconEntityIndexed", 1);
                expected.put("updateSystemNebulasCached", 1);
                expected.put("forceClearSampleCacheThrottled", 1);
                expected.put("gridSpacing", 5);
            }
            case PrepatcherTransformer.A -> {
                expected.put("borrowHitList", 1);
                expected.put("borrowHitPoint", 1);
                expected.put("getMapLocationCached", 1);
                expected.put("hitTestCached", 1);
            }
            case PrepatcherTransformer.Z -> {
                expected.put("getArrowDataCached", 1);
                expected.put("borrowArrowVector", 2);
            }
            case PrepatcherTransformer.EVENTS -> {
                expected.put("getMapLocationCached", 7);
                expected.put("fastContains", 1);
                expected.put("existingIntelIconCandidates", 1);
            }
            case PrepatcherTransformer.CAMPAIGN_ENGINE -> {
                expected.put("beginCampaignEngineChange", 2);
                expected.put("completeCampaignEngineChange", 2);
                expected.put("readdChangeListenersIfNeeded", 1);
            }
            case PrepatcherTransformer.COURSE_WIDGET -> {
                expected.put("routeJumpPointsForSystem", 3);
                expected.put("routeSystemsForAnchor", 1);
            }
            case PrepatcherTransformer.BASE_LOCATION -> {
                expected.put("borrowLocationAdvanceSnapshot", 3);
                expected.put("borrowPausedLocationSnapshot", 2);
            }
            case PrepatcherTransformer.BASE_CAMPAIGN_ENTITY,
                    PrepatcherTransformer.MEMORY -> {
                // These patches are now fully inline. The previous helper-based
                // versions were measurable hot paths in real campaign telemetry.
            }
            case PrepatcherTransformer.ECONOMY -> {
                expected.put("updateEconomyLocationMapIfNeeded", 1);
                expected.put("borrowEconomyMarketsSnapshot", 2);
                expected.put("borrowEconomyCollectionSnapshot", 1);
            }
            case PrepatcherTransformer.MARKET -> expected.put("borrowEconomyCollectionSnapshot", 2);
            case PrepatcherTransformer.INTEL_MANAGER -> expected.put("commRelayCandidateSystems", 1);
            case PrepatcherTransformer.SHIP -> {
                expected.put("borrowShipList", 3);
                expected.put("borrowShipListSnapshot", 1);
                expected.put("borrowShipSet", 2);
            }
            case PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP -> {
                expected.put("borrowParticleRemovalList", 1);
                expected.put("removeExpiredParticles", 1);
            }
            case PrepatcherTransformer.LOADING_UTILS -> {
                expected.put("readUtf8Normalized", 1);
                expected.put("startupLogSuppressed", 5);
            }
            case PrepatcherTransformer.SCRIPT_STORE_RUNNER -> expected.put("startupLogSuppressed", 1);
            case PrepatcherTransformer.RULES -> {
                expected.put("startupLogSuppressed", 1);
                expected.put("removeCarriageReturns", 3);
                expected.put("replaceNewlinesWithSpace", 1);
                expected.put("splitNewlines", 3);
                expected.put("splitOrBlocks", 1);
                expected.put("splitColon", 1);
            }
            case PrepatcherTransformer.SPEC_STORE -> expected.put("suppressStartupInfo", 53);
            case PrepatcherTransformer.TEXTURE_LOADER, PrepatcherTransformer.SOUND ->
                    expected.put("startupLogSuppressed", 1);
            case PrepatcherTransformer.PROGRESS_INPUT, PrepatcherTransformer.PROGRESS_OUTPUT ->
                    expected.put("saveLoadProgressMinIntervalSeconds", 1);
            case PrepatcherTransformer.CAMPAIGN_GAME_MANAGER -> {
                // This patch removes a redundant constructor chain and therefore has no runtime hook.
            }
            case HyperspacePatches.BASE_TILED ->
                    assertHookCount(bytes, HYPERSPACE_HOOKS, "seededRandom", 3);
            case HyperspacePatches.HYPER_TERRAIN -> {
                int layerFilters = countHook(bytes, HYPERSPACE_HOOKS, "filterLayers")
                        + countHook(bytes, HYPERSPACE_HOOKS, "filterLayerSet");
                require(layerFilters == 1,
                        className + " layer filter hook expected=1 actual=" + layerFilters);
                assertHookCount(bytes, HYPERSPACE_HOOKS, "seededRandom", 1);
                assertHookCount(bytes, HYPERSPACE_HOOKS, "automatonCellsInternal", 2);
            }
            case HyperspacePatches.AUTOMATON -> {
                assertHookCount(bytes, HYPERSPACE_HOOKS, "retainAutomatonSpare", 1);
                assertHookCount(bytes, HYPERSPACE_HOOKS, "acquireAutomatonBuffer", 1);
            }
            case HyperspacePatches.STARFIELD -> {
                assertHookCount(bytes, HYPERSPACE_HOOKS, "borrowArrayList", 1);
                assertHookCount(bytes, HYPERSPACE_HOOKS, "removeAllAndRelease", 1);
            }
            default -> throw new AssertionError("Unknown target " + className);
        }
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            require(countHook(bytes, entry.getKey()) == entry.getValue(),
                    className + " hook " + entry.getKey() + " expected=" + entry.getValue()
                            + " actual=" + countHook(bytes, entry.getKey()));
        }
        if (className.equals(PrepatcherTransformer.CAMPAIGN_GAME_MANAGER)) {
            assertRedundantSaveBufferRemoved(bytes);
        }
        if (className.equals(PrepatcherTransformer.H)) {
            assertScratchScope(bytes, "renderStuff", "(FZ)V");
            assertScratchScope(bytes, "getTextAlignmentFor",
                    "(Lcom/fs/starfarer/coreui/A/ooOO;)Lcom/fs/starfarer/api/ui/Alignment;");
        } else if (className.equals(PrepatcherTransformer.A)) {
            assertScratchScope(bytes, "smo$originalHitTest",
                    "(FFF)Lcom/fs/starfarer/api/campaign/SectorEntityToken;");
        } else if (className.equals(PrepatcherTransformer.Z)) {
            assertScratchScope(bytes, "o00000", "(FF)V");
        } else if (className.equals(PrepatcherTransformer.EVENTS)) {
            assertScratchScope(bytes, "addMissingIconsAndRows", "()V");
        } else if (className.equals(PrepatcherTransformer.BASE_LOCATION)) {
            assertScratchScope(bytes, "advance", "(FLcom/fs/starfarer/util/A/new;)V");
            assertScratchScope(bytes, "advanceEvenIfPaused",
                    "(FLcom/fs/starfarer/util/A/new;)V");
        } else if (className.equals(PrepatcherTransformer.BASE_CAMPAIGN_ENTITY)) {
            assertEntityScriptEmptyGuard(bytes);
        } else if (className.equals(PrepatcherTransformer.MEMORY)) {
            assertMemoryEmptyGuard(bytes);
        } else if (className.equals(PrepatcherTransformer.ECONOMY)) {
            assertScratchScope(bytes, "advance", "(F)V");
            assertScratchScope(bytes, "advanceMarketConditionsWhenPaused", "(F)V");
        } else if (className.equals(PrepatcherTransformer.MARKET)) {
            assertScratchScope(bytes, "advance", "(F)V");
        } else if (className.equals(PrepatcherTransformer.INTEL_MANAGER)) {
            assertScratchScope(bytes, "findNearestCommRelayToReceive",
                    "(Lcom/fs/starfarer/api/campaign/SectorEntityToken;)"
                            + "Lcom/fs/starfarer/api/campaign/SectorEntityToken;");
        } else if (className.equals(PrepatcherTransformer.SHIP)) {
            assertScratchScope(bytes, "advance", "(F)V");
        } else if (className.equals(PrepatcherTransformer.DYNAMIC_PARTICLE_GROUP)) {
            assertScratchScope(bytes, "advance", "(F)V");
        }
    }

    private static void assertEntityScriptEmptyGuard(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode method = method(node, "runScripts", "(F)V");
        require(countCalls(method, "beginScratchScope") == 0
                        && countCalls(method, "endScratchScope") == 0,
                "BaseCampaignEntity.runScripts still opens a generic scratch scope");
        require(countCalls(method, "borrowEntityScriptSnapshot") == 0,
                "BaseCampaignEntity.runScripts still calls the retired snapshot helper");
        require(countCalls(method, Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>",
                "(Ljava/util/Collection;)V") == 1,
                "BaseCampaignEntity.runScripts non-empty path lost its fresh vanilla snapshot");
        require(countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z") == 1,
                "BaseCampaignEntity.runScripts has no unique inline empty guard");
        List<AbstractInsnNode> code = meaningfulInstructions(method);
        require(code.size() >= 6, "BaseCampaignEntity.runScripts guard is truncated");
        require(code.get(0) instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD
                        && load.var == 0,
                "BaseCampaignEntity.runScripts guard does not start from this");
        require(code.get(1) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
                        && field.owner.equals(node.name) && field.name.equals("scripts")
                        && field.desc.equals("Ljava/util/List;"),
                "BaseCampaignEntity.runScripts guard does not read scripts");
        require(code.get(2) instanceof MethodInsnNode empty
                        && empty.getOpcode() == Opcodes.INVOKEINTERFACE
                        && empty.owner.equals("java/util/List") && empty.name.equals("isEmpty")
                        && empty.desc.equals("()Z"),
                "BaseCampaignEntity.runScripts empty check changed");
        require(code.get(3) instanceof JumpInsnNode
                        && code.get(3).getOpcode() == Opcodes.IFEQ,
                "BaseCampaignEntity.runScripts empty guard branch changed");
        JumpInsnNode jump = (JumpInsnNode) code.get(3);
        require(code.get(4).getOpcode() == Opcodes.RETURN,
                "BaseCampaignEntity.runScripts empty guard does not return immediately");
        require(code.get(5) instanceof TypeInsnNode snapshot
                        && snapshot.getOpcode() == Opcodes.NEW
                        && snapshot.desc.equals("java/util/ArrayList")
                        && nextMeaningful(jump.label) == snapshot,
                "BaseCampaignEntity.runScripts non-empty branch does not rejoin at the original snapshot");
    }

    private static void assertMemoryEmptyGuard(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode method = method(node, "advance", "(F)V");
        require(countCalls(method, "memoryExpireIterator") == 0
                        && countCalls(method, "memoryRequireIterator") == 0,
                "Memory.advance still calls the retired iterator helpers");
        require(countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "iterator",
                "()Ljava/util/Iterator;") == 1,
                "Memory.advance expire iterator semantics changed");
        require(countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator",
                "()Ljava/util/Iterator;") == 1,
                "Memory.advance require iterator semantics changed");
        require(countCalls(method, Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty", "()Z") == 1,
                "Memory.advance expire empty guard is missing or duplicated");
        require(countCalls(method, Opcodes.INVOKEVIRTUAL, "java/util/LinkedHashMap", "isEmpty",
                "()Z") == 1,
                "Memory.advance require empty guard is missing or duplicated");

        List<MethodInsnNode> engines = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESTATIC
                    && call.owner.equals("com/fs/starfarer/campaign/CampaignEngine")
                    && call.name.equals("getInstance")
                    && call.desc.equals("()Lcom/fs/starfarer/campaign/CampaignEngine;")) {
                engines.add(call);
            }
        }
        require(engines.size() == 2,
                "Memory.advance engine/pause structure changed");
        MethodInsnNode workStart = engines.get(1);
        AbstractInsnNode emptyReturn = previousMeaningful(workStart);
        AbstractInsnNode secondJumpInsn = previousMeaningful(emptyReturn);
        AbstractInsnNode requireEmpty = previousMeaningful(secondJumpInsn);
        AbstractInsnNode requireField = previousMeaningful(requireEmpty);
        AbstractInsnNode requireLoad = previousMeaningful(requireField);
        AbstractInsnNode firstJumpInsn = previousMeaningful(requireLoad);
        require(emptyReturn != null && emptyReturn.getOpcode() == Opcodes.RETURN
                        && secondJumpInsn instanceof JumpInsnNode secondJump
                        && secondJump.getOpcode() == Opcodes.IFEQ
                        && requireEmpty instanceof MethodInsnNode requireCall
                        && requireCall.owner.equals("java/util/LinkedHashMap")
                        && requireCall.name.equals("isEmpty")
                        && requireField instanceof FieldInsnNode field
                        && field.name.equals("require")
                        && requireLoad instanceof VarInsnNode load
                        && load.getOpcode() == Opcodes.ALOAD && load.var == 0
                        && firstJumpInsn instanceof JumpInsnNode firstJump
                        && firstJump.getOpcode() == Opcodes.IFEQ
                        && firstJump.label == secondJump.label
                        && nextMeaningful(firstJump.label) == workStart,
                "Memory.advance guard is not the exact post-pause prefix");
    }

    private static void assertRedundantSaveBufferRemoved(byte[] bytes) {
        ClassNode node = read(bytes);
        MethodNode save = method(node, "o00000",
                "(Lcom/fs/starfarer/campaign/CampaignEngine$o;JZ)Ljava/lang/String;");
        int redundant = 0;
        for (AbstractInsnNode insn : save.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.owner.equals("java/io/BufferedOutputStream")
                    || !call.name.equals("<init>")
                    || !call.desc.equals("(Ljava/io/OutputStream;I)V")) continue;
            AbstractInsnNode size = previousMeaningful(call);
            if (size instanceof LdcInsnNode ldc && Integer.valueOf(1_048_576).equals(ldc.cst)) redundant++;
        }
        require(redundant == 0, "redundant outer 1 MiB save buffer is still present");
    }

    private static void assertScratchScope(byte[] bytes, String name, String desc) {
        ClassNode node = read(bytes);
        MethodNode scoped = method(node, name, desc);
        require(countCalls(scoped, "beginScratchScope") == 1,
                node.name + "." + name + " has no unique scratch-scope entry");
        int returns = 0;
        for (AbstractInsnNode insn : scoped.instructions.toArray()) {
            if (insn.getOpcode() < Opcodes.IRETURN || insn.getOpcode() > Opcodes.RETURN) continue;
            returns++;
            AbstractInsnNode previous = previousMeaningful(insn);
            require(previous instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                            && call.name.equals("endScratchScope") && call.desc.equals("()V"),
                    node.name + "." + name + " has an unscoped return");
        }
        require(countCalls(scoped, "endScratchScope") == returns + 1,
                node.name + "." + name + " scratch-scope exit count changed");

        boolean fullHandler = false;
        for (TryCatchBlockNode block : scoped.tryCatchBlocks) {
            if (block.type != null) continue;
            FrameNode frame = null;
            AbstractInsnNode cursor = block.handler.getNext();
            while (cursor != null && !(cursor instanceof MethodInsnNode)) {
                if (cursor instanceof FrameNode candidate) frame = candidate;
                cursor = cursor.getNext();
            }
            if (cursor instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && call.name.equals("endScratchScope") && call.desc.equals("()V")) {
                require(frame != null && frame.type == Opcodes.F_FULL,
                        node.name + "." + name + " scratch handler is not F_FULL");
                require(frame.local != null && frame.local.equals(initialFrameLocals(node.name, scoped)),
                        node.name + "." + name + " scratch handler locals are not the entry locals");
                fullHandler = true;
            }
        }
        require(fullHandler, node.name + "." + name + " scratch catch-all is missing");
    }

    private static List<Object> initialFrameLocals(String owner, MethodNode method) {
        List<Object> locals = new ArrayList<>();
        if ((method.access & Opcodes.ACC_STATIC) == 0) locals.add(owner);
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            switch (argument.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> locals.add(Opcodes.INTEGER);
                case Type.FLOAT -> locals.add(Opcodes.FLOAT);
                case Type.LONG -> locals.add(Opcodes.LONG);
                case Type.DOUBLE -> locals.add(Opcodes.DOUBLE);
                case Type.ARRAY -> locals.add(argument.getDescriptor());
                case Type.OBJECT -> locals.add(argument.getInternalName());
                default -> throw new AssertionError("unsupported frame argument " + argument);
            }
        }
        return locals;
    }

    private static List<AbstractInsnNode> meaningfulInstructions(MethodNode method) {
        List<AbstractInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn.getOpcode() >= 0) result.add(insn);
        }
        return result;
    }

    private static int countCalls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && call.name.equals(name) && call.desc.equals("()V")) count++;
        }
        return count;
    }

    private static int countCalls(MethodNode method, int opcode, String owner,
                                  String name, String desc) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.getOpcode() == opcode
                    && call.owner.equals(owner) && call.name.equals(name)
                    && call.desc.equals(desc)) count++;
        }
        return count;
    }

    private static int countHook(byte[] bytes, String name) {
        return countHook(bytes, HOOKS, name);
    }

    private static int countHook(byte[] bytes, String owner, String name) {
        int count = 0;
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(owner) && call.name.equals(name)) count++;
            }
        }
        return count;
    }

    private static void assertHookTargetsExist(byte[] transformed) {
        Map<String, Integer> hooks = hookMethods(HOOKS);
        Map<String, Integer> hyperspaceHooks = hookMethods(HYPERSPACE_HOOKS);
        ClassNode node = read(transformed);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call)
                        || (!call.owner.equals(HOOKS) && !call.owner.equals(HYPERSPACE_HOOKS))) continue;
                String key = call.name + call.desc;
                Integer access = (call.owner.equals(HOOKS) ? hooks : hyperspaceHooks).get(key);
                require(access != null, "missing runtime hook target " + key);
                require(call.getOpcode() == Opcodes.INVOKESTATIC
                                && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                                == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                        "runtime hook is not public static: " + key);
            }
        }
    }

    private static void assertHookCount(byte[] bytes, String owner, String name, int expected) {
        int actual = countHook(bytes, owner, name);
        require(actual == expected, name + " expected=" + expected + " actual=" + actual);
    }

    private static Map<String, Integer> hookMethods(String owner) {
        try (var stream = StructuralCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(owner + ".class")) {
            require(stream != null, owner + ".class is missing from the test classpath");
            ClassNode hooks = read(stream.readAllBytes());
            Map<String, Integer> result = new LinkedHashMap<>();
            for (MethodNode method : hooks.methods) result.put(method.name + method.desc, method.access);
            return result;
        } catch (Exception ex) {
            throw new AssertionError("unable to inspect hook linkage for " + owner, ex);
        }
    }

    private static int verifyBytecode(byte[] bytes) {
        ClassNode node = read(bytes);
        int methods = 0;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException ex) {
                throw new AssertionError("BasicVerifier rejected " + node.name + "."
                        + method.name + method.desc, ex);
            }
            methods++;
        }
        return methods;
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        List<MethodNode> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) found.add(method);
        }
        require(found.size() == 1, "method " + name + desc + " expected once, found " + found.size());
        return found.get(0);
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static AbstractInsnNode nextMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getNext();
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int integerProperty(String key) {
        return Integer.parseInt(System.getProperty(key, "0"));
    }

    private enum Mutation { UNRELATED_NOP, UNRELATED_RETAIN, MISSING_RETAIN, DUPLICATE_RETAIN }
    private record MethodKey(String name, String desc, String patchId) {}
    private record HookTamper(String hookName, String patchId) {}

    private record Result(int classes, int methods) {}
}
