package com.starsector.prepatcher.agent;

import jdk.internal.org.objectweb.asm.ClassReader;
import jdk.internal.org.objectweb.asm.ClassWriter;
import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.internal.org.objectweb.asm.Type;
import jdk.internal.org.objectweb.asm.tree.AbstractInsnNode;
import jdk.internal.org.objectweb.asm.tree.ClassNode;
import jdk.internal.org.objectweb.asm.tree.FrameNode;
import jdk.internal.org.objectweb.asm.tree.InsnList;
import jdk.internal.org.objectweb.asm.tree.InsnNode;
import jdk.internal.org.objectweb.asm.tree.LabelNode;
import jdk.internal.org.objectweb.asm.tree.LdcInsnNode;
import jdk.internal.org.objectweb.asm.tree.LineNumberNode;
import jdk.internal.org.objectweb.asm.tree.MethodInsnNode;
import jdk.internal.org.objectweb.asm.tree.MethodNode;
import jdk.internal.org.objectweb.asm.tree.VarInsnNode;
import jdk.internal.org.objectweb.asm.tree.analysis.Analyzer;
import jdk.internal.org.objectweb.asm.tree.analysis.AnalyzerException;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicValue;
import jdk.internal.org.objectweb.asm.tree.analysis.BasicVerifier;

import java.lang.instrument.ClassFileTransformer;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observation-only transformer for direct calls made by mod bytecode to
 * {@code MarketAPI.advance(float)}.
 *
 * <p>The original synchronous call is replaced by a typed static wrapper with
 * the same receiver/argument behavior. The wrapper records aggregate telemetry
 * and invokes the original market immediately. No call is delayed, merged or
 * suppressed by this transformer.</p>
 */
public final class DirectMarketObserveTransformer implements ClassFileTransformer {
    private static final String HOOKS = "com/fs/starfarer/api/StarsectorPrepatcherHooks";
    private static final String MARKET_API = "com/fs/starfarer/api/campaign/econ/MarketAPI";
    private static final String MARKET_IMPL = "com/fs/starfarer/campaign/econ/Market";
    private static final String HOOK_NAME = "observeDirectMarketAdvance";
    private static final String HOOK_DESC =
            "(Lcom/fs/starfarer/api/campaign/econ/MarketAPI;FJLjava/lang/String;)V";
    private static final char FIELD_SEPARATOR = '\u001f';

    private final PrepatcherConfig config;
    private final ClassLoader runtimeLoader;
    private final Path modRoot;
    private final AtomicInteger patchedClasses = new AtomicInteger();
    private final AtomicInteger patchedCallSites = new AtomicInteger();
    private final Set<String> warnedClasses = java.util.Collections.synchronizedSet(new HashSet<>());

    public DirectMarketObserveTransformer(PrepatcherConfig config,
                                          ClassLoader runtimeLoader,
                                          Path modRoot) {
        this.config = config;
        this.runtimeLoader = runtimeLoader;
        this.modRoot = modRoot == null ? null : modRoot.toAbsolutePath().normalize();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!config.directMarketObservation
                || classfileBuffer == null
                || !shouldInspect(loader, className, protectionDomain)) {
            return null;
        }

        try {
            ClassNode node = readClass(classfileBuffer);
            String source = sourceLabel(protectionDomain, loader);
            int originalCalls = countOriginalCalls(node);
            int existingHooks = countHookCalls(node);
            if (originalCalls == 0) return null;
            if (existingHooks != 0) {
                warnOnce(className, "direct-market observation hook already exists while original "
                        + "Market.advance call sites remain; leaving class untouched");
                return null;
            }

            List<MethodNode> changedMethods = new ArrayList<>();
            int changed = 0;
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                int line = -1;
                int ordinal = 0;
                boolean methodChanged = false;
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn instanceof LineNumberNode lineNode) {
                        line = lineNode.line;
                        continue;
                    }
                    if (!(insn instanceof MethodInsnNode call) || !isMarketAdvance(call)) continue;

                    String amountSource = classifyAmountSource(method, call);
                    String metadata = metadata(source, node.name, method.name, method.desc,
                            line, ordinal, amountSource, call.owner, call.getOpcode());
                    long siteId = fnv1a64(metadata);

