package com.starsector.mapoptimizer.agent;

import com.starsector.mapoptimizer.runtime.MapOptimizerHooks;

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

public final class MapOptimizerAgent {
    public static final String VERSION = "0.4.0-exp4";

    private MapOptimizerAgent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        Path agentJar = locateAgentJar();
        Path modRoot = locateModRoot(agentJar);
        Path logPath = modRoot.resolve("logs").resolve("map-optimizer.log");
        OptimizerLog.initialize(logPath);
        OptimizerLog.info("Starsector Map Optimizer " + VERSION + " javaagent starting");
        OptimizerLog.info("Agent JAR: " + agentJar);
        OptimizerLog.info("Mod root: " + modRoot);

        try {
            Path configPath = resolveConfigPath(agentArgs, modRoot);
            OptimizerConfig config = OptimizerConfig.load(configPath);

            System.setProperty("starsector.mapoptimizer.agentActive", "true");
            System.setProperty("starsector.mapoptimizer.version", VERSION);
            System.setProperty("starsector.mapoptimizer.log", logPath.toString());

            if (!config.enabled) {
                OptimizerLog.warn("Optimizer disabled by configuration; no bytecode patches registered.");
                System.setProperty("starsector.mapoptimizer.status", "disabled-by-config");
                return;
            }

            MapOptimizerHooks.configure(config, modRoot);
            exportInternalAsm(instrumentation);
            ClassFileTransformer transformer = new MapOptimizerTransformer(config);
            instrumentation.addTransformer(transformer, false);
            System.setProperty("starsector.mapoptimizer.status", "structural-transformer-installed");
            OptimizerLog.info("Structural transformer installed. Each patch will be matched, applied,"
                    + " and verified independently as its target class loads.");
        } catch (Throwable ex) {
            System.setProperty("starsector.mapoptimizer.status", "agent-error");
            OptimizerLog.error("Fatal agent initialization error; optimizer has failed open and the game will continue unpatched.", ex);
        }
    }

    private static void exportInternalAsm(Instrumentation instrumentation) {
        Module javaBase = Object.class.getModule();
        Module ours = MapOptimizerAgent.class.getModule();
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
        OptimizerLog.info("Exported JDK-internal ASM packages to the optimizer agent module.");
    }

    private static Path locateAgentJar() {
        try {
            URI uri = MapOptimizerAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Paths.get(uri).toAbsolutePath().normalize();
        } catch (Exception ex) {
            return Paths.get("../mods/StarsectorMapOptimizer/agent/StarsectorMapOptimizerAgent.jar")
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
        return Paths.get("../mods/StarsectorMapOptimizer").toAbsolutePath().normalize();
    }

    private static Path resolveConfigPath(String args, Path modRoot) {
        if (args == null || args.isBlank()) return modRoot.resolve("optimizer.properties");
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
