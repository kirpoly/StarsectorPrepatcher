
package com.starsector.prepatcher.hyperspace;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class AgentConfig {
    final boolean enabled;
    final boolean culling;
    final boolean cullingClamp;
    final boolean noOpLayer;
    final boolean randomReuse;
    final boolean automatonBuffer;
    final boolean cleanupBuffers;
    final boolean optimizedRemoveAll;
    final int removeAllThreshold;
    final long statsIntervalNanos;
    final Path modRoot;

    AgentConfig(Path root, Properties p) {
        modRoot=root;
        enabled=b(p,"enabled",true);
        culling=b(p,"patch.hyperspaceCulling",true);
        cullingClamp=b(p,"patch.hyperspaceYClamp",true);
        noOpLayer=b(p,"patch.skipNoOpTerrainLayer",true);
        randomReuse=b(p,"patch.terrainRandomReuse",true);
        automatonBuffer=b(p,"patch.automatonBufferReuse",true);
        cleanupBuffers=b(p,"patch.starfieldCleanupBuffers",true);
        optimizedRemoveAll=b(p,"patch.starfieldLinearRemoval",true);
        removeAllThreshold=i(p,"removeAll.threshold",8,1,4096);
        statsIntervalNanos=(long)(f(p,"stats.intervalSeconds",30f,1f,3600f)*1_000_000_000L);
    }
    static AgentConfig load(Path root) {
        Properties p=new Properties(); Path f=root.resolve("hyperspace-prepatcher.properties");
        if(Files.isRegularFile(f)) try(InputStream in=Files.newInputStream(f)){p.load(in);}catch(Throwable t){AgentLog.error("config read failed",t);}
        return new AgentConfig(root,p);
    }
    static boolean b(Properties p,String k,boolean d){return Boolean.parseBoolean(p.getProperty(k,Boolean.toString(d)).trim());}
    static int i(Properties p,String k,int d,int lo,int hi){try{return Math.max(lo,Math.min(hi,Integer.parseInt(p.getProperty(k,Integer.toString(d)).trim())));}catch(Exception e){return d;}}
    static float f(Properties p,String k,float d,float lo,float hi){try{return Math.max(lo,Math.min(hi,Float.parseFloat(p.getProperty(k,Float.toString(d)).trim())));}catch(Exception e){return d;}}
}
