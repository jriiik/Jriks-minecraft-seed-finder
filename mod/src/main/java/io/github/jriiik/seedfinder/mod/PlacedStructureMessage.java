package io.github.jriiik.seedfinder.mod;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Recognises the answer to "/place structure ...": a structure placed by command sits on a chunk corner like a real
 * start, but not where the seed would put one, so the mod must never note it. Only reads the chat; never sends.
 *
 * @param structure the structure id, like {@code minecraft:village_plains}
 * @param x, z      the position given to the command; the structure starts in the chunk containing it
 */
record PlacedStructureMessage(String structure, int x, int z) {

    static final String SUCCESS_KEY = "commands.place.structure.success";

    static Optional<PlacedStructureMessage> parse(Component message) {
        // The player's own command, or the same text inside an admin notice ("[Player: ...]").
        TranslatableContents contents = LocateMessage.find(message, SUCCESS_KEY);
        if (contents == null || contents.getArgs().length < 4) {
            return Optional.empty();
        }
        Object[] args = contents.getArgs();
        try {
            return Optional.of(new PlacedStructureMessage(LocateMessage.text(args[0]).strip(),
                Integer.parseInt(LocateMessage.text(args[1]).strip()), Integer.parseInt(LocateMessage.text(args[3]).strip())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
