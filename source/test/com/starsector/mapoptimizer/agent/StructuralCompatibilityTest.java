package com.starsector.mapoptimizer.agent;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** In-memory regression harness; it never writes modified game classes. */
public final class StructuralCompatibilityTest {
    private static final String HOOKS = "com/starsector/mapoptimizer/runtime/MapOptimizerHooks";

    private StructuralCompatibilityTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: StructuralCompatibilityTest <optimizer.properties> <core.jar>...");
        }
        Path configPath = Path.of(args[0]).toAbsolutePath().normalize();
        Path testLog = configPath.getParent().resolve(".build").resolve("structural-test.log");
        Files.createDirectories(testLog.getParent());
        OptimizerLog.initialize(testLog);
        OptimizerConfig config = OptimizerConfig.load(configPath);

        int jars = 0;
        int classes = 0;
        int methods = 0;
        for (int i = 1; i < args.length; i++) {
            Path jar = Path.of(args[i]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(jar)) throw new IllegalArgumentException("JAR not found: " + jar);
            Result result = verifyJar(config, jar);
            jars++;
            classes += result.classes;
            methods += result.methods;
            System.out.println("OK structural jar=" + jar + " classes=" + result.classes
                    + " verifiedMethods=" + result.methods);
        }

        runNegativeRetainAllTests(config, Path.of(args[1]).toAbsolutePath().normalize());
        System.out.println("OK negative-tests retainAll missing ambiguous unrelated-call unrelated-change"
                + " marker-ownership scratch-scope-tamper wrapper-tamper wrapper-metadata idempotency");
        System.out.println("SUMMARY jars=" + jars + " transformedClasses=" + classes
                + " verifiedMethods=" + methods);
    }

    private static Result verifyJar(OptimizerConfig config, Path jarPath) throws Exception {
        int classes = 0;
        int methods = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String className : MapOptimizerTransformer.TARGET_CLASSES) {
                byte[] original = jar.getInputStream(jar.getJarEntry(className + ".class")).readAllBytes();
                MapOptimizerTransformer transformer = new MapOptimizerTransformer(config);
                byte[] transformed = transformer.transform(null, className, null, null, original);
                require(transformed != null, "no patch applied to " + className + " from " + jarPath);
                assertExpectedHooks(className, transformed);
                assertHookTargetsExist(transformed);
                methods += verifyBytecode(transformed);
                int skippedBefore = integerProperty("starsector.mapoptimizer.skippedPatches");
                byte[] second = transformer.transform(null, className, null, null, transformed);
                require(second == null, "transform is not idempotent for " + className);
                require(integerProperty("starsector.mapoptimizer.skippedPatches") == skippedBefore,
                        "already-patched class was reported as structurally incompatible: " + className);
                classes++;
            }
        }
        return new Result(classes, methods);
    }

    private static void runNegativeRetainAllTests(OptimizerConfig config, Path jarPath) throws Exception {
        byte[] h;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            h = jar.getInputStream(jar.getJarEntry(MapOptimizerTransformer.H + ".class")).readAllBytes();
        }

        byte[] unrelated = mutateH(h, Mutation.UNRELATED_NOP);
        byte[] unrelatedResult = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, unrelated);
        require(unrelatedResult != null, "unrelated H change blocked all patches");
        require(countHook(unrelatedResult, "retainAllFast") == 1,
                "unrelated H change blocked retainAllFast");
        verifyBytecode(unrelatedResult);

        byte[] unrelatedCall = mutateH(h, Mutation.UNRELATED_RETAIN);
        byte[] unrelatedCallResult = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, unrelatedCall);
        require(unrelatedCallResult != null, "unrelated retainAll call blocked all H patches");
        require(countHook(unrelatedCallResult, "retainAllFast") == 1,
                "unrelated retainAll call was confused with map reconciliation");
        verifyBytecode(unrelatedCallResult);

        byte[] missing = mutateH(h, Mutation.MISSING_RETAIN);
        byte[] missingResult = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, missing);
        require(missingResult != null, "other H patches were lost when retainAll was missing");
        require(countHook(missingResult, "retainAllFast") == 0,
                "missing retainAll site was patched unexpectedly");
        assertHNonRetainHooks(missingResult, "missing retainAll site");
        verifyBytecode(missingResult);

        byte[] ambiguous = mutateH(h, Mutation.DUPLICATE_RETAIN);
        byte[] ambiguousResult = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, ambiguous);
        require(ambiguousResult != null, "other H patches were lost for ambiguous retainAll");
        require(countHook(ambiguousResult, "retainAllFast") == 0,
                "ambiguous retainAll sites were patched unexpectedly");
        assertHNonRetainHooks(ambiguousResult, "ambiguous retainAll sites");
        verifyBytecode(ambiguousResult);

        runOwnershipAndWrapperTests(config, h);
    }

    private static void runOwnershipAndWrapperTests(OptimizerConfig config, byte[] h) {
        byte[] patched = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, h);
        require(patched != null, "H baseline transform failed in ownership test");

        ClassNode unowned = read(patched);
        require(unowned.fields.removeIf(field -> field.name.equals("smo$patched$retainAll")),
                "retainAll ownership marker was not emitted");
        byte[] unownedResult = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, write(unowned));
        require(unownedResult == null, "foreign hook-shaped retainAll state was modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.mapoptimizer.patchStatus.com.fs.starfarer.coreui.A.H.retainAll")),
                "foreign hook-shaped retainAll state was not rejected structurally");

        ClassNode unscoped = read(patched);
        removeScratchScope(method(unscoped, "renderStuff", "(FZ)V"));
        byte[] unscopedResult = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, write(unscoped));
        require(unscopedResult == null, "owned scratch hooks without a scope were modified");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.mapoptimizer.patchStatus.com.fs.starfarer.coreui.A.H.retainAll")),
                "owned retainAll hook without a scratch scope was accepted");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.mapoptimizer.patchStatus.com.fs.starfarer.coreui.A.H.scratchCollections")),
                "owned scratch allocation hooks without a scope were accepted");

        ClassNode tampered = read(patched);
        method(tampered, "getIntelIconEntity",
                "(Lcom/fs/starfarer/api/campaign/comm/IntelInfoPlugin;)"
                        + "Lcom/fs/starfarer/campaign/CustomCampaignEntity;")
                .instructions.insert(new InsnNode(Opcodes.NOP));
        byte[] tamperedResult = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, write(tampered));
        require(tamperedResult == null, "tampered wrapper was modified instead of rejected");
        require("SKIPPED_STRUCTURAL".equals(System.getProperty(
                        "starsector.mapoptimizer.patchStatus.com.fs.starfarer.coreui.A.H.intelEntityIndex")),
                "tampered wrapper was not rejected structurally");

        ClassNode annotated = read(h);
        MethodNode annotatedIntel = method(annotated, "getIntelIconEntity",
                "(Lcom/fs/starfarer/api/campaign/comm/IntelInfoPlugin;)"
                        + "Lcom/fs/starfarer/campaign/CustomCampaignEntity;");
        annotatedIntel.visibleAnnotations = new ArrayList<>();
        annotatedIntel.visibleAnnotations.add(new AnnotationNode("Ltest/StructuralMarker;"));
        byte[] annotatedResult = new MapOptimizerTransformer(config)
                .transform(null, MapOptimizerTransformer.H, null, null, write(annotated));
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
            case MapOptimizerTransformer.H -> {
                expected.put("retainAllFast", 1);
                expected.put("borrowEntityList", 1);
                expected.put("borrowClassSet", 1);
                expected.put("nearbyLabelIcons", 1);
                expected.put("getIntelIconEntityIndexed", 1);
                expected.put("updateSystemNebulasCached", 1);
                expected.put("forceClearSampleCacheThrottled", 1);
                expected.put("gridSpacing", 5);
            }
            case MapOptimizerTransformer.A -> {
                expected.put("borrowHitList", 1);
                expected.put("borrowHitPoint", 1);
                expected.put("getMapLocationCached", 1);
                expected.put("hitTestCached", 1);
            }
            case MapOptimizerTransformer.Z -> {
                expected.put("getArrowDataCached", 1);
                expected.put("borrowArrowVector", 2);
            }
            case MapOptimizerTransformer.EVENTS -> {
                expected.put("getMapLocationCached", 7);
                expected.put("fastContains", 1);
                expected.put("existingIntelIconCandidates", 1);
            }
            case MapOptimizerTransformer.CAMPAIGN_ENGINE -> expected.put("readdChangeListenersIfNeeded", 1);
            case MapOptimizerTransformer.COURSE_WIDGET -> {
                expected.put("routeJumpPointsForSystem", 3);
                expected.put("routeSystemsForAnchor", 1);
            }
            default -> throw new AssertionError("Unknown target " + className);
        }
        for (Map.Entry<String, Integer> entry : expected.entrySet()) {
            require(countHook(bytes, entry.getKey()) == entry.getValue(),
                    className + " hook " + entry.getKey() + " expected=" + entry.getValue()
                            + " actual=" + countHook(bytes, entry.getKey()));
        }
        if (className.equals(MapOptimizerTransformer.H)) {
            assertScratchScope(bytes, "renderStuff", "(FZ)V");
            assertScratchScope(bytes, "getTextAlignmentFor",
                    "(Lcom/fs/starfarer/coreui/A/ooOO;)Lcom/fs/starfarer/api/ui/Alignment;");
        } else if (className.equals(MapOptimizerTransformer.A)) {
            assertScratchScope(bytes, "smo$originalHitTest",
                    "(FFF)Lcom/fs/starfarer/api/campaign/SectorEntityToken;");
        } else if (className.equals(MapOptimizerTransformer.Z)) {
            assertScratchScope(bytes, "o00000", "(FF)V");
        } else if (className.equals(MapOptimizerTransformer.EVENTS)) {
            assertScratchScope(bytes, "addMissingIconsAndRows", "()V");
        }
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

    private static int countCalls(MethodNode method, String name) {
        int count = 0;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call && call.owner.equals(HOOKS)
                    && call.name.equals(name) && call.desc.equals("()V")) count++;
        }
        return count;
    }

    private static int countHook(byte[] bytes, String name) {
        int count = 0;
        ClassNode node = read(bytes);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(HOOKS) && call.name.equals(name)) count++;
            }
        }
        return count;
    }

    private static void assertHookTargetsExist(byte[] transformed) {
        Map<String, Integer> hooks = hookMethods();
        ClassNode node = read(transformed);
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(HOOKS)) continue;
                String key = call.name + call.desc;
                Integer access = hooks.get(key);
                require(access != null, "missing runtime hook target " + key);
                require(call.getOpcode() == Opcodes.INVOKESTATIC
                                && (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                                == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                        "runtime hook is not public static: " + key);
            }
        }
    }

    private static Map<String, Integer> hookMethods() {
        try (var stream = StructuralCompatibilityTest.class.getClassLoader()
                .getResourceAsStream(HOOKS + ".class")) {
            require(stream != null, "MapOptimizerHooks.class is missing from the test classpath");
            ClassNode hooks = read(stream.readAllBytes());
            Map<String, Integer> result = new LinkedHashMap<>();
            for (MethodNode method : hooks.methods) result.put(method.name + method.desc, method.access);
            return result;
        } catch (Exception ex) {
            throw new AssertionError("unable to inspect MapOptimizerHooks linkage", ex);
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
    private record Result(int classes, int methods) {}
}
