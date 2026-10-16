package io.github.jriiik.seedfinder.mod;

import io.github.jriiik.seedfinder.structure.RandomSpreadPlacement;
import io.github.jriiik.seedfinder.structure.StructureSet;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Notes a structure at the player's position: the player picks its type. */
final class StructurePickerScreen extends Screen {

    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);
    private final Screen parent;
    private final WorldSession session;
    private ScrollableLayout list;

    StructurePickerScreen(Screen parent, WorldSession session) {
        super(Component.translatable("gui.jriksseedfinder.add_structure"));
        this.parent = parent;
        this.session = session;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        LinearLayout contents = LinearLayout.vertical().spacing(6);
        contents.addChild(new FittingMultiLineTextWidget(0, 0, 360, 40, Component.literal(
            "Stand in the chunk where the structure starts (F3+G shows chunk borders), then pick its type. The start chunk "
                + "is hard to see, so use /locate if you may. Types the mod recognizes by itself ("
                + StatusText.recognizedTypes(SeedFinderClient.data()) + ") are not listed."), font));
        LinearLayout buttons = LinearLayout.vertical().spacing(2);
        String dimension = minecraft.level == null ? "minecraft:overworld" : minecraft.level.dimension().identifier().toString();
        for (StructureSet set : usableSets(dimension)) {
            buttons.addChild(Button.builder(Component.literal(displayName(set)), button -> add(set)).width(240).build());
        }
        list = new ScrollableLayout(minecraft, buttons, height - 33 - 33 - 60);
        contents.addChild(list);
        layout.addToContents(contents);
        layout.addToFooter(Button.builder(Component.translatable("gui.jriksseedfinder.back"), button -> onClose()).width(200).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private List<StructureSet> usableSets(String dimension) {
        return SeedFinderClient.data().structureSets().stream()
            .filter(set -> set.placement() instanceof RandomSpreadPlacement spread && spread.spacing() > 1)
            .filter(set -> SeedFinderClient.data().dimensionsOf(set).contains(dimension))
            .filter(set -> !StructureRecognition.recognizedSets().contains(set.shortId()))
            .toList();
    }

    static String displayName(StructureSet set) {
        String name = set.shortId().replace('_', ' ');
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    private void add(StructureSet set) {
        if (minecraft.player != null) {
            BlockPos at = minecraft.player.blockPosition();
            session.addStructure(new WorldSession.NotedStructure(set.shortId(), at.getX(), at.getZ(), "marked by you"));
        }
        onClose();
    }

    @Override
    public void onClose() {
        GameVersion.setScreen(minecraft, parent);
    }

    @Override
    protected void repositionElements() {
        if (list != null) {
            list.arrangeElements();
        }
        layout.arrangeElements();
    }
}
