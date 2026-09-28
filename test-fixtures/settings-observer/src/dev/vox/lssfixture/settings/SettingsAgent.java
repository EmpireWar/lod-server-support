package dev.vox.lssfixture.settings;

import java.lang.instrument.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.objectweb.asm.*;

/** External observation only: callbacks run at the actual product method boundary. */
public final class SettingsAgent {
    public static void premain(String ignored, Instrumentation instrumentation) throws Exception {
        instrumentation.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(
                System.getProperty("lss.rig.settingsObserver")));
        SettingsRecorder.start();
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    java.security.ProtectionDomain domain, byte[] bytes) {
                boolean service = name.equals("dev/vox/lss/networking/server/RequestProcessingService")
                        || name.equals("dev/vox/lss/paper/PaperRequestProcessingService");
                boolean generation = name.equals("dev/vox/lss/networking/server/ChunkGenerationService")
                        || name.equals("dev/vox/lss/paper/PaperChunkGenerationService");
                if (!service && !generation) return null;
                try {
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    String expected = System.getProperty("lss.rig.settings." + (service ? "service" : "generation") + "Sha256");
                    if (!sha.equals(expected)) throw new IllegalStateException("unexpected candidate bytecode");
                    ClassReader reader = new ClassReader(bytes);
                    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    int[] found = {0};
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        public MethodVisitor visitMethod(int access, String method, String desc, String sig, String[] exceptions) {
                            MethodVisitor base = super.visitMethod(access,method,desc,sig,exceptions);
                            String callback = service && method.equals("tick") && desc.equals("()V") ? "sample"
                                    : generation && method.equals("updatePolicy") && desc.equals("(ZIIIJ)V") ? "policy" : null;
                            if (callback == null) return base;
                            found[0]++;
                            return new MethodVisitor(Opcodes.ASM9,base) {
                                public void visitInsn(int opcode) {
                                    if (opcode == Opcodes.RETURN) {
                                        mv.visitVarInsn(Opcodes.ALOAD,0);
                                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,"dev/vox/lssfixture/settings/SettingsRecorder",callback,"(Ljava/lang/Object;)V",false);
                                    }
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                    },0);
                    if (found[0] != 1) throw new IllegalStateException("exact method descriptor absent");
                    SettingsRecorder.applied(service ? "service" : "generation",sha);
                    return writer.toByteArray();
                } catch (Exception failure) { SettingsRecorder.failure("transform_" + name.replace('/','_')); return null; }
            }
        });
    }
}
