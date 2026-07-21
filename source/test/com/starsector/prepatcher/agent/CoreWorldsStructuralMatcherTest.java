package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarFile;

/** Focused structural regression proving the CoreScript patch is not hash/slot gated. */
public final class CoreWorldsStructuralMatcherTest {
    private static final String RUNTIME =
            "com/fs/starfarer/api/StarsectorPrepatcherCoreWorldsRuntime";

    private CoreWorldsStructuralMatcherTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: CoreWorldsStructuralMatcherTest <config> <starfarer.api.jar>");
        }
        PrepatcherConfig config = PrepatcherConfig.load(Path.of(args[0]));
        byte[] original = readClass(Path.of(args[1]), PrepatcherTransformer.CORE_SCRIPT);

        byte[] patched = transform(config, original);
        require(patched != null, "vanilla CoreScript was not patched");
        assertPatched(patched, 2);
        require(new PrepatcherTransformer(config).transform(null,
                        PrepatcherTransformer.CORE_SCRIPT, null, null, patched) == null,
                "second pass was not idempotent");

        byte[] unrelated = mutate(original, false, true);
        byte[] unrelatedPatched = transform(config, unrelated);
        require(unrelatedPatched != null,
                "unrelated NOP changed compatibility (hash-style gating detected)");
        assertPatched(unrelatedPatched, 2);

        byte[] remapped = mutate(original, true, false);
        byte[] remappedPatched = transform(config, remapped);
        require(remappedPatched != null, "sector-local remap was rejected");
        assertPatched(remappedPatched, 4);

        byte[] duplicate = duplicateCoreWorldsCall(original);
        require(transform(config, duplicate) == null,
                "ambiguous duplicate core-worlds call was patched");

        byte[] moved = moveCoreWorldsAwayFromTerminalBoundary(original);
        require(transform(config, moved) == null,
                "non-terminal core-worlds call was patched");

        byte[] foreignHook = installForeignHookWithoutMarker(original);
        require(transform(config, foreignHook) == null,
                "foreign hook-shaped state without ownership marker was accepted");

        System.out.println("OK core-worlds structural vanilla/idempotent/unrelated/local-remap"
                + "/ambiguous/non-terminal/foreign-hook");
    }

    private static byte[] transform(PrepatcherConfig config, byte[] bytes) {
        return new PrepatcherTransformer(config).transform(null,
                PrepatcherTransformer.CORE_SCRIPT, null, null, bytes);
    }

    private static void assertPatched(byte[] bytes, int expectedLocal) {
        ClassNode node = read(bytes);
        MethodNode advance = method(node, "advance", "(F)V");
        int original = 0;
        int hooks = 0;
        for (AbstractInsnNode insn : advance.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.owner.equals("com/fs/starfarer/api/util/Misc")
                    && call.name.equals("computeCoreWorldsExtent")
                    && call.desc.equals("()V")) original++;
            if (call.owner.equals(RUNTIME) && call.name.equals("update")) {
                hooks++;
                AbstractInsnNode previous = previousMeaningful(call);
                require(previous instanceof VarInsnNode load
                                && load.getOpcode() == Opcodes.ALOAD
                                && load.var == expectedLocal,
                        "hook did not load derived sector local " + expectedLocal);
            }
        }
        require(original == 0 && hooks == 1,
                "unexpected patched call inventory original=" + original + " hooks=" + hooks);
    }

    private static byte[] mutate(byte[] source, boolean remapLocal, boolean addNop) {
        ClassNode node = read(source);
        MethodNode advance = method(node, "advance", "(F)V");
        if (remapLocal) {
            for (AbstractInsnNode insn : advance.instructions.toArray()) {
                if (insn instanceof VarInsnNode var && var.var == 2
                        && (var.getOpcode() == Opcodes.ALOAD
                        || var.getOpcode() == Opcodes.ASTORE)) {
                    var.var = 4;
                }
            }
            advance.maxLocals = Math.max(advance.maxLocals, 5);
        }
        if (addNop) advance.instructions.insert(new InsnNode(Opcodes.NOP));
        return write(node);
    }

    private static byte[] duplicateCoreWorldsCall(byte[] source) {
        ClassNode node = read(source);
        MethodNode advance = method(node, "advance", "(F)V");
        for (AbstractInsnNode insn : advance.instructions.toArray()) {
            if (insn instanceof MethodInsnNode call
                    && call.owner.equals("com/fs/starfarer/api/util/Misc")
                    && call.name.equals("computeCoreWorldsExtent")) {
                advance.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        call.owner, call.name, call.desc, false));
                return write(node);
            }
        }
        throw new AssertionError("vanilla core-worlds call missing");
    }

    private static byte[] moveCoreWorldsAwayFromTerminalBoundary(byte[] source) {
        ClassNode node = read(source);
        MethodNode advance = method(node, "advance", "(F)V");
        MethodInsnNode call = coreWorldsCall(advance);
        advance.instructions.insert(call, new InsnNode(Opcodes.NOP));
        return write(node);
    }

    private static byte[] installForeignHookWithoutMarker(byte[] source) {
        ClassNode node = read(source);
        MethodNode advance = method(node, "advance", "(F)V");
        MethodInsnNode call = coreWorldsCall(advance);
        int sectorLocal = sectorLocal(advance);
        advance.instructions.insertBefore(call,
                new VarInsnNode(Opcodes.ALOAD, sectorLocal));
        call.owner = RUNTIME;
        call.name = "update";
        call.desc = "(Lcom/fs/starfarer/api/campaign/SectorAPI;)V";
        call.setOpcode(Opcodes.INVOKESTATIC);
        call.itf = false;
        return write(node);
    }

    private static MethodInsnNode coreWorldsCall(MethodNode method) {
        MethodInsnNode found = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)
                    || !call.owner.equals("com/fs/starfarer/api/util/Misc")
                    || !call.name.equals("computeCoreWorldsExtent")
                    || !call.desc.equals("()V")) continue;
            require(found == null, "duplicate vanilla core-worlds call");
            found = call;
        }
        require(found != null, "vanilla core-worlds call missing");
        return found;
    }

    private static int sectorLocal(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)
                    || call.getOpcode() != Opcodes.INVOKESTATIC
                    || !call.owner.equals("com/fs/starfarer/api/Global")
                    || !call.name.equals("getSector")) continue;
            AbstractInsnNode next = call.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            require(next instanceof VarInsnNode store
                            && store.getOpcode() == Opcodes.ASTORE,
                    "Global.getSector result was not stored");
            return ((VarInsnNode) next).var;
        }
        throw new AssertionError("Global.getSector call missing");
    }

    private static byte[] readClass(Path jar, String internalName) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.getJarEntry(internalName + ".class");
            require(entry != null, "class missing from JAR: " + internalName);
            try (InputStream input = file.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
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
        MethodNode found = null;
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name) || !method.desc.equals(desc)) continue;
            require(found == null, "duplicate method " + name + desc);
            found = method;
        }
        require(found != null, "method missing " + name + desc);
        return found;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node.getPrevious();
        while (current != null && current.getOpcode() < 0) current = current.getPrevious();
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
