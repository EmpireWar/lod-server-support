package dev.vox.lssfixture.settings;

import java.lang.instrument.*;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.jar.JarFile;
import java.nio.file.Path;
import java.util.HexFormat;
import org.objectweb.asm.*;

/** External observation only: callbacks run at the actual product method boundary. */
public final class SettingsAgent {
    static String checksum(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    static void requireSourceAndProcessed(byte[] source, byte[] actual, byte[] processed, String expected) throws Exception {
        if (!checksum(source).equals(expected)) throw new IllegalStateException("source_candidate_mismatch");
        if (!Arrays.equals(actual,processed)) throw new IllegalStateException("platform_transform_mismatch");
    }
    static String validateCandidate(ClassLoader loader, String name, java.security.ProtectionDomain domain,
            byte[] actual, String expected) throws Exception {
        if (checksum(actual).equals(expected)) return "exact";
        if (!name.startsWith("dev/vox/lss/paper/")
                || !loader.getClass().getName().equals("org.bukkit.plugin.java.PluginClassLoader"))
            throw new IllegalStateException("unexpected_candidate_bytecode");
        // Bukkit's real plugin loader runs UnsafeValues.processClass before defineClass.
        // Bind its actual description and source jar, then reproduce that exact transform;
        // neither an arbitrary replacement class nor a different plugin is accepted.
        Object plugin=loader.getClass().getMethod("getPlugin").invoke(loader);
        var location=domain.getCodeSource().getLocation();
        if (plugin==null || plugin.getClass().getClassLoader()!=loader
                || !location.equals(plugin.getClass().getProtectionDomain().getCodeSource().getLocation())
                || !location.getProtocol().equals("file"))
            throw new IllegalStateException("plugin_source_identity_mismatch");
        byte[] source;
        try (JarFile jar=new JarFile(Path.of(location.toURI()).toFile())) {
            var entry=jar.getJarEntry(name+".class");
            if (entry==null || entry.getSize()>2*1024*1024) throw new IllegalStateException("source_class_budget");
            try(var input=jar.getInputStream(entry)){source=input.readNBytes(2*1024*1024+1);}
            if(source.length>2*1024*1024) throw new IllegalStateException("source_class_budget");
        }
        if (!checksum(source).equals(expected)) throw new IllegalStateException("source_candidate_mismatch");
        Class<?> javaPlugin=Class.forName("org.bukkit.plugin.java.JavaPlugin",false,loader);
        Object description=javaPlugin.getMethod("getDescription").invoke(plugin);
        Class<?> bukkit=Class.forName("org.bukkit.Bukkit",false,loader);
        Object unsafe=bukkit.getMethod("getUnsafe").invoke(null);
        Class<?> unsafeType=Class.forName("org.bukkit.UnsafeValues",false,loader);
        byte[] processed=(byte[])unsafeType.getMethod("processClass",description.getClass(),String.class,byte[].class)
                .invoke(unsafe,description,name+".class",source);
        requireSourceAndProcessed(source,actual,processed,expected);
        return "bukkit_process_class";
    }
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
                String kind=service ? "service" : "generation";
                String expected=System.getProperty("lss.rig.settings."+kind+"Sha256");
                String sha="unavailable";
                try {
                    sha=checksum(bytes);
                    String validation=validateCandidate(loader,name,domain,bytes,expected);
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
                    SettingsRecorder.applied(kind,expected,sha,validation);
                    return writer.toByteArray();
                } catch (Exception failure) {
                    SettingsRecorder.transformFailure(kind,expected,sha,failure.getClass().getSimpleName()+":"+failure.getMessage());
                    return null;
                }
            }
        });
    }
}
