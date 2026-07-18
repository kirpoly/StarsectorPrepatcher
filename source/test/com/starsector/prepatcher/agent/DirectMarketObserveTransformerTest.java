package com.starsector.prepatcher.agent;

import com.fs.starfarer.api.campaign.econ.MarketAPI;
import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Structural and synchronous-behavior test for arbitrary mod call-site observation. */
public final class DirectMarketObserveTransformerTest {
    private static final String CLASS_NAME = "test/mod/DirectMarketCaller";
    private static final String HOOKS = "com/fs/starfarer/api/StarsectorPrepatcherHooks";
    private static final String HOOK_DESC =
            "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;FJLjava/lang/String;)V";

    private DirectMarketObserveTransformerTest() {}

    public static void main(String[] args) throws Exception {
        PrepatcherConfig config = config();
        ClassLoader parent = ClassLoader.getSystemClassLoader();
        ChildLoader loader = new ChildLoader(parent);
        ProtectionDomain domain = new ProtectionDomain(
                new CodeSource(new URL("file:/test/Starsector/mods/TestMod/jars/test.jar"),
                        (Certificate[]) null), null, loader, null);
        byte[] original = generateClass();
        DirectMarketObserveTransformer transformer =
                new DirectMarketObserveTransformer(config, parent, null);
        byte[] transformed = transformer.transform(loader, CLASS_NAME, null, domain, original);
        require(transformed != null, "candidate mod class was not transformed");

        ClassNode node = read(transformed);
        int originals = 0;
        int hooks = 0;
        List<String> metadata = new ArrayList<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call) {
                    if ("advance".equals(call.name) && "(F)V".equals(call.desc)
                            && "com/fs/starfarer/api/campaign/econ/MarketAPI".equals(call.owner)) {
                        originals++;
                    }
                    if (call.getOpcode() == Opcodes.INVOKESTATIC
                            && HOOKS.equals(call.owner)
                            && "observeDirectMarketAdvance".equals(call.name)
                            && HOOK_DESC.equals(call.desc)) {
                        hooks++;
                    }
                }
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text
                        && text.indexOf('\u001f') >= 0) {
                    metadata.add(text);
                }
            }
        }
        require(originals == 0, "original MarketAPI.advance calls remain: " + originals);
        require(hooks == 4, "expected four observation hooks, found " + hooks);
        require(metadata.stream().anyMatch(value -> value.contains("ARG1")),
                "float argument source was not classified");
        require(metadata.stream().anyMatch(value -> value.contains("CONST:1.0")),
                "constant amount source was not classified");

        Class<?> callerClass = loader.define(CLASS_NAME.replace('/', '.'), transformed);
        Object caller = callerClass.getConstructor().newInstance();
        Counter counter = new Counter();
        MarketAPI market = (MarketAPI) Proxy.newProxyInstance(parent,
                new Class<?>[]{MarketAPI.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("advance")) {
                        counter.calls++;
                        counter.sum += ((Float) arguments[0]);
                        if (counter.throwNext) throw new IllegalStateException("expected");
                    }
                    return defaultValue(method.getReturnType());
                });

        callerClass.getMethod("passThrough", MarketAPI.class, float.class)
                .invoke(caller, market, 2.5f);
        callerClass.getMethod("constant", MarketAPI.class).invoke(caller, market);
        callerClass.getMethod("duplicate", MarketAPI.class, float.class)
                .invoke(caller, market, 3f);
        require(counter.calls == 4, "wrapper changed call multiplicity: " + counter.calls);
        require(Float.floatToIntBits(counter.sum) == Float.floatToIntBits(9.5f),
                "wrapper changed delivered amount: " + counter.sum);

        counter.throwNext = true;
        try {
            callerClass.getMethod("passThrough", MarketAPI.class, float.class)
                    .invoke(caller, market, 1f);
            throw new AssertionError("original exception was suppressed");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof IllegalStateException,
                    "wrong exception propagated: " + expected.getCause());
        }

        System.out.println("OK direct-market-transformer callSites=" + hooks
                + " calls=" + counter.calls);
    }

    private static PrepatcherConfig config() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.directMarketObservation", "true");
        properties.setProperty("logging.statsIntervalSeconds", "0");
        Constructor<PrepatcherConfig> constructor =
                PrepatcherConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        return constructor.newInstance(properties);
    }

    private static byte[] generateClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                CLASS_NAME, null, "java/lang/Object", null);

        var ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        var pass = writer.visitMethod(Opcodes.ACC_PUBLIC, "passThrough",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;F)V", null, null);
        pass.visitCode();
        jdk.internal.org.objectweb.asm.Label passLine = new jdk.internal.org.objectweb.asm.Label();
        pass.visitLabel(passLine);
        pass.visitLineNumber(10, passLine);
        pass.visitVarInsn(Opcodes.ALOAD, 1);
        pass.visitVarInsn(Opcodes.FLOAD, 2);
        pass.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance", "(F)V", true);
        pass.visitInsn(Opcodes.RETURN);
        pass.visitMaxs(0, 0);
        pass.visitEnd();

        var constant = writer.visitMethod(Opcodes.ACC_PUBLIC, "constant",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;)V", null, null);
        constant.visitCode();
        constant.visitVarInsn(Opcodes.ALOAD, 1);
        constant.visitInsn(Opcodes.FCONST_1);
        constant.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance", "(F)V", true);
        constant.visitInsn(Opcodes.RETURN);
        constant.visitMaxs(0, 0);
        constant.visitEnd();

        var duplicate = writer.visitMethod(Opcodes.ACC_PUBLIC, "duplicate",
                "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;F)V", null, null);
        duplicate.visitCode();
        duplicate.visitVarInsn(Opcodes.ALOAD, 1);
        duplicate.visitVarInsn(Opcodes.FLOAD, 2);
        duplicate.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance", "(F)V", true);
        duplicate.visitVarInsn(Opcodes.ALOAD, 1);
        duplicate.visitVarInsn(Opcodes.FLOAD, 2);
        duplicate.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/fs/starfarer/api/campaign/econ/MarketAPI", "advance", "(F)V", true);
        duplicate.visitInsn(Opcodes.RETURN);
        duplicate.visitMaxs(0, 0);
        duplicate.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Counter {
        int calls;
        float sum;
        boolean throwNext;
    }

    private static final class ChildLoader extends ClassLoader {
        ChildLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
