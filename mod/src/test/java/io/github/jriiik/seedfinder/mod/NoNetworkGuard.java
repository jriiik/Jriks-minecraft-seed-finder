package io.github.jriiik.seedfinder.mod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads compiled classes and lists every reference to something that could send data to a server, register chat
 * commands, or patch the game's code (mixins). Also looks inside nested jars (the engine).
 *
 * <p>The jar for an obfuscated Minecraft version (1.21.11 and older) uses Fabric's intermediary names
 * ({@code net/minecraft/class_634}, {@code method_1562}); the guard turns them back into Minecraft's own names with the
 * mappings the jar was remapped with ({@link Names}), so the same rules apply, and any intermediary name it can't turn
 * back is a problem too.
 */
final class NoNetworkGuard {

    /** Internal-name prefixes that must not appear anywhere: not as a class, in a descriptor or in an annotation. */
    static final List<String> FORBIDDEN_PREFIXES = List.of(
        "net/fabricmc/fabric/api/client/command/",
        "net/fabricmc/fabric/api/command/",
        "net/fabricmc/fabric/api/client/networking/",
        "net/fabricmc/fabric/api/networking/",
        "net/fabricmc/fabric/api/client/message/v1/ClientSendMessageEvents",
        "net/minecraft/client/multiplayer/ClientPacketListener",
        "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
        "net/minecraft/client/multiplayer/MultiPlayerGameMode",
        "net/minecraft/network/Connection",
        "net/minecraft/network/protocol/Packet",
        "org/spongepowered/");
    static final Pattern SERVERBOUND = Pattern.compile("net/minecraft/network/protocol/[a-z/]+/Serverbound\\w*");
    static final Set<String> FORBIDDEN_MEMBERS = Set.of(
        "getConnection", "connection", "sendCommand", "sendChat", "sendUnattendedCommand", "sendPacket", "send",
        "setAccessible", "forName");
    /** Only the passive message listeners are allowed; ALLOW_* and MODIFY_* could hide or change messages. */
    static final Pattern FORBIDDEN_MESSAGE_FIELD = Pattern.compile("(ALLOW|MODIFY)_.*");
    static final String MESSAGE_EVENTS = "net/fabricmc/fabric/api/client/message/v1/ClientReceiveMessageEvents";

    record Result(int classes, List<String> problems) {
    }

    /** An intermediary name of a Minecraft class, also nested ones ({@code net/minecraft/class_1$class_2}). */
    static final Pattern INTERMEDIARY_CLASS = Pattern.compile("net/minecraft/class_\\d+(?:\\$class_\\d+)*");
    /** An intermediary name of a Minecraft method, field or record component. */
    static final Pattern INTERMEDIARY_MEMBER = Pattern.compile("(?:method|field|comp)_\\d+");

    /**
     * Minecraft's own names for the intermediary names of a remapped jar, read from Loom's mappings file (Tiny v2 with
     * "intermediary" and "named" names); {@link #NONE} for a jar that uses Minecraft's own names already. A class or
     * member whose Minecraft name is empty makes the file unusable: that name could hide what it stands for.
     */
    record Names(Map<String, String> classes, Map<String, Set<String>> members) {
        static final Names NONE = new Names(Map.of(), Map.of());

        static Names read(Path tiny) throws IOException {
            List<String> lines = Files.readAllLines(tiny, StandardCharsets.UTF_8);
            List<String> header = List.of(lines.getFirst().split("\t"));
            int from = header.indexOf("intermediary") - 3;
            int to = header.indexOf("named") - 3;
            if (!header.getFirst().equals("tiny") || !header.get(1).equals("2") || from < 0 || to < 0) {
                throw new IOException("not Tiny v2 mappings with intermediary and named names: " + tiny);
            }
            Map<String, String> classes = new HashMap<>();
            Map<String, Set<String>> members = new HashMap<>();
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split("\t", -1);
                if (parts[0].equals("c")) {
                    classes.put(parts[1 + from], named(parts[1 + to], tiny, i));
                } else if (parts.length > 3 + Math.max(from, to) && parts[0].isEmpty() && (parts[1].equals("m") || parts[1].equals("f"))) {
                    // Overriding methods share one intermediary name; all of their names count.
                    members.computeIfAbsent(parts[3 + from], name -> new HashSet<>()).add(named(parts[3 + to], tiny, i));
                }
            }
            return new Names(Map.copyOf(classes), Map.copyOf(members));
        }

