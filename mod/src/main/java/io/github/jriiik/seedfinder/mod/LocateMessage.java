package io.github.jriiik.seedfinder.mod;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Recognises the answer to a "/locate structure" command the player typed themselves. The mod only reads the answer
 * that appears in the chat; it never sends commands.
 *
 * @param structure the structure id, like {@code minecraft:village_plains}
 */
record LocateMessage(String structure, int x, int z) {

    static final String SUCCESS_KEY = "commands.locate.structure.success";

    static Optional<LocateMessage> parse(Component message) {
        if (!(message.getContents() instanceof TranslatableContents contents) || !contents.getKey().equals(SUCCESS_KEY)) {
            return Optional.empty();
        }
        Object[] args = contents.getArgs();
        if (args.length < 2) {
            return Optional.empty();
        }
        // "minecraft:igloo", or for a tag search "#minecraft:village (minecraft:village_plains)".
        String structure = text(args[0]).strip();
        int open = structure.lastIndexOf('(');
        if (open >= 0 && structure.endsWith(")")) {
            structure = structure.substring(open + 1, structure.length() - 1);
        }
        TranslatableContents coordinates = find(args[1], "chat.coordinates");
        if (coordinates == null || coordinates.getArgs().length < 3) {
            return Optional.empty();
        }
        try {
            int x = Integer.parseInt(text(coordinates.getArgs()[0]).strip());
            int z = Integer.parseInt(text(coordinates.getArgs()[2]).strip());
            return Optional.of(new LocateMessage(structure, x, z));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static String text(Object arg) {
        return arg instanceof Component component ? component.getString() : String.valueOf(arg);
    }

    /** The translatable text with this key anywhere in a message (also nested in its arguments), or null. */
    static TranslatableContents find(Object node, String key) {
        if (!(node instanceof Component component)) {
            return null;
        }
        if (component.getContents() instanceof TranslatableContents contents) {
            if (contents.getKey().equals(key)) {
                return contents;
            }
            for (Object arg : contents.getArgs()) {
                TranslatableContents found = find(arg, key);
                if (found != null) {
                    return found;
                }
            }
        }
        for (Component sibling : component.getSiblings()) {
            TranslatableContents found = find(sibling, key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
