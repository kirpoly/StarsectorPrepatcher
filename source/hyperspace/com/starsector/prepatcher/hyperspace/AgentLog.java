
package com.starsector.prepatcher.hyperspace;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

final class AgentLog {
    private static PrintWriter out;
    static synchronized void init(Path modRoot) {
        try {
            Path dir=modRoot.resolve("logs"); Files.createDirectories(dir);
            out=new PrintWriter(new OutputStreamWriter(new FileOutputStream(dir.resolve("prepatcher-hyperspace.log").toFile(),true),StandardCharsets.UTF_8),true);
        } catch (Throwable t) { t.printStackTrace(); }
    }
    static synchronized void info(String s) {
        String line="["+Instant.now()+"] "+s;
        System.out.println("[StarsectorPrepatcher/Hyperspace] "+s);
        if(out!=null) out.println(line);
    }
    static synchronized void error(String s,Throwable t) {
        info(s+": "+t);
        if(out!=null) t.printStackTrace(out);
    }
}
