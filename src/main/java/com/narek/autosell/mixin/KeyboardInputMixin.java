package com.narek.autosell.mixin;

import com.narek.autosell.AutoSellClient;
import com.narek.autosell.core.Walker;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Substitutes synthetic movement while the walker is running.
 *
 * <p>Injected at TAIL of {@link KeyboardInput#tick()} - vanilla rebuilds both
 * {@code keyPresses} and {@code moveVector} from the keyboard every tick, so
 * this has to land after that work or it would simply be overwritten.
 * {@code moveVector} is what actually drives movement, so it is set alongside
 * the key record rather than instead of it.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends ClientInput {

	@Inject(method = "tick", at = @At("TAIL"))
	private void autosell$overrideForWalk(CallbackInfo ci) {
		Walker walker = AutoSellClient.walker();
		if (walker == null || !walker.isActive()) {
			return;
		}
		// Baritone drives the player itself; do not fight it. Fine alignment is
		// ours, though, and runs after Baritone has finished.
		if (walker.isDelegated() && !walker.isAligning()) {
			return;
		}
		this.keyPresses = walker.buildInput();
		// Magnitude below 1 walks slower, which is how the walker eases into
		// corners and stops instead of overshooting.
		this.moveVector = walker.moveVector();
	}
}
