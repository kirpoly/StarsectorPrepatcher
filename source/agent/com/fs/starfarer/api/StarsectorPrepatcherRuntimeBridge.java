package com.fs.starfarer.api;

import com.starsector.prepatcher.agent.PrepatcherConfig;

import java.nio.file.Path;

/**
 * Loader-neutral initialization boundary for the target-loader runtime.
 *
 * <p>The javaagent deliberately reaches this class only through reflection.
 * Keeping {@link Object} and JDK types in the descriptor prevents the agent
 * loader from resolving any Starsector type while installing the runtime.</p>
 */
public final class StarsectorPrepatcherRuntimeBridge {
    private StarsectorPrepatcherRuntimeBridge() {}

    public static void configure(Object rawConfig, Path modRoot) {
        if (!(rawConfig instanceof PrepatcherConfig config)) {
            String actual = rawConfig == null ? "null" : rawConfig.getClass().getName();
            throw new IllegalArgumentException("Unexpected prepatcher configuration type: " + actual);
        }
        StarsectorPrepatcherHooks.configure(config, modRoot);
        StarsectorPrepatcherHyperspaceHooks.configure(config);
        StarsectorPrepatcherPresentationHooks.configure(config);
    }

    /** Loader-neutral registration endpoint used by the mod call-site transformer. */
    public static void registerDirectMarketCallSite(long siteId, String metadata) {
        StarsectorPrepatcherHooks.registerDirectMarketCallSite(siteId, metadata);
    }
}
