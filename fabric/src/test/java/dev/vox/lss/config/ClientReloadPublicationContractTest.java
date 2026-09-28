package dev.vox.lss.config;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.Opcodes;
import static org.junit.jupiter.api.Assertions.*;

/** Invalid startup requires restart: reload cannot create/migrate files or publish a second handle. */
class ClientReloadPublicationContractTest {
    @Test void onlyStartupConstructsAndPublishesTheClientSettingsHandle() throws Exception {
        var node = new ClassNode();
        try (var input = getClass().getClassLoader().getResourceAsStream("dev/vox/lss/config/LSSClientConfig.class")) {
            assertNotNull(input);
            new ClassReader(input).accept(node, 0);
        }
        int constructions=0, publications=0;
        for (var method : node.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                if (call.owner.equals("dev/vox/lss/common/config/SettingsHandle") && call.name.equals("<init>")) {
                    constructions++;
                    assertEquals("<init>",method.name,"only process startup may initialize/migrate client settings");
                }
                assertFalse(call.owner.equals("dev/vox/lss/common/config/SettingsStore")
                        && (call.name.equals("read") || call.name.equals("initialize")),
                        "reload reads must go through the existing revisioned reload owner, never startup recovery");
            }
            if (instruction instanceof FieldInsnNode field && field.getOpcode()==Opcodes.PUTFIELD
                    && field.name.equals("handle") && field.owner.equals(node.name)) {
                publications++;
                assertEquals("<init>",method.name,"reload must not replace the stable handle");
            }
        }
        assertEquals(1,constructions);
        assertEquals(1,publications);
    }
}