                    InsnList replacement = new InsnList();
                    replacement.add(new LdcInsnNode(siteId));
                    replacement.add(new LdcInsnNode(metadata));
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            HOOKS, HOOK_NAME, HOOK_DESC, false));
                    method.instructions.insertBefore(call, replacement);
                    method.instructions.remove(call);
                    methodChanged = true;
                    changed++;
                    ordinal++;
                }
                if (methodChanged) changedMethods.add(method);
            }

            if (changed != originalCalls) {
                warnOnce(className, "direct-market observation structural mismatch: expected "
                        + originalCalls + " call sites, transformed " + changed);
                return null;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            byte[] candidate = writer.toByteArray();
            verifyTouchedMethods(candidate, changedMethods);

            ClassNode reparsed = readClass(candidate);
            if (countOriginalCalls(reparsed) != 0 || countHookCalls(reparsed) != changed) {
                warnOnce(className, "direct-market observation postcondition mismatch");
                return null;
            }

            int classes = patchedClasses.incrementAndGet();
            int sites = patchedCallSites.addAndGet(changed);
            System.setProperty("starsector.prepatcher.directMarketPatchedClasses",
                    Integer.toString(classes));
            System.setProperty("starsector.prepatcher.directMarketPatchedCallSites",
                    Integer.toString(sites));
            PrepatcherLog.info("DIRECT_MARKET_OBSERVE patched " + className
                    + " from " + source + ": callSites=" + changed
                    + ", loader=" + loaderName(loader));
            return candidate;
        } catch (Throwable failure) {
            warnOnce(className, "direct-market observation failed open: "
                    + failure.getClass().getName() + ": " + failure.getMessage());
            return null;
        }
    }

    private boolean shouldInspect(ClassLoader loader, String className,
                                  ProtectionDomain protectionDomain) {
        if (loader == null || className == null || className.isEmpty()) return false;
        if (className.equals("module-info") || className.endsWith("/module-info")) return false;
        if (className.startsWith("com/starsector/prepatcher/")
                || className.startsWith("com/fs/starfarer/api/StarsectorPrepatcher")) {
            return false;
        }
        if (className.startsWith("java/") || className.startsWith("javax/")
                || className.startsWith("jdk/") || className.startsWith("sun/")) {
            return false;
        }
        if (runtimeLoader != null && !isSameOrDescendant(loader, runtimeLoader)) return false;

        String source = sourceLocation(protectionDomain).toLowerCase(Locale.ROOT)
                .replace('\\', '/');
        if (source.contains("starsectorprepatcheragent.jar")
                || source.contains("starsectorprepatcherbootstrap.jar")
                || source.endsWith("/starfarer_obf.jar")
                || source.endsWith("/starfarer.api.jar")
                || source.endsWith("/fs.common_obf.jar")
                || source.endsWith("/fs.sound_obf.jar")) {
            return false;
        }

        // System-loader classes are game/library classes unless their code source
        // explicitly lives in the mods tree. Child script/plugin loaders are the
        // normal path for mod classes and are accepted even when CodeSource is null.
        if (loader == runtimeLoader && !source.contains("/mods/")) return false;
        return true;
    }

    private static boolean isSameOrDescendant(ClassLoader loader, ClassLoader ancestor) {
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            if (current == ancestor) return true;
        }
        return false;
    }

    private static int countOriginalCalls(ClassNode node) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && isMarketAdvance(call)) count++;
            }
        }
        return count;
    }

    private static int countHookCalls(ClassNode node) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && HOOKS.equals(call.owner)
                        && HOOK_NAME.equals(call.name)
                        && HOOK_DESC.equals(call.desc)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isMarketAdvance(MethodInsnNode call) {
        if (!"advance".equals(call.name) || !"(F)V".equals(call.desc)) return false;
        if (MARKET_API.equals(call.owner)) {
            return call.getOpcode() == Opcodes.INVOKEINTERFACE
                    || call.getOpcode() == Opcodes.INVOKEVIRTUAL;
        }
        return MARKET_IMPL.equals(call.owner) && call.getOpcode() == Opcodes.INVOKEVIRTUAL;
    }

    private static String classifyAmountSource(MethodNode method, MethodInsnNode call) {
        AbstractInsnNode source = previousMeaningful(call.getPrevious());
        if (source == null) return "UNKNOWN";
        int opcode = source.getOpcode();
        if (source instanceof VarInsnNode variable && opcode == Opcodes.FLOAD) {
            int argument = argumentAtLocal(method.access, method.desc, variable.var);
            return argument >= 0 ? "ARG" + argument : "LOCAL" + variable.var;
        }
        if (source instanceof LdcInsnNode constant && constant.cst instanceof Float value) {
            return "CONST:" + Float.toString(value);
        }
        return switch (opcode) {
            case Opcodes.FCONST_0 -> "CONST:0.0";
            case Opcodes.FCONST_1 -> "CONST:1.0";
            case Opcodes.FCONST_2 -> "CONST:2.0";
            case Opcodes.FADD, Opcodes.FSUB, Opcodes.FMUL, Opcodes.FDIV, Opcodes.FREM,
                    Opcodes.FNEG, Opcodes.I2F, Opcodes.L2F, Opcodes.D2F -> "DERIVED";
            case Opcodes.GETFIELD, Opcodes.GETSTATIC -> "FIELD";
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE,
                    Opcodes.INVOKESTATIC, Opcodes.INVOKESPECIAL -> "CALL_RESULT";
            default -> "OPCODE:" + opcode;
        };
    }

    private static int argumentAtLocal(int access, String descriptor, int wantedLocal) {
        int local = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < arguments.length; i++) {
            if (local == wantedLocal && arguments[i].getSort() == Type.FLOAT) return i;
            local += arguments[i].getSize();
        }
        return -1;
    }

    private static AbstractInsnNode previousMeaningful(AbstractInsnNode node) {
        AbstractInsnNode current = node;
        while (current instanceof LabelNode || current instanceof LineNumberNode
                || current instanceof FrameNode || current.getOpcode() < 0) {
            current = current.getPrevious();
            if (current == null) return null;
        }
        return current;
    }

    private String sourceLabel(ProtectionDomain protectionDomain, ClassLoader loader) {
        String raw = sourceLocation(protectionDomain);
        if (!raw.isEmpty()) {
            try {
                Path path = Path.of(new URI(raw)).toAbsolutePath().normalize();
                if (modRoot != null) {
                    Path gameRoot = modRoot.getParent() == null ? null : modRoot.getParent().getParent();
                    if (gameRoot != null && path.startsWith(gameRoot)) {
                        return sanitize(gameRoot.relativize(path).toString().replace('\\', '/'));
                    }
                }
                return sanitize(path.toString().replace('\\', '/'));
            } catch (Throwable ignored) {
                return sanitize(raw);
            }
        }
        return "loader:" + loaderName(loader);
    }

    private static String sourceLocation(ProtectionDomain protectionDomain) {
        try {
            CodeSource codeSource = protectionDomain == null ? null : protectionDomain.getCodeSource();
            URL location = codeSource == null ? null : codeSource.getLocation();
            return location == null ? "" : location.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String metadata(String source, String className, String method,
                                   String descriptor, int line, int ordinal,
                                   String amountSource, String callOwner, int opcode) {
        return String.join(String.valueOf(FIELD_SEPARATOR),
                "1",
                sanitize(source),
                sanitize(className.replace('/', '.')),
                sanitize(method),
                sanitize(descriptor),
                Integer.toString(line),
                Integer.toString(ordinal),
                sanitize(amountSource),
                sanitize(callOwner.replace('/', '.')),
                Integer.toString(opcode));
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = null;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character != FIELD_SEPARATOR && character != '\r' && character != '\n') {
                if (result != null) result.append(character);
                continue;
            }
            if (result == null) {
                result = new StringBuilder(value.length());
                result.append(value, 0, i);
            }
            result.append(' ');
        }
        return result == null ? value : result.toString();
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            hash ^= character & 0xffL;
            hash *= 0x100000001b3L;
            hash ^= character >>> 8;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM8);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static void verifyTouchedMethods(byte[] bytes, List<MethodNode> originalMethods) {
        Set<String> touched = new HashSet<>();
        for (MethodNode method : originalMethods) touched.add(method.name + method.desc);
        ClassNode node = readClass(bytes);
        for (MethodNode method : node.methods) {
            if (!touched.contains(method.name + method.desc)
                    || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                continue;
            }
            try {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(node.name, method);
            } catch (AnalyzerException failure) {
                throw new IllegalStateException("BasicVerifier rejected " + node.name + "."
                        + method.name + method.desc + ": " + failure.getMessage(), failure);
            }
        }
    }

    private void warnOnce(String className, String message) {
        String key = className == null ? "<unknown>" : className;
        if (warnedClasses.add(key)) {
            PrepatcherLog.warn("DIRECT_MARKET_OBSERVE " + key + ": " + message);
        }
    }

    private static String loaderName(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
    }
}
