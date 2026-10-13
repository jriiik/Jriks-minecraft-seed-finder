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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads compiled classes and lists every reference to something that could send data to a server, register chat
 * commands, or patch the game's code (mixins). Also looks inside nested jars (the engine).
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

    private NoNetworkGuard() {
    }

    static Result scanJar(String name, InputStream jar) throws IOException {
        List<String> problems = new ArrayList<>();
        int classes = 0;
        try (ZipInputStream zip = new ZipInputStream(jar)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String where = name + "!/" + entry.getName();
                if (entry.getName().endsWith(".class")) {
                    classes++;
                    problems.addAll(scanClass(where, zip.readAllBytes()));
                } else if (entry.getName().endsWith(".jar")) {
                    Result nested = scanJar(where, new ByteArrayInputStream(zip.readAllBytes()));
                    classes += nested.classes();
                    problems.addAll(nested.problems());
                }
            }
        }
        return new Result(classes, problems);
    }

    static List<String> scanClass(String where, byte[] bytes) {
        List<String> problems = new ArrayList<>();
        ClassModel model = ClassFile.of().parse(bytes);
        for (PoolEntry entry : model.constantPool()) {
            switch (entry) {
                case ClassEntry type -> {
                    if (SERVERBOUND.matcher(type.asInternalName()).matches()) {
                        problems.add(where + " references packet " + type.asInternalName());
                    }
                }
                case MemberRefEntry member -> {
                    String owner = member.owner().asInternalName();
                    String name = member.name().stringValue();
                    if (FORBIDDEN_MEMBERS.contains(name)) {
                        problems.add(where + " references member " + owner + "." + name);
                    }
                    if (owner.equals(MESSAGE_EVENTS) && FORBIDDEN_MESSAGE_FIELD.matcher(name).matches()) {
                        problems.add(where + " uses message event " + name + " (only listening is allowed)");
                    }
                }
                case Utf8Entry text -> {
                    // Class names, descriptors, generic signatures and annotation types all live in UTF-8 entries.
                    String value = text.stringValue();
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
