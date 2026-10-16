package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fails the build if the mod jar (including the engine inside it) could send anything to a server, register chat
 * commands or patch the game. This is the project's promise to players and server owners, checked on every build, for
 * the jar that ships for each Minecraft version.
 */
class NoNetworkGuardTest {

    /** The jar that ships for the Minecraft version this project builds. */
    static Path modJar() {
        String path = System.getProperty("seedfinder.modJar");
        assertTrue(path != null && Files.isRegularFile(Path.of(path)), "mod jar not found: " + path);
        return Path.of(path);
    }

    /** The mappings the jar was remapped with (obfuscated Minecraft versions, 1.21.11 and older), or none. */
    private static NoNetworkGuard.Names names() throws IOException {
        String path = System.getProperty("seedfinder.mappings");
        return path == null ? NoNetworkGuard.Names.NONE : NoNetworkGuard.Names.read(Path.of(path));
    }

    @Test
    void theModJarSendsNothing() throws IOException {
        NoNetworkGuard.Result result;
        try (InputStream in = Files.newInputStream(modJar())) {
            result = NoNetworkGuard.scanJar(modJar().getFileName().toString(), in, names());
        }
        assertTrue(result.classes() > 20, "expected the mod and engine classes, got " + result.classes());
        assertEquals(List.of(), result.problems());
    }

