package dev.drivehost.client.mixin;

import dev.drivehost.client.screen.DriveHostMainScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a "DriveHost" button on the Minecraft title screen,
 * positioned below the Multiplayer button.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void drivehost$addButton(CallbackInfo ci) {
        // Position below multiplayer: multiplayer is at y = height/4 + 48 + 24 = height/4 + 72
        // We place our button right after Realms (height/4 + 72 + 24 = height/4 + 96)
        // Actually, to be safe, we just place it at a fixed offset from the bottom of the vanilla buttons
        int buttonWidth = 200;
        int buttonHeight = 20;
        int x = this.width / 2 - buttonWidth / 2;
        int y = this.height / 4 + 96 + 12; // Below the Realms button with some padding

        this.addRenderableWidget(
            Button.builder(Component.literal("DriveHost"), button -> {
                this.minecraft.setScreen(new DriveHostMainScreen(this));
            })
            .bounds(x, y, buttonWidth, buttonHeight)
            .build()
        );
    }
}
