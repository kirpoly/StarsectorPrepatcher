
package com.starsector.prepatcher.hyperspace;

import java.lang.instrument.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public final class HyperspaceAgent {
    public static final String VERSION = "0.8.0";
    public static void premain(String args,Instrumentation inst){
        System.setProperty("starsector.prepatcher.hyperspaceAgentActive", "true");
        System.setProperty("starsector.prepatcher.hyperspaceVersion", VERSION);
        try{
            Path root=root();AgentLog.init(root);AgentLog.info("StarsectorPrepatcher Hyperspace "+VERSION+" starting; root="+root);
            AgentConfig cfg=AgentConfig.load(root);if(!cfg.enabled){System.setProperty("starsector.prepatcher.hyperspaceStatus", "disabled-by-config");AgentLog.info("disabled by config");return;}
            exportInternalAsm(inst);TargetRegistry r=TargetRegistry.load();HyperspaceHooks.init(cfg);inst.addTransformer(new HyperspaceTransformer(cfg,r),false);
            System.setProperty("starsector.prepatcher.hyperspaceStatus", "transformer-installed");
            System.setProperty("starsector.prepatcher.hyperspaceTargets", Integer.toString(r.all().size()));
            AgentLog.info("installed targets="+r.all().size()+"; exact per-class hash and patch-count guards armed");
        }catch(Throwable t){System.setProperty("starsector.prepatcher.hyperspaceStatus", "agent-error");AgentLog.error("agent startup failed; vanilla retained",t);}
    }

    static void exportInternalAsm(Instrumentation inst){
        Module javaBase=Object.class.getModule(), ours=HyperspaceAgent.class.getModule();
        Map<String,Set<Module>> exports=new HashMap<>();
        exports.put("jdk.internal.org.objectweb.asm",Collections.singleton(ours));
        exports.put("jdk.internal.org.objectweb.asm.tree",Collections.singleton(ours));
        exports.put("jdk.internal.org.objectweb.asm.tree.analysis",Collections.singleton(ours));
        inst.redefineModule(javaBase,Collections.emptySet(),exports,Collections.emptyMap(),Collections.emptySet(),Collections.emptyMap());
    }
    static Path root()throws Exception{URL u=HyperspaceAgent.class.getProtectionDomain().getCodeSource().getLocation();Path p=Paths.get(u.toURI()).toAbsolutePath();return Files.isDirectory(p)?p:p.getParent().getParent();}
}