    @Test
    void theModIsClientOnlyWithoutMixins() throws IOException {
        try (ZipFile zip = new ZipFile(modJar().toFile())) {
            JsonObject modJson = JsonParser.parseString(new String(zip.getInputStream(zip.getEntry("fabric.mod.json")).readAllBytes(),
                StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("client", modJson.get("environment").getAsString());
            assertFalse(modJson.has("mixins"), "the mod must not change the game's code");
            assertEquals(Set.of("client"), modJson.getAsJsonObject("entrypoints").keySet());
            assertTrue(zip.stream().anyMatch(e -> e.getName().startsWith("META-INF/jars/engine") && e.getName().endsWith(".jar")),
                "the engine jar is inside the mod jar");
        }
    }

    /** The guard must catch what it is there for: a class that sends a chat command. */
    @Test
    void theGuardCatchesACommandSender() {
        ClassDesc minecraft = ClassDesc.ofInternalName("net/minecraft/client/Minecraft");
        ClassDesc listener = ClassDesc.ofInternalName("net/minecraft/client/multiplayer/ClientPacketListener");
        byte[] bad = ClassFile.of().build(ClassDesc.of("bad.BadMod"), type -> type.withMethodBody("run",
            MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, code -> code
                .invokestatic(minecraft, "getInstance", MethodTypeDesc.of(minecraft))
                .invokevirtual(minecraft, "getConnection", MethodTypeDesc.of(listener))
                .ldc("locate structure minecraft:village_plains")
                .invokevirtual(listener, "sendCommand", MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                .return_()));
        List<String> problems = NoNetworkGuard.scanClass("bad/BadMod.class", bad);
        assertTrue(problems.stream().anyMatch(p -> p.contains("getConnection")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("sendCommand")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ClientPacketListener")), problems.toString());
    }

    /** Mappings in Loom's format with what the tests below need, as the real ones have them. */
    private static NoNetworkGuard.Names someMappings(Path folder) throws IOException {
        Path tiny = folder.resolve("mappings.tiny");
        Files.writeString(tiny, String.join("\n",
            "tiny\t2\t0\tintermediary\tnamed",
            "c\tnet/minecraft/class_310\tnet/minecraft/client/Minecraft",
            "\tm\t()Lnet/minecraft/class_310;\tmethod_1551\tgetInstance",
            "\tm\t()Lnet/minecraft/class_634;\tmethod_1562\tgetConnection",
            "c\tnet/minecraft/class_634\tnet/minecraft/client/multiplayer/ClientPacketListener",
            "\tm\t(Ljava/lang/String;)V\tmethod_45730\tsendCommand",
            "c\tnet/minecraft/class_2797\tnet/minecraft/network/protocol/game/ServerboundChatCommandPacket",
            ""), StandardCharsets.UTF_8);
        return NoNetworkGuard.Names.read(tiny);
    }

    /** Mappings with an empty Minecraft name are refused: such a name could hide what a class or member stands for. */
    @Test
    void mappingsWithAnEmptyMinecraftNameAreRefused(@TempDir Path folder) throws IOException {
        Path tiny = folder.resolve("mappings.tiny");
        for (String lines : List.of(
            "c\tnet/minecraft/class_634\t",
            "c\tnet/minecraft/class_310\tnet/minecraft/client/Minecraft\n\tm\t()Lnet/minecraft/class_634;\tmethod_1562\t")) {
            Files.writeString(tiny, "tiny\t2\t0\tintermediary\tnamed\n" + lines + "\n", StandardCharsets.UTF_8);
            IOException refused = assertThrows(IOException.class, () -> NoNetworkGuard.Names.read(tiny), lines);
            assertTrue(refused.getMessage().contains("has no Minecraft (named) name"), refused.getMessage());
        }
    }

    /** A remapped jar's command sender reads class_310.method_1562 and so on; the guard must see through that. */
    @Test
    void theGuardSeesThroughIntermediaryNames(@TempDir Path folder) throws IOException {
        assertCatchesIntermediaryCommandSender(someMappings(folder));
        ClassDesc packet = ClassDesc.ofInternalName("net/minecraft/class_2797");
        byte[] bad = ClassFile.of().build(ClassDesc.of("bad.PacketField"), type -> type.withField("packet", packet, ClassFile.ACC_STATIC));
        List<String> problems = NoNetworkGuard.scanClass("bad/PacketField.class", bad, someMappings(folder));
        assertTrue(problems.stream().anyMatch(p -> p.contains("ServerboundChatCommandPacket")), problems.toString());
    }

    /** The same with the mappings the jar that ships was remapped with (only for obfuscated Minecraft versions). */
    @Test
    void theGuardSeesThroughTheRealIntermediaryNames() throws IOException {
        NoNetworkGuard.Names names = names();
        assumeTrue(names.remapped(), "this version's jar uses Minecraft's own names");
        assertTrue(names.classes().size() > 1000 && names.members().size() > 10000, "mappings read: " + names.classes().size() + " classes");
        assertCatchesIntermediaryCommandSender(names);
    }

    private static void assertCatchesIntermediaryCommandSender(NoNetworkGuard.Names names) {
        ClassDesc minecraft = ClassDesc.ofInternalName(intermediary(names.classes(), "net/minecraft/client/Minecraft"));
        ClassDesc listener = ClassDesc.ofInternalName(intermediary(names.classes(), "net/minecraft/client/multiplayer/ClientPacketListener"));
        byte[] bad = ClassFile.of().build(ClassDesc.of("bad.BadMod"), type -> type.withMethodBody("run",
            MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, code -> code
                .invokestatic(minecraft, intermediaryMember(names, "getInstance"), MethodTypeDesc.of(minecraft))
                .invokevirtual(minecraft, intermediaryMember(names, "getConnection"), MethodTypeDesc.of(listener))
                .ldc("locate structure minecraft:village_plains")
                .invokevirtual(listener, intermediaryMember(names, "sendCommand"), MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String))
                .return_()));
        assertTrue(NoNetworkGuard.scanClass("bad/BadMod.class", bad).stream().noneMatch(p -> p.contains("sendCommand")),
            "without the mappings the names say nothing");
        List<String> problems = NoNetworkGuard.scanClass("bad/BadMod.class", bad, names);
        assertTrue(problems.stream().anyMatch(p -> p.contains("getConnection")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("sendCommand")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.contains("ClientPacketListener")), problems.toString());
    }

    private static String intermediary(Map<String, String> names, String named) {
        return names.entrySet().stream().filter(entry -> entry.getValue().equals(named)).map(Map.Entry::getKey).findFirst().orElseThrow();
    }

    private static String intermediaryMember(NoNetworkGuard.Names names, String named) {
        return names.members().entrySet().stream().filter(entry -> entry.getValue().contains(named)).map(Map.Entry::getKey)
            .filter(NoNetworkGuard.INTERMEDIARY_MEMBER.asMatchPredicate()).findFirst().orElseThrow();
    }

    /**
     * An intermediary name the guard can't turn back is a problem: without mappings (a remapped jar scanned as if it
     * had Minecraft's own names) and with mappings that don't have it.
     */
    @Test
    void theGuardNeedsToKnowEveryIntermediaryName(@TempDir Path folder) throws IOException {
        ClassDesc minecraft = ClassDesc.ofInternalName("net/minecraft/class_310");
        byte[] unknownMember = ClassFile.of().build(ClassDesc.of("bad.Unknown"), type -> type.withMethodBody("run",
            MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_STATIC, code -> code
                .invokestatic(minecraft, "method_99999", MethodTypeDesc.of(ConstantDescs.CD_void))
                .return_()));
        assertTrue(NoNetworkGuard.scanClass("bad/Unknown.class", unknownMember).stream().anyMatch(p -> p.contains("no mappings given")));
        List<String> problems = NoNetworkGuard.scanClass("bad/Unknown.class", unknownMember, someMappings(folder));
        assertTrue(problems.stream().anyMatch(p -> p.contains("method_99999") && p.contains("the mappings don't have")), problems.toString());
        byte[] unknownClass = ClassFile.of().build(ClassDesc.of("bad.UnknownClass"), type -> type.withField("x",
            ClassDesc.ofInternalName("net/minecraft/class_99999"), ClassFile.ACC_STATIC));
        problems = NoNetworkGuard.scanClass("bad/UnknownClass.class", unknownClass, someMappings(folder));
        assertTrue(problems.stream().anyMatch(p -> p.contains("class_99999") && p.contains("the mappings don't have")), problems.toString());
    }

    /** A mixin that only uses annotations has no class reference to Mixin; the text scan must still see it. */
    @Test
    void theGuardCatchesAnnotationOnlyMixins() {
        byte[] mixin = ClassFile.of().build(ClassDesc.of("bad.SneakyMixin"), type -> type.with(
            RuntimeInvisibleAnnotationsAttribute.of(Annotation.of(ClassDesc.ofInternalName("org/spongepowered/asm/mixin/Mixin")))));
        List<String> problems = NoNetworkGuard.scanClass("bad/SneakyMixin.class", mixin);
        assertTrue(problems.stream().anyMatch(p -> p.contains("org/spongepowered/")), problems.toString());
    }

    @Test
    void theGuardCatchesServerboundPacketsInDescriptors() {
        ClassDesc packet = ClassDesc.ofInternalName("net/minecraft/network/protocol/game/ServerboundChatCommandPacket");
        byte[] bad = ClassFile.of().build(ClassDesc.of("bad.PacketField"), type -> type.withField("packet", packet, ClassFile.ACC_STATIC));
        List<String> problems = NoNetworkGuard.scanClass("bad/PacketField.class", bad);
        assertTrue(problems.stream().anyMatch(p -> p.contains("ServerboundChatCommandPacket")), problems.toString());
    }
}
