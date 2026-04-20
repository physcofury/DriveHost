package dev.drivehost.client.screen;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Main hub screen for DriveHost — offers Create World and Join World options.
 */
public class DriveHostMainScreen extends Screen {

    private final Screen parent;

    public DriveHostMainScreen(Screen parent) {
        super(Component.literal("DriveHost"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int startY = this.height / 4 + 24;
        int buttonWidth = 200;
        int buttonHeight = 20;
        int spacing = 28;

        // Create World button
        this.addRenderableWidget(
            Button.builder(Component.literal("Create World"), button -> {
                this.minecraft.setScreen(new CreateWorldScreen(this));
            })
            .bounds(centerX - buttonWidth / 2, startY, buttonWidth, buttonHeight)
            .build()
        );

        // Join World button
        this.addRenderableWidget(
            Button.builder(Component.literal("Join World"), button -> {
                this.minecraft.setScreen(new JoinWorldScreen(this));
            })
            .bounds(centerX - buttonWidth / 2, startY + spacing, buttonWidth, buttonHeight)
            .build()
        );

        // Back button
        this.addRenderableWidget(
            Button.builder(Component.literal("Back"), button -> {
                this.minecraft.setScreen(parent);
            })
            .bounds(centerX - buttonWidth / 2, startY + spacing * 3, buttonWidth, buttonHeight)
            .build()
        );
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
            Component.literal("Decentralised P2P hosting via Google Drive"),
            this.width / 2, 36, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
