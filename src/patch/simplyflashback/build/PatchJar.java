package patch.simplyflashback.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

/** Small, fail-closed build-time overlay on one digest-pinned upstream release. */
public final class PatchJar {
    public static final String ADAPTER = "com/simplyran/simplyflashback/version/v26_3/V26_3Adapter";
    public static final String HANDLER = "com/simplyran/simplyflashback/capture/PacketCapture$1";
    public static final String PREFIX = "patch/simplyflashback/";
    public static final String PACKET = "net/minecraft/network/protocol/Packet";
    public static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
    public static final String CAPTURE_DESC = "(L" + PLAYER + ";L" + PACKET + ";)Ljava/util/List;";
    private static final String UPSTREAM_SHA = "83f56bf9d3860eb1e214bcce09fe63c873aca39191099a4ac43ae0566e37eb36";

    public static byte[] patchAdapter(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (!node.name.equals(ADAPTER)) throw new IllegalArgumentException("Unexpected adapter");
        if (node.methods.stream().anyMatch(m -> m.name.equals("inventoryPatch$encodeCapturedPacketOriginal")))
            throw new IllegalArgumentException("Adapter is already patched");
        int renamed = 0;
        for (MethodNode method : node.methods) {
            if (method.name.equals("encodeCapturedPacket") && method.desc.equals(CAPTURE_DESC)
                    && (method.access & (ACC_STATIC | ACC_PRIVATE)) == (ACC_STATIC | ACC_PRIVATE)) {
                method.name = "inventoryPatch$encodeCapturedPacketOriginal";
                renamed++;
            }
        }
        if (renamed != 1) throw new IllegalArgumentException("Expected exactly one capture encoder");

        // Compute frames for the NEW method in isolation. Preserve upstream methods'
        // frames without loading CraftBukkit or changing unrelated adapter bytecode.
        ClassWriter wrapper = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        wrapper.visit(V25, ACC_PUBLIC, ADAPTER, null, "java/lang/Object", null);
        MethodVisitor m = wrapper.visitMethod(ACC_PRIVATE | ACC_STATIC, "encodeCapturedPacket",
                CAPTURE_DESC, null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, PREFIX + "HotbarPackets", "normalize", CAPTURE_DESC, false);
        m.visitVarInsn(ASTORE, 2);
        Label normalized = new Label();
        m.visitVarInsn(ALOAD, 2);
        m.visitJumpInsn(IFNONNULL, normalized);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 1);
        m.visitMethodInsn(INVOKESTATIC, ADAPTER, "inventoryPatch$encodeCapturedPacketOriginal", CAPTURE_DESC, false);
        m.visitInsn(ARETURN);
        m.visitLabel(normalized);
        m.visitTypeInsn(NEW, "java/util/ArrayList");
        m.visitInsn(DUP);
        m.visitMethodInsn(INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        m.visitVarInsn(ASTORE, 3);
        m.visitVarInsn(ALOAD, 2);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "iterator", "()Ljava/util/Iterator;", true);
        m.visitVarInsn(ASTORE, 4);
        Label loop = new Label(), end = new Label();
        m.visitLabel(loop);
        m.visitVarInsn(ALOAD, 4);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
        m.visitJumpInsn(IFEQ, end);
        m.visitVarInsn(ALOAD, 3);
        m.visitVarInsn(ALOAD, 0);
        m.visitVarInsn(ALOAD, 4);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
        m.visitTypeInsn(CHECKCAST, PACKET);
        m.visitMethodInsn(INVOKESTATIC, ADAPTER, "encodeGamePacket", "(L"+PLAYER+";L"+PACKET+";)[B", false);
        m.visitMethodInsn(INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true);
        m.visitInsn(POP);
        m.visitJumpInsn(GOTO, loop);
        m.visitLabel(end);
        m.visitVarInsn(ALOAD, 3);
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        wrapper.visitEnd();
        ClassNode computed = new ClassNode();
        new ClassReader(wrapper.toByteArray()).accept(computed, 0);
        node.methods.add(computed.methods.getFirst());
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static byte[] patchCaptureFilter(byte[] input) {
        ClassReader reader = new ClassReader(input);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (!node.name.equals(HANDLER)) throw new IllegalArgumentException("Unexpected capture handler");
        int replaced = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == INVOKESTATIC
                        && call.owner.equals("com/simplyran/simplyflashback/capture/IgnoredPacketFilter")
                        && call.name.equals("shouldSkip") && call.desc.equals("(Ljava/lang/Object;)Z")) {
                    call.owner = PREFIX + "CaptureFilter";
                    replaced++;
                }
            }
        }
        if (replaced != 1) throw new IllegalArgumentException("Expected exactly one outer capture filter");
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected upstream, classes, output, version");
        Path input = Path.of(args[0]), classes = Path.of(args[1]), output = Path.of(args[2]);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)));
        if (!digest.equals(UPSTREAM_SHA)) throw new IllegalArgumentException("Upstream digest mismatch");
        Map<String, byte[]> entries = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(input))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                if (!entry.isDirectory()) {
                    if (entry.getName().matches("(?i)META-INF/.*\\.(SF|RSA|DSA)"))
                        throw new IllegalArgumentException("Signed upstream must not be patched silently");
                    if (entries.put(entry.getName(), zip.readAllBytes()) != null)
                        throw new IllegalArgumentException("Duplicate upstream ZIP entry");
                }
            }
        }
        entries.put(ADAPTER + ".class", patchAdapter(required(entries, ADAPTER + ".class")));
        entries.put(HANDLER + ".class", patchCaptureFilter(required(entries, HANDLER + ".class")));
        String descriptor = new String(required(entries, "plugin.yml"), StandardCharsets.UTF_8);
        if (!descriptor.contains("version: '0.1.1'")) throw new IllegalArgumentException("Unexpected upstream version");
        entries.put("plugin.yml", descriptor.replace("version: '0.1.1'", "version: '" + args[3] + "'").getBytes(StandardCharsets.UTF_8));
        try (var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String relative = classes.relativize(file).toString().replace('\\', '/');
                if (!relative.startsWith(PREFIX) || !relative.endsWith(".class"))
                    throw new IllegalArgumentException("Unexpected overlay file: " + relative);
                if (entries.putIfAbsent(relative, Files.readAllBytes(file)) != null)
                    throw new IllegalArgumentException("Overlay collides with upstream");
            }
        }
        entries.put("META-INF/inventory-patch/NOTICE", Files.readAllBytes(Path.of("NOTICE")));
        entries.put("META-INF/inventory-patch/LICENSE", Files.readAllBytes(Path.of("LICENSE")));
        Files.createDirectories(output.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            for (var entry : entries.entrySet()) {
                ZipEntry target = new ZipEntry(entry.getKey());
                target.setTime(0);
                zip.putNextEntry(target);
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        System.out.println("Built " + output + " from verified upstream " + digest);
    }

    private static byte[] required(Map<String, byte[]> entries, String name) throws IOException {
        byte[] value = entries.get(name);
        if (value == null) throw new IOException("Missing upstream entry: " + name);
        return value;
    }
}
