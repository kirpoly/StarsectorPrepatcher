package com.starsector.prepatcher.runtime;

/** Loads CoreScript through the real javaagent and verifies patch/runtime loader state. */
public final class CoreWorldsActualAgentSmokeTest {
    private CoreWorldsActualAgentSmokeTest() {}

    public static void main(String[] args) throws Exception {
        Class<?> coreScript = Class.forName(
                "com.fs.starfarer.api.impl.campaign.CoreScript", false,
                ClassLoader.getSystemClassLoader());
        String status = System.getProperty(
                "starsector.prepatcher.patchStatus.com.fs.starfarer.api.impl.campaign.CoreScript.coreWorldsExtentCache");
        require("APPLIED".equals(status), "unexpected CoreScript patch status: " + status);
        Class<?> runtime = Class.forName(
                "com.fs.starfarer.api.StarsectorPrepatcherCoreWorldsRuntime", false,
                ClassLoader.getSystemClassLoader());
        require(coreScript.getClassLoader() == runtime.getClassLoader(),
                "CoreScript/runtime loader mismatch");
        System.out.println("OK actual-agent core-worlds status=" + status
                + " loader=" + coreScript.getClassLoader());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
