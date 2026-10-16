package io.github.jriiik.seedfinder.mod;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads compiled classes and lists every reference to something that could send data to a server, register or run
 * chat commands, patch the game's code (mixins), or get around these rules (Java's networking, reflection, method
 * handles, class loading, other programs, native code). Also looks inside nested jars (the engine).
 *
 * <p>Rules by name can't see a game method that sends something by itself: the chat screen's input sends chat and
 * commands, closing a container or dropping an item sends a packet. So every member of code outside the jar and outside
 * Java itself (the game, Fabric and the libraries the game ships) that the jar uses is collected as well
 * ({@link Result#libraryMembers}), including members a class of the jar inherits from the game, and
 * {@link #unreviewed} compares them with a reviewed list: a new one fails the build until someone has checked that it
 * sends nothing and added it.
 *
 * <p>The jar for an obfuscated Minecraft version (1.21.11 and older) uses Fabric's intermediary names
 * ({@code net/minecraft/class_634}, {@code method_1562}); the guard turns them back into Minecraft's own names with the
 * mappings the jar was remapped with ({@link Names}), so the same rules apply, and any intermediary name it can't turn
 * back is a problem too.
 */
final class NoNetworkGuard {

    /** Internal-name prefixes that must not appear anywhere: not as a class, in a descriptor or in an annotation. */
    static final List<String> FORBIDDEN_PREFIXES = List.of(
        // Fabric's command, networking and message-sending APIs
        "net/fabricmc/fabric/api/client/command/",
        "net/fabricmc/fabric/api/command/",
        "net/fabricmc/fabric/api/client/networking/",
        "net/fabricmc/fabric/api/networking/",
        "net/fabricmc/fabric/api/client/message/v1/ClientSendMessageEvents",
        // the game's connection, packets and commands, and the libraries behind them
        "net/minecraft/client/multiplayer/ClientPacketListener",
        "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl",
        "net/minecraft/client/multiplayer/MultiPlayerGameMode",
        "net/minecraft/network/Connection",
        "net/minecraft/network/protocol/Packet",
        "net/minecraft/commands/",
        "com/mojang/brigadier/",
        "io/netty/",
        // mixins
        "org/spongepowered/",
        // Java's networking
        "java/net/",
        "javax/net/",
        "jdk/net/",
        "sun/net/",
        "com/sun/net/",
        "java/nio/channels/SocketChannel",
        "java/nio/channels/ServerSocketChannel",
        "java/nio/channels/DatagramChannel",
        "java/nio/channels/AsynchronousSocketChannel",
        "java/nio/channels/AsynchronousServerSocketChannel",
        "java/nio/channels/NetworkChannel",
        "java/nio/channels/spi/",
        "java/rmi/",
        "javax/rmi/",
        "javax/naming/",
        "javax/management/remote/",
        // ways around every rule here: reflection, loading code, other programs, native code, the JDK's insides
        "java/lang/reflect/",
        "java/lang/ClassLoader",
        "java/security/SecureClassLoader",
        "java/lang/Process",
        "java/lang/foreign/",
        "java/lang/instrument/",
        "javax/script/",
        "javax/tools/",
        "sun/misc/",
        "sun/reflect/",
        "jdk/internal/");
    static final Pattern SERVERBOUND = Pattern.compile("net/minecraft/network/protocol/[a-z/]+/Serverbound\\w*(?:\\$\\w+)*");
    static final Set<String> FORBIDDEN_MEMBERS = Set.of(
        "getConnection", "connection", "sendCommand", "sendChat", "sendUnattendedCommand", "sendPacket", "send",
        "setAccessible", "forName");
    /** Members of Java's own classes that reach code by its name, load code, or run other programs or native code. */
    static final Map<String, Set<String>> FORBIDDEN_JAVA_MEMBERS = Map.of(
        "java/lang/Class", Set.of("forName", "newInstance", "getMethod", "getMethods", "getDeclaredMethod", "getDeclaredMethods",
            "getField", "getFields", "getDeclaredField", "getDeclaredFields", "getConstructor", "getConstructors",
            "getDeclaredConstructor", "getDeclaredConstructors", "getRecordComponents", "getEnclosingMethod",
            "getEnclosingConstructor", "getClassLoader"),
        "java/lang/Runtime", Set.of("exec", "load", "loadLibrary"),
        "java/lang/System", Set.of("load", "loadLibrary"));
    /** Method handles reach any member by name; only what lambdas and string joining compile to is allowed. */
    static final String METHOD_HANDLES = "java/lang/invoke/";
    static final Set<String> ALLOWED_METHOD_HANDLE_CLASSES = Set.of("java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory");
    /** Java's own packages. Members of anything else outside the jar must be on the reviewed list. */
    static final List<String> JAVA_PACKAGES = List.of("java/", "javax/", "jdk/", "sun/", "com/sun/");
    /** Only the passive message listeners are allowed; ALLOW_* and MODIFY_* could hide or change messages. */
    static final Pattern FORBIDDEN_MESSAGE_FIELD = Pattern.compile("(ALLOW|MODIFY)_.*");
    static final String MESSAGE_EVENTS = "net/fabricmc/fabric/api/client/message/v1/ClientReceiveMessageEvents";

    /**
     * What a scan found.
     *
     * @param problems       references that are never allowed
     * @param libraryMembers every member of the game, Fabric or another library the classes use (also those a class
     *                       inherits from them), as {@code owner.name descriptor} in Minecraft's own names, with the
     *                       first class that uses it
     */
    record Result(int classes, List<String> problems, SortedMap<String, String> libraryMembers) {
    }

    /**
     * An intermediary name of a Minecraft class, also nested ones: {@code net/minecraft/class_1$class_2}, and nested in
     * a class that kept its name, like {@code net/minecraft/server/MinecraftServer$class_6897}.
     */
    static final Pattern INTERMEDIARY_CLASS = Pattern.compile(
        "(?:net/minecraft/class_\\d+|(?:[a-z][a-z0-9_]*/)+[A-Za-z]\\w*(?:\\$[A-Za-z]\\w*)*\\$class_\\d+)(?:\\$class_\\d+)*");
    /** A class name with an intermediary name in it, with the nested classes after it (also anonymous ones, {@code $1}). */
    private static final Pattern INTERMEDIARY_CLASS_IN_TEXT = Pattern.compile("(?:" + INTERMEDIARY_CLASS.pattern() + ")(?:\\$\\w+)*");
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

        /**
         * The text with every intermediary class name in it replaced by Minecraft's own. A nested class the mappings
         * don't have keeps its own part after the outer class's name ({@code Minecraft$class_99999}), which is
         * still an intermediary name.
         */
        String text(String value) {
            Matcher matcher = INTERMEDIARY_CLASS_IN_TEXT.matcher(value);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                matcher.appendReplacement(out, Matcher.quoteReplacement(className(matcher.group())));
            }
            return matcher.appendTail(out).toString();
        }

        /** Minecraft's own name of the class, or of the outermost classes the mappings have, with the rest kept. */
        private String className(String name) {
            for (String outer = name; ; ) {
                String named = classes.get(outer);
                if (named != null) {
                    return named + name.substring(outer.length());
                }
                int nested = outer.lastIndexOf('$');
                if (nested < 0) {
                    return name;
                }
                outer = outer.substring(0, nested);
            }
        }

        /** Minecraft's own names of a member (just the name when it isn't an intermediary name). */
        Set<String> member(String name) {
            return members.getOrDefault(name, Set.of(name));
        }

        boolean remapped() {
            return !classes.isEmpty();
        }
    }

    /**
     * A class of the jar: its supertypes and its own fields and methods ({@code name descriptor}; true when it has
     * code or is a field, false for an abstract method).
     */
    private record ClassInfo(String superName, List<String> interfaces, Map<String, Boolean> members) {

        static ClassInfo of(ClassModel model) {
            Map<String, Boolean> members = new HashMap<>();
            for (FieldModel field : model.fields()) {
                members.put(field.fieldName().stringValue() + " " + field.fieldType().stringValue(), true);
            }
            for (MethodModel method : model.methods()) {
                members.put(method.methodName().stringValue() + " " + method.methodType().stringValue(),
                    (method.flags().flagsMask() & ClassFile.ACC_ABSTRACT) == 0);
            }
            return new ClassInfo(model.superclass().map(ClassEntry::asInternalName).orElse(null),
                model.interfaces().stream().map(ClassEntry::asInternalName).toList(), members);
        }
    }

    /** The classes of the jar, to find where a member a class of the jar uses really comes from. */
    private static final class Hierarchy {
        private final Map<String, ClassInfo> classes;
        private final Map<String, Set<String>> directSubtypes = new HashMap<>();

        Hierarchy(Map<String, ClassInfo> classes) {
            this.classes = classes;
            classes.forEach((name, info) -> {
                if (info.superName() != null) {
                    directSubtypes.computeIfAbsent(info.superName(), type -> new HashSet<>()).add(name);
                }
                info.interfaces().forEach(type -> directSubtypes.computeIfAbsent(type, t -> new HashSet<>()).add(name));
            });
        }

        boolean contains(String type) {
            return classes.containsKey(type);
        }

        /**
         * The classes outside the jar whose code a reference to {@code member} of the jar's class {@code owner} may
         * reach (in the jar's own names): none when the jar has the member itself. A method may run in an object of
         * any subtype, so those count too; the first class up the line that has the method with code is the one that
         * runs, else one outside the jar (or a default method of an interface outside it).
         */
        Set<String> outsideOwners(String owner, String member, boolean method) {
            Set<String> outside = new TreeSet<>();
            for (String type : method ? withSubtypes(owner) : Set.of(owner)) {
                List<String> line = new ArrayList<>();
                boolean found = false;
                for (String current = type; current != null; ) {
                    ClassInfo info = classes.get(current);
                    if (info == null) {
                        outside.add(current);
                        break;
                    }
                    line.add(current);
                    if (info.members().getOrDefault(member, false) || !method && info.members().containsKey(member)) {
                        found = true;
                        break;
                    }
                    current = info.superName();
                }
                if (!found) {
                    outside.addAll(outsideInterfaces(line, member, method));
                }
            }
            return outside;
        }

        /** The interfaces outside the jar of these classes (and of their interfaces in the jar). */
        private Set<String> outsideInterfaces(List<String> types, String member, boolean method) {
            Set<String> outside = new TreeSet<>();
            Deque<String> todo = new ArrayDeque<>();
            types.forEach(type -> todo.addAll(classes.get(type).interfaces()));
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String type = todo.pop();
                if (!seen.add(type)) {
                    continue;
                }
                ClassInfo info = classes.get(type);
                if (info == null) {
                    outside.add(type);
                } else if (!(info.members().getOrDefault(member, false) || !method && info.members().containsKey(member))) {
                    todo.addAll(info.interfaces());
                }
            }
            return outside;
        }

        private Set<String> withSubtypes(String type) {
            Set<String> all = new LinkedHashSet<>();
            Deque<String> todo = new ArrayDeque<>(List.of(type));
            while (!todo.isEmpty()) {
                String current = todo.pop();
                if (all.add(current)) {
                    todo.addAll(directSubtypes.getOrDefault(current, Set.of()));
                }
            }
            return all;
        }
    }

    private NoNetworkGuard() {
    }

    static Result scanJar(String name, InputStream jar, Names names) throws IOException {
        Map<String, byte[]> classFiles = new LinkedHashMap<>();
        readJar(name, jar, classFiles);
        return scan(classFiles, names);
    }

    private static void readJar(String name, InputStream jar, Map<String, byte[]> into) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(jar)) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                String where = name + "!/" + entry.getName();
                if (entry.getName().endsWith(".class")) {
                    into.put(where, zip.readAllBytes());
                } else if (entry.getName().endsWith(".jar")) {
                    readJar(where, new ByteArrayInputStream(zip.readAllBytes()), into);
                }
            }
        }
    }

    /** Scans classes that belong together (a jar): {@code where} (a name for messages) to the class file. */
    static Result scan(Map<String, byte[]> classFiles, Names names) {
        Map<String, ClassModel> models = new LinkedHashMap<>();
        Map<String, ClassInfo> infos = new HashMap<>();
        classFiles.forEach((where, bytes) -> {
            ClassModel model = ClassFile.of().parse(bytes);
            models.put(where, model);
            infos.put(model.thisClass().asInternalName(), ClassInfo.of(model));
        });
        Hierarchy hierarchy = new Hierarchy(infos);
        List<String> problems = new ArrayList<>();
        SortedMap<String, String> libraryMembers = new TreeMap<>();
        models.forEach((where, model) -> problems.addAll(scanClass(where, model, names, hierarchy, libraryMembers)));
        return new Result(models.size(), problems, libraryMembers);
    }

    static List<String> scanClass(String where, byte[] bytes) {
        return scanClass(where, bytes, Names.NONE);
    }

    static List<String> scanClass(String where, byte[] bytes, Names names) {
        return scan(Map.of(where, bytes), names).problems();
    }

    /**
     * The library members the scan found that the reviewed list doesn't have, and the list's lines no member uses any
     * more (so the list stays what was really reviewed).
     */
    static List<String> unreviewed(Result result, Set<String> reviewed, String list) {
        List<String> problems = new ArrayList<>();
        result.libraryMembers().forEach((member, where) -> {
            if (!reviewed.contains(member)) {
                problems.add(where + " uses " + member + ", which " + list + " doesn't have: check in the game's code that it sends"
                    + " nothing to the server and registers or runs no command, then add that line");
            }
        });
        for (String member : new TreeSet<>(reviewed)) {
            if (!result.libraryMembers().containsKey(member)) {
                problems.add(list + " has " + member + ", which the jar doesn't use any more: remove that line");
            }
        }
        return problems;
    }

    private static boolean isJava(String type) {
        return type.startsWith("[") || JAVA_PACKAGES.stream().anyMatch(type::startsWith);
    }

    private static List<String> scanClass(String where, ClassModel model, Names names, Hierarchy hierarchy, Map<String, String> libraryMembers) {
        List<String> problems = new ArrayList<>();
        for (PoolEntry entry : model.constantPool()) {
            switch (entry) {
                case ClassEntry type -> {
                    String internalName = names.text(type.asInternalName());
                    if (SERVERBOUND.matcher(internalName).matches()) {
                        problems.add(where + " references packet " + internalName);
                    }
                }
                case MemberRefEntry member -> {
                    String rawOwner = member.owner().asInternalName();
                    String owner = names.text(rawOwner);
                    String written = member.name().stringValue();
                    String descriptor = member.type().stringValue();
                    Set<String> known = names.member(written);
                    for (String name : known) {
                        if (FORBIDDEN_MEMBERS.contains(name)) {
                            problems.add(where + " references member " + owner + "." + name);
                        }
                        if (owner.equals(MESSAGE_EVENTS) && FORBIDDEN_MESSAGE_FIELD.matcher(name).matches()) {
                            problems.add(where + " uses message event " + name + " (only listening is allowed)");
                        }
                        if (FORBIDDEN_JAVA_MEMBERS.getOrDefault(owner, Set.of()).contains(name)) {
                            problems.add(where + " references member " + owner + "." + name);
                        }
                    }
                    if (owner.startsWith(METHOD_HANDLES) && !ALLOWED_METHOD_HANDLE_CLASSES.contains(owner)) {
                        problems.add(where + " references member " + owner + "." + written + " (method handles reach any member by its name)");
                    }
                    if (known.contains(written) && INTERMEDIARY_MEMBER.matcher(written).matches()) {
                        problems.add(where + " references " + owner + "." + written + ", an intermediary name "
                            + (names.remapped() ? "the mappings don't have" : "(no mappings given)"));
                    }
                    boolean method = !(member instanceof FieldRefEntry);
                    Set<String> outside = hierarchy.contains(rawOwner)
                        ? hierarchy.outsideOwners(rawOwner, written + " " + descriptor, method) : Set.of(rawOwner);
                    for (String type : outside) {
                        String libraryType = names.text(type);
                        if (!isJava(libraryType)) {
                            for (String name : known) {
                                libraryMembers.putIfAbsent(libraryType + "." + name + " " + names.text(descriptor), where);
                            }
                        }
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
