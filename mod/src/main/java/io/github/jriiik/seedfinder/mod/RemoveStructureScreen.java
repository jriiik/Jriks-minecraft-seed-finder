package io.github.jriiik.seedfinder.mod;

import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Lists the noted structures; clicking one removes it (for structures noted in the wrong place). "Remove all notes"
 * starts over, for a world that was reset or replaced.
 */
final class RemoveStructureScreen extends Screen {

    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);
    private final Screen parent;
    private final WorldSession session;
    private ScrollableLayout list;

    RemoveStructureScreen(Screen parent, WorldSession session) {
        super(Component.translatable("gui.jriksseedfinder.remove_structure"));
        this.parent = parent;
        this.session = session;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        LinearLayout buttons = LinearLayout.vertical().spacing(2);
        List<WorldSession.NotedStructure> structures = session.structures();
        if (structures.isEmpty()) {
            buttons.addChild(new StringWidget(Component.literal("No structures noted yet."), font));
        }
        for (int i = 0; i < structures.size(); i++) {
            WorldSession.NotedStructure structure = structures.get(i);
            int index = i;
            buttons.addChild(Button.builder(Component.literal(structure.set() + " at " + structure.x() + ", " + structure.z()
                + " (" + structure.source() + ")"), button -> {
                    session.removeStructure(index);
                    onClose();
                }).width(300).build());
        }
        list = new ScrollableLayout(minecraft, buttons, height - 33 - 33 - 20);
        layout.addToContents(list);
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(6));
        footer.addChild(Button.builder(Component.translatable("gui.jriksseedfinder.start_over"), button -> minecraft.gui.setScreen(new ConfirmScreen(
            confirmed -> {
                if (confirmed) {
                    SeedFinderClient.startOver();
                }
                onClose();
            },
            Component.translatable("gui.jriksseedfinder.start_over.title"), Component.translatable("gui.jriksseedfinder.start_over.message")))).width(150).build());
        footer.addChild(Button.builder(Component.translatable("gui.jriksseedfinder.back"), button -> onClose()).width(150).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    protected void repositionElements() {
        if (list != null) {
            list.arrangeElements();
        }
        layout.arrangeElements();
    }
}
