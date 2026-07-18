package com.starsector.prepatcher.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class PrepatcherAgent {
    public static final String VERSION = "0.9.1";
    private PrepatcherAgent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        Path agentJar = locateAgentJar();
        Path modRoot = locateModRoot(agentJar);
        Path logPath = modRoot.resolve("logs").resolve("prepatcher.log");
        PrepatcherLog.initialize(logPath);
        PrepatcherLog.info("StarsectorPrepatcher " + VERSION + " javaagent starting");
        PrepatcherLog.info("Agent JAR: " + agentJar);
        PrepatcherLog.info("Mod root: " + modRoot);

        try {
            Path configPath = resolveConfigPath(agentArgs, modRoot);
            PrepatcherConfig config = PrepatcherConfig.load(configPath);

            System.setProperty("starsector.prepatcher.agentActive", "true");
            System.setProperty("starsector.prepatcher.version", VERSION);
            System.setProperty("starsector.prepatcher.log", logPath.toString());

            if (!config.enabled) {
                PrepatcherLog.warn("Prepatcher disabled by configuration; no bytecode patches registered.");
                System.setProperty("starsector.prepatcher.status", "disabled-by-config");
                return;
            }

            PrepatcherTransformer transformPlan = new PrepatcherTransformer(config);
            Class<?> loadedTarget = findLoadedTarget(instrumentation, transformPlan,
                    ClassLoader.getSystemClassLoader());
            if (loadedTarget != null) {
                failForLoadedTarget("before runtime installation", loadedTarget,
                        "target-loaded-before-runtime");
                return;
            }

            ClassLoader runtimeLoader;
            try {
                runtimeLoader = RuntimeInstaller.install(agentJar, config, modRoot);
            } catch (ClassNotFoundException ex) {
                System.setProperty("starsector.prepatcher.status", "runtime-unavailable");
                PrepatcherLog.warn("Starsector runtime is unavailable; no bytecode patches registered: "
                        + ex.getMessage());
                return;
            }

            loadedTarget = findLoadedTarget(instrumentation, transformPlan, runtimeLoader);
            if (loadedTarget != null) {
                failForLoadedTarget("during runtime installation", loadedTarget,
                        "target-loaded-during-runtime-install");
                return;
            }

            exportInternalAsm(instrumentation);
            ClassFileTransformer transformer = new PrepatcherTransformer(config, runtimeLoader);
            instrumentation.addTransformer(transformer, false);
            System.setProperty("starsector.prepatcher.status", "transformer-installed");
            PrepatcherLog.info("Unified transformer installed. Each patch will be matched, applied,"
                    + " and verified independently as its target class loads.");
        } catch (Throwable ex) {
            System.setProperty("starsector.prepatcher.status", "agent-error");
            PrepatcherLog.error("Fatal agent initialization error; prepatcher has failed open and the game will continue unpatched.", ex);
        }
    }

    private static Class<?> findLoadedTarget(Instrumentation instrumentation,
                                             PrepatcherTransformer transformPlan,
                                             ClassLoader runtimeLoader) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String internalName = loaded.getName().replace('.', '/');
            if (!PrepatcherTransformer.TARGET_CLASSES.contains(internalName)
                    || !transformPlan.isTargetEnabled(internalName)) {
                continue;
            }
            ClassLoader expectedLoader = runtimeLoader;
            if (PrepatcherTransformer.SOUND.equals(internalName)
                    && runtimeLoader != null && runtimeLoader.getParent() != null) {
                // Vanilla owns sound.Sound in the system loader; FR keeps it
                // in that custom loader's parent. Ignore unrelated duplicate
                // names, but fail closed if the actual enabled target is late.
                ClassLoader actual = loaded.getClassLoader();
                if (actual == runtimeLoader || actual == runtimeLoader.getParent()) return loaded;
                continue;
            }
            if (loaded.getClassLoader() == expectedLoader) return loaded;
        }
        return null;
    }

    private static void failForLoadedTarget(String phase, Class<?> target, String status) {
        System.setProperty("starsector.prepatcher.status", status);
        ClassLoader loader = target.getClassLoader();
        String loaderName = loader == null ? "bootstrap"
                : loader.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(loader));
        PrepatcherLog.warn("Target class was already loaded " + phase
                + "; no bytecode patches registered: " + target.getName()
                + " (loader=" + loaderName + ")");
    }

    private static void exportInternalAsm(Instrumentation instrumentation) {
        Module javaBase = Object.class.getModule();
        Module ours = PrepatcherAgent.class.getModule();
        Map<String, Set<Module>> exports = new HashMap<>();
        exports.put("jdk.internal.org.objectweb.asm", Collections.singleton(ours));
        exports.put("jdk.internal.org.objectweb.asm.tree", Collections.singleton(ours));
        exports.put("jdk.internal.org.objectweb.asm.tree.analysis", Collections.singleton(ours));
        instrumentation.redefineModule(
                javaBase,
                Collections.emptySet(),
                exports,
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap());
        PrepatcherLog.info("Exported JDK-internal ASM packages to the prepatcher agent module.");
    }

    private static Path locateAgentJar() {
        try {
            URI uri = PrepatcherAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Paths.get(uri).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return Paths.get("../mods/StarsectorPrepatcher/agent/StarsectorPrepatcherAgent.jar")
                    .toAbsolutePath().normalize();
        }
    }

    private static Path locateModRoot(Path agentJar) {
        Path parent = agentJar.getParent();
        if (parent != null && parent.getFileName() != null
                && "agent".equalsIgnoreCase(parent.getFileName().toString())) {
            Path root = parent.getParent();
            if (root != null) return root;
        }
        return Paths.get("../mods/StarsectorPrepatcher").toAbsolutePath().normalize();
    }

    private static Path resolveConfigPath(String args, Path modRoot) {
        if (args == null || args.isBlank()) {
            return modRoot.resolve("prepatcher.properties");
        }
        String value = args.trim();
        if (value.startsWith("config=")) value = value.substring("config=".length()).trim();
        Path path = Paths.get(value);
        if (!path.isAbsolute()) {
            Path cwdRelative = path.toAbsolutePath().normalize();
            if (Files.isRegularFile(cwdRelative)) return cwdRelative;
            path = modRoot.resolve(path).normalize();
        }
        return path;
    }

}
