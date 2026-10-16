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
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks on every build, for the jar that ships for each Minecraft version (with the engine inside it), the project's
 * promise to players and server owners that the mod sends nothing to a server and registers or runs no chat command.
 * The build fails when the jar refers to the game's connection, packets or command system, Fabric's networking, command
 * or message-sending APIs, mixins, or Java's networking, reflection, method handles, class loading, other programs or
 * native code; and when it uses a member of the game, Fabric or another library that is not on this version's reviewed
 * list ({@link #REVIEWED}), since ordinary game methods can send packets themselves. A new game call needs a person (or
 * a session) to check it in the game's code before it goes on the list.
 */
class NoNetworkGuardTest {

    /** The reviewed list of game, Fabric and library members for this project's Minecraft version (test resources). */
    static final String REVIEWED = "reviewed-game-members-" + System.getProperty("seedfinder.minecraftVersion") + ".txt";
    private static final String REVIEWED_PATH = "mod/src/test/resources/io/github/jriiik/seedfinder/mod/" + REVIEWED;

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

    /** The members on the reviewed list ({@code owner.name descriptor}; # starts a comment line). */
    static Set<String> reviewed() throws IOException {
        try (InputStream in = NoNetworkGuardTest.class.getResourceAsStream(REVIEWED)) {
            assertTrue(in != null, "missing " + REVIEWED_PATH);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).collect(Collectors.toSet());
        }
    }

    @Test
    void theModJarUsesOnlyReviewedGameMembers() throws IOException {
        NoNetworkGuard.Result result;
        try (InputStream in = Files.newInputStream(modJar())) {
            result = NoNetworkGuard.scanJar(modJar().getFileName().toString(), in, names());
        }
        assertTrue(result.libraryMembers().size() > 100, "expected the game members the mod uses, got " + result.libraryMembers().size());
        assertEquals(List.of(), NoNetworkGuard.unreviewed(result, reviewed(), REVIEWED_PATH));
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
            "c\tnet/minecraft/class_2828\tnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket",
            "c\tnet/minecraft/class_2828$class_2829\tnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket$Pos",
            "c\tnet/minecraft/server/MinecraftServer$class_6897\tnet/minecraft/server/MinecraftServer$ReloadableResources",
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

    /**
     * Nested intermediary names are turned back whole: inside an intermediary class ({@code class_2828$class_2829}) and
     * inside a class that kept its name ({@code MinecraftServer$class_6897}); one the mappings don't have is a problem.
     */
    @Test
    void theGuardTurnsNestedIntermediaryNamesBack(@TempDir Path folder) throws IOException {
        NoNetworkGuard.Names names = someMappings(folder);
        assertEquals("Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket$Pos;", names.text("Lnet/minecraft/class_2828$class_2829;"));
        assertEquals("(Lnet/minecraft/server/MinecraftServer$ReloadableResources;)V", names.text("(Lnet/minecraft/server/MinecraftServer$class_6897;)V"));
        List<String> problems = NoNetworkGuard.scanClass("bad/Nested.class", fieldOfType("net/minecraft/class_2828$class_2829"), names);
        assertTrue(problems.stream().anyMatch(p -> p.contains("mentions packet net/minecraft/network/protocol/game/ServerboundMovePlayerPacket$Pos")),
            problems.toString());
        for (String unknown : List.of("net/minecraft/class_310$class_99999", "net/minecraft/server/MinecraftServer$class_99999")) {
            problems = NoNetworkGuard.scanClass("bad/Unknown.class", fieldOfType(unknown), names);
            assertTrue(problems.stream().anyMatch(p -> p.contains("an intermediary class name the mappings don't have")), unknown + ": " + problems);
            problems = NoNetworkGuard.scanClass("bad/Unknown.class", fieldOfType(unknown));
            assertTrue(problems.stream().anyMatch(p -> p.contains("an intermediary class name (no mappings given)")), unknown + ": " + problems);
        }
    }

    /** Every class name of the real mappings is turned back whole (only for obfuscated Minecraft versions). */
    @Test
    void theGuardTurnsEveryRealIntermediaryClassNameBack() throws IOException {
        NoNetworkGuard.Names names = names();
        assumeTrue(names.remapped(), "this version's jar uses Minecraft's own names");
        List<String> wrong = names.classes().entrySet().stream()
            .filter(entry -> !names.text(entry.getKey()).equals(entry.getValue()) || NoNetworkGuard.INTERMEDIARY_CLASS.matcher(entry.getValue()).find())
            .map(entry -> entry.getKey() + " -> " + names.text(entry.getKey()) + " (mappings: " + entry.getValue() + ")").sorted().limit(20).toList();
        assertEquals(List.of(), wrong);
        assertTrue(names.classes().keySet().stream().anyMatch(name -> name.startsWith("net/minecraft/server/MinecraftServer$class_")),
            "the mappings have intermediary classes nested in a class that kept its name");
    }

    private static byte[] fieldOfType(String type) {
        return ClassFile.of().build(ClassDesc.of("bad.Field"), builder -> builder.withField("x", ClassDesc.ofInternalName(type), ClassFile.ACC_STATIC));
    }

    /** A class with one static method {@code run} whose code is {@code body} (then return). */
    private static byte[] probe(String name, Consumer<CodeBuilder> body) {
        return ClassFile.of().build(ClassDesc.ofInternalName(name), type -> type.withMethodBody("run", MethodTypeDesc.of(ConstantDescs.CD_void),
            ClassFile.ACC_STATIC, code -> {
                body.accept(code);
                code.return_();
            }));
    }

    private static MethodTypeDesc type(ClassDesc returns, ClassDesc... parameters) {
        return MethodTypeDesc.of(returns, parameters);
    }

    /**
     * The ways around rules by name: Java's networking, reflection (members named in text), method handles, loading a
     * class by its name in text, other programs, native code, and the command system itself.
     */
    @Test
    void theGuardCatchesWaysAroundItsRules() {
        ClassDesc string = ConstantDescs.CD_String;
        ClassDesc object = ConstantDescs.CD_Object;
        Map<String, Consumer<CodeBuilder>> routes = new LinkedHashMap<>();
        routes.put("a method found by its name", code -> code
            .ldc(ConstantDescs.CD_Object).ldc("getConnection").iconst_0().anewarray(ConstantDescs.CD_Class)
            .invokevirtual(ConstantDescs.CD_Class, "getMethod", type(ClassDesc.of("java.lang.reflect.Method"), string, ConstantDescs.CD_Class.arrayType()))
            .aconst_null().iconst_0().anewarray(object)
            .invokevirtual(ClassDesc.of("java.lang.reflect.Method"), "invoke", type(object, object, object.arrayType())).pop());
        routes.put("a new object of a class", code -> code
            .ldc(ConstantDescs.CD_Object).invokevirtual(ConstantDescs.CD_Class, "newInstance", type(object)).pop());
        routes.put("Java's networking", code -> code
            .ldc("https://example.com/").invokestatic(ClassDesc.of("java.net.URI"), "create", type(ClassDesc.of("java.net.URI"), string))
            .invokevirtual(ClassDesc.of("java.net.URI"), "toURL", type(ClassDesc.of("java.net.URL")))
            .invokevirtual(ClassDesc.of("java.net.URL"), "openStream", type(ClassDesc.of("java.io.InputStream"))).pop());
        routes.put("a socket channel", code -> code
            .invokestatic(ClassDesc.of("java.nio.channels.SocketChannel"), "open", type(ClassDesc.of("java.nio.channels.SocketChannel"))).pop());
        routes.put("a class loaded by its name", code -> code
            .invokestatic(ClassDesc.of("java.lang.ClassLoader"), "getSystemClassLoader", type(ClassDesc.of("java.lang.ClassLoader")))
            .ldc("net.minecraft.client.multiplayer.ClientPacketListener")
            .invokevirtual(ClassDesc.of("java.lang.ClassLoader"), "loadClass", type(ConstantDescs.CD_Class, string)).pop());
        routes.put("method handles", code -> code
            .invokestatic(ConstantDescs.CD_MethodHandles, "lookup", type(ConstantDescs.CD_MethodHandles_Lookup)).pop());
        routes.put("another program", code -> code
            .invokestatic(ClassDesc.of("java.lang.Runtime"), "getRuntime", type(ClassDesc.of("java.lang.Runtime"))).ldc("curl")
            .invokevirtual(ClassDesc.of("java.lang.Runtime"), "exec", type(ClassDesc.of("java.lang.Process"), string)).pop());
        routes.put("native code", code -> code
            .ldc("sneaky").invokestatic(ClassDesc.of("java.lang.System"), "loadLibrary", type(ConstantDescs.CD_void, string)));
        routes.put("a command registered", code -> code
            .aconst_null().aconst_null()
            .invokevirtual(ClassDesc.of("com.mojang.brigadier.CommandDispatcher"), "register",
                type(ClassDesc.of("com.mojang.brigadier.tree.LiteralCommandNode"), ClassDesc.of("com.mojang.brigadier.builder.LiteralArgumentBuilder"))).pop());
        routes.put("a command run on the integrated server", code -> code
            .aconst_null().aconst_null().ldc("locate structure minecraft:village_plains")
            .invokevirtual(ClassDesc.of("net.minecraft.commands.Commands"), "performPrefixedCommand",
                type(ConstantDescs.CD_void, ClassDesc.of("net.minecraft.commands.CommandSourceStack"), string)));
        routes.forEach((route, body) -> assertTrue(!NoNetworkGuard.scanClass("bad/Route.class", probe("bad/Route", body)).isEmpty(), route));
        // What lambdas, records and string joining compile to stays allowed.
        assertEquals(List.of(), NoNetworkGuard.scanClass("ok/Lambda.class", probe("ok/Lambda", code -> code
            .invokestatic(ClassDesc.of("java.lang.Runtime"), "getRuntime", type(ClassDesc.of("java.lang.Runtime")))
            .invokevirtual(ClassDesc.of("java.lang.Runtime"), "availableProcessors", type(ConstantDescs.CD_int)).pop())));
    }

    private static final ClassDesc CHAT_SCREEN = ClassDesc.ofInternalName("net/minecraft/client/gui/screens/ChatScreen");
    private static final MethodTypeDesc CHAT_INPUT = MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String, ConstantDescs.CD_boolean);
    private static final String SENDS_CHAT = "net/minecraft/client/gui/screens/ChatScreen.handleChatInput (Ljava/lang/String;Z)V";

    /**
     * Game methods that send by themselves have harmless names (the chat screen's input sends chat and commands,
     * closing a container sends a packet). No rule by name sees them; they are caught because they are not on the
     * reviewed list, also when a class of the jar inherits them, even through an interface of its own.
     */
    @Test
    void gameMethodsThatSendNeedReview() throws IOException {
        ClassDesc player = ClassDesc.ofInternalName("net/minecraft/client/player/LocalPlayer");
        byte[] direct = probe("bad/Direct", code -> code
            .aconst_null().ldc("/locate structure minecraft:village_plains").iconst_0().invokevirtual(CHAT_SCREEN, "handleChatInput", CHAT_INPUT)
            .aconst_null().invokevirtual(player, "closeContainer", MethodTypeDesc.of(ConstantDescs.CD_void)));
        NoNetworkGuard.Result result = NoNetworkGuard.scan(Map.of("bad/Direct.class", direct), NoNetworkGuard.Names.NONE);
        assertEquals(List.of(), result.problems(), "no rule by name sees these");
        String closes = "net/minecraft/client/player/LocalPlayer.closeContainer ()V";
        assertEquals(Set.of(SENDS_CHAT, closes), result.libraryMembers().keySet());
        List<String> problems = NoNetworkGuard.unreviewed(result, reviewed(), REVIEWED);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad/Direct.class uses " + SENDS_CHAT + ", which ")), problems.toString());
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("bad/Direct.class uses " + closes + ", which ")), problems.toString());

        // Inherited: a screen of the jar calls the chat screen's method as its own.
        ClassDesc mine = ClassDesc.ofInternalName("bad/MyChat");
        byte[] subclass = ClassFile.of().build(mine, type -> type.withSuperclass(CHAT_SCREEN).withMethodBody("run",
            MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, code -> code
                .aload(0).ldc("/locate structure minecraft:village_plains").iconst_0().invokevirtual(mine, "handleChatInput", CHAT_INPUT).return_()));
        assertTrue(NoNetworkGuard.scan(Map.of("bad/MyChat.class", subclass), NoNetworkGuard.Names.NONE).libraryMembers().containsKey(SENDS_CHAT));

        // Through an interface of the jar that a subclass of the chat screen implements with the game's method.
        ClassDesc sender = ClassDesc.ofInternalName("bad/Sender");
        byte[] senderType = ClassFile.of().build(sender, type -> type.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT)
            .withMethod("handleChatInput", CHAT_INPUT, ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, method -> { }));
        byte[] sneaky = ClassFile.of().build(ClassDesc.ofInternalName("bad/SneakyChat"), type -> type.withSuperclass(CHAT_SCREEN).withInterfaceSymbols(sender));
        byte[] use = probe("bad/Use", code -> code
            .aconst_null().ldc("/locate structure minecraft:village_plains").iconst_0().invokeinterface(sender, "handleChatInput", CHAT_INPUT));
        result = NoNetworkGuard.scan(Map.of("bad/Sender.class", senderType, "bad/SneakyChat.class", sneaky, "bad/Use.class", use), NoNetworkGuard.Names.NONE);
        assertTrue(result.libraryMembers().containsKey(SENDS_CHAT), result.libraryMembers().toString());

        // A method of the jar's own (here one that overrides the game's) is the jar's code, not the game's.
        ClassDesc own = ClassDesc.ofInternalName("ok/OwnChat");
        byte[] ownType = ClassFile.of().build(own, type -> type.withSuperclass(CHAT_SCREEN)
            .withMethodBody("handleChatInput", CHAT_INPUT, ClassFile.ACC_PUBLIC, code -> code.return_()));
        byte[] useOwn = probe("ok/UseOwn", code -> code
            .aconst_null().ldc("hello").iconst_0().invokevirtual(own, "handleChatInput", CHAT_INPUT));
        assertEquals(Map.of(), NoNetworkGuard.scan(Map.of("ok/OwnChat.class", ownType, "ok/UseOwn.class", useOwn), NoNetworkGuard.Names.NONE)
            .libraryMembers());
    }

    /** The same through the intermediary names of the jar that ships (only for obfuscated Minecraft versions). */
    @Test
    void gameMethodsThatSendNeedReviewAlsoUnderIntermediaryNames() throws IOException {
        NoNetworkGuard.Names names = names();
        assumeTrue(names.remapped(), "this version's jar uses Minecraft's own names");
        ClassDesc chat = ClassDesc.ofInternalName(intermediary(names.classes(), "net/minecraft/client/gui/screens/ChatScreen"));
        byte[] direct = probe("bad/Direct", code -> code
            .aconst_null().ldc("/locate structure minecraft:village_plains").iconst_0()
            .invokevirtual(chat, intermediaryMember(names, "handleChatInput"), CHAT_INPUT));
        NoNetworkGuard.Result result = NoNetworkGuard.scan(Map.of("bad/Direct.class", direct), names);
        assertEquals(List.of(), result.problems());
        assertEquals(Set.of(SENDS_CHAT), result.libraryMembers().keySet());
        assertTrue(NoNetworkGuard.unreviewed(result, reviewed(), REVIEWED).stream().anyMatch(p -> p.contains(SENDS_CHAT)));
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
