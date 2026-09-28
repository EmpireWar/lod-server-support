package dev.vox.lssfixture.settings;

import java.util.jar.JarFile;
import org.objectweb.asm.*;

/** Standalone build check against the exact packaged ASM and real candidate class files. */
public final class ObserverChecks {
    public static void main(String[] args) throws Exception {
        String prefix="dev/vox/lss/"+(args[1].equals("paper")?"paper/Paper":"networking/server/");
        try(JarFile jar=new JarFile(args[0])) {
            for(String type:new String[]{"RequestProcessingService","ChunkGenerationService"}) {
                byte[] source;
                try(var input=jar.getInputStream(jar.getJarEntry(prefix+type+".class"))){source=input.readAllBytes();}
                int[] methods={0};
                new ClassReader(source).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String signature,String[] exceptions) {
                        if(type.equals("RequestProcessingService") ? name.equals("tick")&&descriptor.equals("()V")
                                : name.equals("updatePolicy")&&descriptor.equals("(ZIIIJ)V")) methods[0]++;
                        return null;
                    }
                },0);
                if(methods[0]!=1)throw new AssertionError("exact observation method absent: "+type);
                String expected=SettingsAgent.checksum(source);
                SettingsAgent.requireSourceAndProcessed(source,source,source,expected);
                byte[] altered=source.clone();altered[altered.length-1]^=1;
                expectRejected(()->SettingsAgent.requireSourceAndProcessed(altered,source,source,expected),"source_candidate_mismatch");
                expectRejected(()->SettingsAgent.requireSourceAndProcessed(source,altered,source,expected),"platform_transform_mismatch");
            }
        }
        System.out.println("PACKAGED_ASM_REAL_TARGETS_AND_IDENTITY_NEGATIVE_CONTROLS_OK");
    }
    interface Checked {void run() throws Exception;}
    static void expectRejected(Checked action,String reason) throws Exception {
        try{action.run();throw new AssertionError("mutated class accepted");}
        catch(IllegalStateException expected){if(!reason.equals(expected.getMessage()))throw expected;}
    }
}
