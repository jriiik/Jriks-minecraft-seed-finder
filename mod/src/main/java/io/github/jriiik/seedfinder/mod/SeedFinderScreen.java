package io.github.jriiik.seedfinder.mod;

import java.util.OptionalLong;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.FittingMultiLineTextWidget;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The mod's main screen, opened with its key. Everything stays on this computer. */
final class SeedFinderScreen extends Screen {

    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
    private final WorldSession session;
    private final SearchRunner runner;
    private FittingMultiLineTextWidget status;
    private Button collect;
    private Button showStatus;
    private Button copySeed;

    SeedFinderScreen(WorldSession session, SearchRunner runner) {
        super(Component.translatable("gui.jriksseedfinder.title"));
        this.session = session;
        this.runner = runner;
    }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        LinearLayout contents = LinearLayout.vertical().spacing(6);
        status = new FittingMultiLineTextWidget(0, 0, 360, 130, Component.literal(""), font);
        contents.addChild(status);

        LinearLayout row = LinearLayout.horizontal().spacing(6);
        collect = row.addChild(Button.builder(Component.empty(), button -> session.setCollecting(!session.collecting())).width(176).build());
        row.addChild(Button.builder(Component.translatable("gui.jriksseedfinder.add_structure"),
            button -> minecraft.gui.setScreen(new StructurePickerScreen(this, session))).width(176).build());
        contents.addChild(row);

        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
        LinearLayout tools = footer.addChild(LinearLayout.horizontal().spacing(6));
        copySeed = tools.addChild(Button.builder(Component.translatable("gui.jriksseedfinder.copy_seed"), button -> {
            OptionalLong seed = StatusText.seedToCopy(session);
            seed.ifPresent(value -> minecraft.keyboardHandler.setClipboard(Long.toString(value)));
        }).width(114).build());
        tools.addChild(Button.builder(Component.translatable("gui.jriksseedfinder.copy_notes"),
            button -> minecraft.keyboardHandler.setClipboard(String.join("\n", session.notesLines()))).width(114).build());
        tools.addChild(Button.builder(Component.translatable("gui.jriksseedfinder.remove_structure"),
            button -> minecraft.gui.setScreen(new RemoveStructureScreen(this, session))).width(114).build());
        LinearLayout bottom = footer.addChild(LinearLayout.horizontal().spacing(6));
        showStatus = bottom.addChild(Button.builder(Component.empty(), button -> session.setShowStatus(!session.showStatus())).width(176).build());
        bottom.addChild(Button.builder(Component.translatable("gui.jriksseedfinder.close"), button -> onClose()).width(176).build());

        layout.addToContents(contents);
        layout.visitWidgets(this::addRenderableWidget);
        refresh();
        repositionElements();
    }

    @Override
    public void tick() {
        refresh();
    }

    private void refresh() {
        status.setMessage(Component.literal(String.join("\n", StatusText.lines(session, runner))));
        collect.setMessage(Component.translatable(session.collecting() ? "gui.jriksseedfinder.collect.on" : "gui.jriksseedfinder.collect.off"));
        showStatus.setMessage(Component.translatable(session.showStatus() ? "gui.jriksseedfinder.show_status.on" : "gui.jriksseedfinder.show_status.off"));
        copySeed.active = StatusText.seedToCopy(session).isPresent();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
    }
}
