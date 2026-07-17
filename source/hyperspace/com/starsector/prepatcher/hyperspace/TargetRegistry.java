
package com.starsector.prepatcher.hyperspace;

import java.io.*;
import java.util.*;

final class TargetRegistry {
    static final class Target {
        final String name;
        final Set<String> hashes;
        final Set<String> kinds;
        final Map<String,Integer> expectedCounts;

        Target(String name, Set<String> hashes, Set<String> kinds, Map<String,Integer> expectedCounts) {
            this.name=name;
            this.hashes=Collections.unmodifiableSet(new LinkedHashSet<>(hashes));
            this.kinds=Collections.unmodifiableSet(new LinkedHashSet<>(kinds));
            this.expectedCounts=Collections.unmodifiableMap(new LinkedHashMap<>(expectedCounts));
        }

        boolean acceptsHash(String hash) {
            for(String candidate:hashes) if(candidate.equalsIgnoreCase(hash)) return true;
            return false;
        }

        boolean acceptsCounts(Map<String,Integer> actual) {
            for(Map.Entry<String,Integer> e:actual.entrySet()) {
                Integer expected=expectedCounts.get(e.getKey());
                if(expected==null || !expected.equals(e.getValue())) return false;
            }
            return true;
        }
    }
    private final Map<String,Target> targets=new LinkedHashMap<>();
    static TargetRegistry load() throws IOException {
        Properties p=new Properties();
        try(InputStream in=TargetRegistry.class.getResourceAsStream("/hyperspace-targets.properties")){
            if(in==null) throw new FileNotFoundException("hyperspace-targets.properties"); p.load(in);
        }
        TargetRegistry r=new TargetRegistry();
        ArrayList<String> names=new ArrayList<>(p.stringPropertyNames());
        Collections.sort(names);
        for(String n:names){
            if(!n.startsWith("target.")) continue;
            String cn=n.substring(7); String[] v=p.getProperty(n).split(";",3);
            if(v.length!=3) throw new IOException("Invalid target entry (hashes;kinds;counts required): "+n);
            Set<String> hashes=tokens(v[0]);
            Set<String> kinds=tokens(v[1]);
            LinkedHashMap<String,Integer> counts=new LinkedHashMap<>();
            for(String token:tokens(v[2])) {
                String[] pair=token.split("=",2);
                if(pair.length!=2) throw new IOException("Invalid expected count in "+n+": "+token);
                int count;
                try{count=Integer.parseInt(pair[1]);}catch(NumberFormatException e){throw new IOException("Invalid expected count in "+n+": "+token,e);}
                if(count<1 || counts.put(pair[0],count)!=null) throw new IOException("Invalid/duplicate expected count in "+n+": "+token);
            }
            if(hashes.isEmpty() || kinds.isEmpty() || counts.isEmpty()) throw new IOException("Empty target guard in "+n);
            Target target=new Target(cn,hashes,kinds,counts);
            if(r.targets.put(cn,target)!=null) throw new IOException("Duplicate target "+cn);
        }
        if(r.targets.isEmpty()) throw new IOException("No hyperspace targets registered");
        return r;
    }
    private static Set<String> tokens(String value){
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String token:value.split(",")){String trimmed=token.trim();if(!trimmed.isEmpty())result.add(trimmed);}
        return result;
    }
    Target get(String n){return targets.get(n);} Collection<Target> all(){return targets.values();}
}