        private static String named(String name, Path tiny, int index) throws IOException {
            if (name.isEmpty()) {
                throw new IOException("line " + (index + 1) + " of " + tiny + " has no Minecraft (named) name");
            }
            return name;
        }

        /** The text with every intermediary class name in it replaced by Minecraft's own (unknown ones are kept). */
        String text(String value) {
            Matcher matcher = INTERMEDIARY_CLASS.matcher(value);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(classes.getOrDefault(matcher.group(), matcher.group())));
            }
            return matcher.appendTail(out).toString();
        }

        /** Minecraft's own names of a member (just the name when it isn't an intermediary name). */
        Set<String> member(String name) {
            return members.getOrDefault(name, Set.of(name));
        }

        boolean remapped() {
            return !classes.isEmpty();
        }
    }

    private NoNetworkGuard() {
    }

    static Result scanJar(String name, InputStream jar, Names names) throws IOException {
        List<String> problems = new ArrayList<>();
        int classes = 0;
        try (ZipInputStream zip = new ZipInputStream(jar)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String where = name + "!/" + entry.getName();
                if (entry.getName().endsWith(".class")) {
                    classes++;
                    problems.addAll(scanClass(where, zip.readAllBytes(), names));
                } else if (entry.getName().endsWith(".jar")) {
                    Result nested = scanJar(where, new ByteArrayInputStream(zip.readAllBytes()), names);
                    classes += nested.classes();
                    problems.addAll(nested.problems());
                }
            }
        }
        return new Result(classes, problems);
    }

    static List<String> scanClass(String where, byte[] bytes) {
        return scanClass(where, bytes, Names.NONE);
    }

    static List<String> scanClass(String where, byte[] bytes, Names names) {
        List<String> problems = new ArrayList<>();
        ClassModel model = ClassFile.of().parse(bytes);
        for (PoolEntry entry : model.constantPool()) {
            switch (entry) {
                case ClassEntry type -> {
                    String internalName = names.text(type.asInternalName());
                    if (SERVERBOUND.matcher(internalName).matches()) {
                        problems.add(where + " references packet " + internalName);
                    }
                }
                case MemberRefEntry member -> {
                    String owner = names.text(member.owner().asInternalName());
                    String written = member.name().stringValue();
                    Set<String> known = names.member(written);
                    for (String name : known) {
                        if (FORBIDDEN_MEMBERS.contains(name)) {
                            problems.add(where + " references member " + owner + "." + name);
                        }
                        if (owner.equals(MESSAGE_EVENTS) && FORBIDDEN_MESSAGE_FIELD.matcher(name).matches()) {
                            problems.add(where + " uses message event " + name + " (only listening is allowed)");
                        }
                    }
                    if (known.contains(written) && INTERMEDIARY_MEMBER.matcher(written).matches()) {
                        problems.add(where + " references " + owner + "." + written + ", an intermediary name "
                            + (names.remapped() ? "the mappings don't have" : "(no mappings given)"));
                    }
                }
                case Utf8Entry text -> {
                    // Class names, descriptors, generic signatures and annotation types all live in UTF-8 entries.
                    String value = names.text(text.stringValue());
                    if (INTERMEDIARY_CLASS.matcher(value).find()) {
                        // Without mappings the rules would not see what such a name stands for.
                        problems.add(where + " mentions an intermediary class name " + (names.remapped() ? "the mappings don't have" : "(no mappings given)")
                            + " in \"" + value + "\"");
                    }
                    for (String prefix : FORBIDDEN_PREFIXES) {
                        if (value.contains(prefix)) {
                            problems.add(where + " mentions " + prefix + " in \"" + value + "\"");
                        }
                    }
                    Matcher packet = SERVERBOUND.matcher(value);
                    while (packet.find()) {
                        problems.add(where + " mentions packet " + packet.group());
                    }
                }
                default -> {
                }
            }
        }
        return problems;
    }
}
