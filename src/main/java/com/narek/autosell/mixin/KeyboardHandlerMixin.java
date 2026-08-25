package com.narek.autosell.mixin;

import com.narek.autosell.AutoSellClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taps raw key presses before Minecraft routes them.
 *
 * <p>This has to sit below the screen layer: container screens consume number
 * keys for hotbar swaps, so a {@code KeyMapping} would never fire while a chest
 * is open - which is exactly when the routine needs to be triggerable.
 *
 * <p>Observation only; the event is never cancelled, so vanilla hotbar
 * switching keeps working normally.
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

	@Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"))
	private void autosell$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		if (action != GLFW.GLFW_PRESS) {
			return;
		}
		AutoSellClient.onKeyPressed(event.key());
	}
}
