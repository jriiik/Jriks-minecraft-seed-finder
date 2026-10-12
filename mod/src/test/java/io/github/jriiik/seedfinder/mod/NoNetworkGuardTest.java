package io.github.jriiik.seedfinder.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.Set;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Fails the build if the mod jar (including the engine inside it) could send anything to a server, register chat
 * commands or patch the game. This is the project's promise to players and server owners, checked on every build.
 */
class NoNetworkGuardTest {

    private static Path modJar() {
        String path = System.getProperty("seedfinder.modJar");
        assertTrue(path != null && Files.isRegularFile(Path.of(path)), "mod jar not found: " + path);
        return Path.of(path);
    }

    @Test
    void theModJarSendsNothing() throws IOException {
        NoNetworkGuard.Result result;
        try (InputStream in = Files.newInputStream(modJar())) {
            result = NoNetworkGuard.scanJar(modJar().getFileName().toString(), in);
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
