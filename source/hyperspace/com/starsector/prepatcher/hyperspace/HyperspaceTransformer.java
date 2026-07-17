
package com.starsector.prepatcher.hyperspace;

import java.lang.instrument.*;
import java.security.*;
import java.util.*;
import jdk.internal.org.objectweb.asm.*;
import jdk.internal.org.objectweb.asm.tree.*;

final class HyperspaceTransformer implements ClassFileTransformer {
    static final String HOOK="com/starsector/prepatcher/hyperspace/HyperspaceHooks";
    static final String AUTO_SPARE="smo$automatonSpare";
    static final String AUTO_EXPOSED="smo$automatonCellsExposed";
    static final String AUTO_MARKER="smo$automatonBufferInstrumented";
    final AgentConfig cfg; final TargetRegistry registry;
    HyperspaceTransformer(AgentConfig c,TargetRegistry r){cfg=c;registry=r;}
    @Override public byte[] transform(Module module,ClassLoader loader,String name,Class<?> redef,ProtectionDomain pd,byte[] bytes){return transformBytes(name,bytes);}
    byte[] transformBytes(String name,byte[] bytes){
        TargetRegistry.Target t=registry.get(name); if(t==null)return null;
        String actual=Hex.sha256(bytes); if(!t.acceptsHash(actual)){guardFailure(name,"hash-mismatch");AgentLog.info("SKIP hash mismatch "+name+" expectedOneOf="+t.hashes+" actual="+actual);return null;}
        try{
            ClassNode cn=new ClassNode(Opcodes.ASM8);new ClassReader(bytes).accept(cn,0);int changed=0;LinkedHashMap<String,Integer> counts=new LinkedHashMap<>();
            if(t.kinds.contains("BASE_TILED")){
                if(cfg.culling){int n=patchCull(cn);changed+=n;counts.put("culling",n);}
                if(cfg.cullingClamp){int n=patchClamp(cn);changed+=n;counts.put("yClamp",n);}
                if(cfg.randomReuse){int n=patchRandom(cn);changed+=n;counts.put("random",n);}
            }
            if(t.kinds.contains("HYPER_TERRAIN")){
                if(cfg.noOpLayer){int n=patchLayers(cn);changed+=n;counts.put("noOpLayer",n);}
                if(cfg.randomReuse){int n=patchRandom(cn);changed+=n;counts.put("random",n);}
                if(cfg.automatonBuffer){int n=patchAutomatonInternalReads(cn);changed+=n;counts.put("internalCells",n);}
            }
            if(t.kinds.contains("AUTOMATON") && cfg.automatonBuffer){int n=patchAutomaton(cn);changed+=n;counts.put("automatonBuffers",n);}
            if(t.kinds.contains("CLEANUP") && cfg.cleanupBuffers){int n=patchCleanup(cn);changed+=n;counts.put("cleanup",n);}
            if(!t.acceptsCounts(counts)){guardFailure(name,"patch-count-mismatch");AgentLog.info("SKIP exact patch-count mismatch "+name+" expected="+t.expectedCounts+" actual="+counts);return null;}
            if(changed==0){AgentLog.info("No applicable patterns in "+name+" kinds="+t.kinds);return null;}
            ClassWriter cw=new ClassWriter(ClassWriter.COMPUTE_MAXS);cn.accept(cw);byte[] out=cw.toByteArray();
            AgentLog.info("Patched "+name+" "+counts);return out;
        }catch(Throwable x){guardFailure(name,"patch-error");AgentLog.error("Patch failed, vanilla retained for "+name,x);return null;}
    }
    static void guardFailure(String name,String reason){
        System.setProperty("starsector.prepatcher.hyperspaceStatus","target-guard-failed");
        System.setProperty("starsector.prepatcher.hyperspaceGuardFailure",name+":"+reason);
    }
    static AbstractInsnNode nextReal(AbstractInsnNode n){for(n=n.getNext();n!=null && (n instanceof LabelNode||n instanceof LineNumberNode||n instanceof FrameNode);n=n.getNext());return n;}
    static AbstractInsnNode prevReal(AbstractInsnNode n){for(n=n.getPrevious();n!=null && (n instanceof LabelNode||n instanceof LineNumberNode||n instanceof FrameNode);n=n.getPrevious());return n;}
    /**
     * Fix the decompiled-source-equivalent yEnd expression which loads viewport
     * width for the vertical range. The base implementation is patched directly,
     * so every subclass inherits the corrected behavior as requested.
     */
    static int patchCull(ClassNode cn){
        int total=0;
        for(MethodNode m:cn.methods){
            int w=-1,h=-1; AbstractInsnNode afterHeight=null;
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext()){
                if(!(n instanceof MethodInsnNode q)) continue;
                if(q.name.equals("getVisibleWidth") && q.desc.equals("()F")){
                    AbstractInsnNode store=nextReal(q);
                    if(store instanceof VarInsnNode v && v.getOpcode()==Opcodes.FSTORE) w=v.var;
                } else if(q.name.equals("getVisibleHeight") && q.desc.equals("()F")){
                    AbstractInsnNode store=nextReal(q);
                    if(store instanceof VarInsnNode v && v.getOpcode()==Opcodes.FSTORE){h=v.var;afterHeight=store;}
                }
            }
            if(w<0 || h<0 || afterHeight==null) continue;
            ArrayList<VarInsnNode> widthLoads=new ArrayList<>();
            for(AbstractInsnNode n=afterHeight.getNext();n!=null;n=n.getNext())
                if(n instanceof VarInsnNode v && v.getOpcode()==Opcodes.FLOAD && v.var==w) widthLoads.add(v);
            // In BaseTiledTerrain.render()/isTileVisible(): viewport intersection,
            // xEnd, then the erroneous yEnd. Refuse fuzzy matches.
            if(widthLoads.size()!=3) continue;
            widthLoads.get(2).var=h;
            total++;
        }
        return total;
    }

    /**
     * Fix only the second end-index comparison in viewport methods:
     * yEnd must be compared with tiles[0].length, not tiles.length.
     * The replacement is direct bytecode (AALOAD/ARRAYLENGTH), with no
     * per-frame reflection/helper overhead.
     */
    static int patchClamp(ClassNode cn){
        int total=0;
        for(MethodNode m:cn.methods){
            boolean viewportMethod=false;
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext()){
                if(n instanceof MethodInsnNode q && q.name.equals("getVisibleHeight") && q.desc.equals("()F")){
                    viewportMethod=true; break;
                }
            }
            if(!viewportMethod) continue;
            ArrayList<InsnNode> endComparisons=new ArrayList<>();
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext()){
                if(!(n instanceof InsnNode a) || a.getOpcode()!=Opcodes.ARRAYLENGTH) continue;
                AbstractInsnNode p=prevReal(a);
                AbstractInsnNode n1=nextReal(a);
                AbstractInsnNode n2=n1==null?null:nextReal(n1);
                AbstractInsnNode n3=n2==null?null:nextReal(n2);
                if(!(p instanceof FieldInsnNode f) || f.getOpcode()!=Opcodes.GETFIELD || !f.desc.startsWith("[[")) continue;
                if(n1==null || n1.getOpcode()!=Opcodes.I2F || n2==null || n2.getOpcode()!=Opcodes.FCMPL || !(n3 instanceof JumpInsnNode)) continue;
                endComparisons.add(a);
            }
            // xEnd comparison first, yEnd comparison second. Refuse fuzzy matches.
            if(endComparisons.size()!=2) continue;
            InsnNode victim=endComparisons.get(1);
            InsnList replacement=new InsnList();
            replacement.add(new InsnNode(Opcodes.ICONST_0));
            replacement.add(new InsnNode(Opcodes.AALOAD));
            replacement.add(new InsnNode(Opcodes.ARRAYLENGTH));
            m.instructions.insertBefore(victim,replacement);
            m.instructions.remove(victim);
            total++;
        }
        return total;
    }
    static int patchLayers(ClassNode cn){
        int total=0;
        for(MethodNode m:cn.methods){if(!m.name.equals("getActiveLayers"))continue;boolean enumSet=m.desc.endsWith(")Ljava/util/EnumSet;");boolean set=m.desc.endsWith(")Ljava/util/Set;");if(!enumSet&&!set)continue;
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext())if(n.getOpcode()==Opcodes.ARETURN){InsnList x=new InsnList();x.add(new VarInsnNode(Opcodes.ALOAD,0));x.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,enumSet?"filterLayers":"filterLayerSet",enumSet?"(Ljava/util/EnumSet;Ljava/lang/Object;)Ljava/util/EnumSet;":"(Ljava/util/Set;Ljava/lang/Object;)Ljava/util/Set;",false));m.instructions.insertBefore(n,x);total++;}
        }
        return total;
    }
    /**
     * HyperspaceTerrainPlugin is the engine-internal consumer of the automaton
     * array. Redirect its two getCells() calls to a direct field hook so they do
     * not mark the public array as escaped. Public/mod calls still go through
     * getCells() and therefore retain vanilla alias safety.
     */
    static int patchAutomatonInternalReads(ClassNode cn){
        final String owner="com/fs/starfarer/api/impl/campaign/terrain/HyperspaceAutomaton";
        int total=0;
        for(MethodNode m:cn.methods){
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext()){
                if(!(n instanceof MethodInsnNode call)
                        || call.getOpcode()!=Opcodes.INVOKEVIRTUAL
                        || !call.owner.equals(owner) || !call.name.equals("getCells")
                        || !call.desc.equals("()[[I")) continue;
                m.instructions.insertBefore(call,new LdcInsnNode(owner.replace('/','.')));
                m.instructions.set(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,
                        "automatonCellsInternal","(Ljava/lang/Object;Ljava/lang/String;)[[I",false));
                total++;
            }
        }
        return total;
    }

    static int patchRandom(ClassNode cn){
        int total=0;
        for(MethodNode m:cn.methods){
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;){AbstractInsnNode next=n.getNext();
                if(n instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals("java/util/Random")){
                    AbstractInsnNode dup=nextReal(n);if(dup!=null&&dup.getOpcode()==Opcodes.DUP){
                        for(AbstractInsnNode q=dup.getNext();q!=null;q=q.getNext()){
                            if(q instanceof MethodInsnNode mi&&mi.getOpcode()==Opcodes.INVOKESPECIAL&&mi.owner.equals("java/util/Random")&&mi.name.equals("<init>")){
                                if(mi.desc.equals("(J)V")){m.instructions.remove(n);m.instructions.remove(dup);m.instructions.set(mi,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"seededRandom","(J)Ljava/util/Random;",false));total++;}
                                break;
                            }
                            if(q instanceof TypeInsnNode z&&z.getOpcode()==Opcodes.NEW)break;
                        }
                    }
                }
                if(n.getPrevious()==null && n.getNext()==null && next!=null) { n=nextReal(next); } else { n=next; }
            }
        }
        return total;
    }
    static int patchAutomaton(ClassNode cn){
        for(FieldNode f:cn.fields)if(f.name.equals(AUTO_SPARE)||f.name.equals(AUTO_EXPOSED)||f.name.equals(AUTO_MARKER))return 0;
        boolean cells=false,next=false;
        for(FieldNode f:cn.fields){if(f.name.equals("cells")&&f.desc.equals("[[I"))cells=true;if(f.name.equals("next")&&f.desc.equals("[[I"))next=true;}
        if(!cells||!next)return 0;

        MethodNode getter=null,advance=null;
        for(MethodNode m:cn.methods){
            if(m.name.equals("getCells")&&m.desc.equals("()[[I"))getter=m;
            else if(m.name.equals("advance")&&m.desc.equals("(F)V"))advance=m;
        }
        if(getter==null||advance==null)return 0;

        ArrayList<InsnNode> returns=new ArrayList<>();
        for(AbstractInsnNode n=getter.instructions.getFirst();n!=null;n=n.getNext())if(n.getOpcode()==Opcodes.ARETURN)returns.add((InsnNode)n);
        ArrayList<FieldInsnNode> swaps=new ArrayList<>(),nextStores=new ArrayList<>();
        ArrayList<MultiANewArrayInsnNode> allocations=new ArrayList<>();
        for(AbstractInsnNode n=advance.instructions.getFirst();n!=null;n=n.getNext()){
            if(n instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.owner.equals(cn.name)){
                if(f.name.equals("cells")&&f.desc.equals("[[I"))swaps.add(f);
                else if(f.name.equals("next")&&f.desc.equals("[[I"))nextStores.add(f);
            }else if(n instanceof MultiANewArrayInsnNode a&&a.desc.equals("[[I")&&a.dims==2)allocations.add(a);
        }
        if(returns.size()!=1||swaps.size()!=1||nextStores.size()!=1||allocations.size()!=1)return 0;
        FieldInsnNode swap=swaps.get(0),nextStore=nextStores.get(0);MultiANewArrayInsnNode allocation=allocations.get(0);
        if(!isBefore(swap,allocation)||!isBefore(allocation,nextStore))return 0;

        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_TRANSIENT|Opcodes.ACC_SYNTHETIC,AUTO_SPARE,"[[I",null,null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_TRANSIENT|Opcodes.ACC_SYNTHETIC,AUTO_EXPOSED,"Z",null,null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC|Opcodes.ACC_FINAL|Opcodes.ACC_SYNTHETIC,AUTO_MARKER,"Z",null,1));

        InsnList escaped=new InsnList();
        escaped.add(new VarInsnNode(Opcodes.ALOAD,0));escaped.add(new InsnNode(Opcodes.ICONST_1));
        escaped.add(new FieldInsnNode(Opcodes.PUTFIELD,cn.name,AUTO_EXPOSED,"Z"));
        getter.instructions.insertBefore(returns.get(0),escaped);

        InsnList stash=new InsnList();
        stash.add(new VarInsnNode(Opcodes.ALOAD,0));stash.add(new VarInsnNode(Opcodes.ALOAD,0));
        stash.add(new FieldInsnNode(Opcodes.GETFIELD,cn.name,"cells","[[I"));stash.add(new VarInsnNode(Opcodes.ALOAD,0));
        stash.add(new FieldInsnNode(Opcodes.GETFIELD,cn.name,AUTO_EXPOSED,"Z"));
        stash.add(new VarInsnNode(Opcodes.ALOAD,0));stash.add(new LdcInsnNode(cn.name.replace('/','.')));
        stash.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"retainAutomatonSpare","([[IZLjava/lang/Object;Ljava/lang/String;)[[I",false));
        stash.add(new FieldInsnNode(Opcodes.PUTFIELD,cn.name,AUTO_SPARE,"[[I"));
        advance.instructions.insertBefore(swap,stash);
        InsnList unexposed=new InsnList();
        unexposed.add(new VarInsnNode(Opcodes.ALOAD,0));unexposed.add(new InsnNode(Opcodes.ICONST_0));
        unexposed.add(new FieldInsnNode(Opcodes.PUTFIELD,cn.name,AUTO_EXPOSED,"Z"));
        advance.instructions.insert(swap,unexposed);

        InsnList spareArg=new InsnList();spareArg.add(new VarInsnNode(Opcodes.ALOAD,0));spareArg.add(new FieldInsnNode(Opcodes.GETFIELD,cn.name,AUTO_SPARE,"[[I"));
        advance.instructions.insertBefore(allocation,spareArg);
        advance.instructions.set(allocation,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"acquireAutomatonBuffer","(II[[I)[[I",false));
        InsnList clearSpare=new InsnList();clearSpare.add(new VarInsnNode(Opcodes.ALOAD,0));clearSpare.add(new InsnNode(Opcodes.ACONST_NULL));clearSpare.add(new FieldInsnNode(Opcodes.PUTFIELD,cn.name,AUTO_SPARE,"[[I"));
        advance.instructions.insert(nextStore,clearSpare);
        return 1;
    }
    static boolean isBefore(AbstractInsnNode first,AbstractInsnNode second){for(AbstractInsnNode n=first;n!=null;n=n.getNext())if(n==second)return true;return false;}
    static int patchCleanup(ClassNode cn){
        int total=0;
        for(MethodNode m:cn.methods){
            ArrayList<Candidate> cs=new ArrayList<>();
            for(AbstractInsnNode n=m.instructions.getFirst();n!=null;n=n.getNext()) if(n instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&t.desc.equals("java/util/ArrayList")){
                AbstractInsnNode dup=nextReal(n);if(dup==null||dup.getOpcode()!=Opcodes.DUP)continue;AbstractInsnNode init=nextReal(dup);if(!(init instanceof MethodInsnNode mi)||mi.getOpcode()!=Opcodes.INVOKESPECIAL||!mi.owner.equals("java/util/ArrayList")||!mi.name.equals("<init>")||!mi.desc.equals("()V"))continue;AbstractInsnNode store=nextReal(init);if(store instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ASTORE)cs.add(new Candidate(t,(InsnNode)dup,mi,v.var));
            }
            for(Candidate c:cs){MethodInsnNode remove=null;boolean bad=false;for(AbstractInsnNode n=c.init.getNext();n!=null;n=n.getNext()){
                    if(n instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==c.slot){AbstractInsnNode nx=nextReal(n);if(nx instanceof MethodInsnNode mi){String key=mi.owner+"."+mi.name+mi.desc;if(mi.name.equals("removeAll")&&mi.desc.equals("(Ljava/util/Collection;)Z")){remove=mi;}else if(!(mi.name.equals("add")||mi.name.equals("isEmpty")||mi.name.equals("size")||mi.name.equals("iterator"))){bad=true;break;}}}
                    if(remove!=null){for(AbstractInsnNode q=remove.getNext();q!=null;q=q.getNext())if(q instanceof VarInsnNode v&&v.getOpcode()==Opcodes.ALOAD&&v.var==c.slot){bad=true;break;}break;}
                }
                if(remove==null||bad)continue;
                m.instructions.remove(c.newNode);m.instructions.remove(c.dup);m.instructions.set(c.init,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"borrowArrayList","()Ljava/util/ArrayList;",false));
                m.instructions.set(remove,new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"removeAllAndRelease","(Ljava/util/Collection;Ljava/util/Collection;)Z",false));total++;
            }
        }
        return total;
    }
    static final class Candidate{final TypeInsnNode newNode;final InsnNode dup;final MethodInsnNode init;final int slot;Candidate(TypeInsnNode n,InsnNode d,MethodInsnNode i,int s){newNode=n;dup=d;init=i;slot=s;}}
    static final class Hex {static String sha256(byte[] b){try{java.security.MessageDigest d=java.security.MessageDigest.getInstance("SHA-256");StringBuilder s=new StringBuilder();for(byte x:d.digest(b))s.append(String.format("%02x",x));return s.toString();}catch(Exception e){throw new RuntimeException(e);}}}
}
