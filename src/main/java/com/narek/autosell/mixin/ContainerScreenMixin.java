package com.narek.autosell.mixin;

import com.narek.autosell.fuel.ShopRecorder;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the player's own container clicks so a shop path can be recorded.
 *
 * <p>Only the player's clicks pass through {@code slotClicked}; the mod's own
 * automation calls {@code handleContainerInput} directly, so replay cannot
 * accidentally re-record itself.
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin extends Screen {

	protected ContainerScreenMixin() {
		super(null);
	}

	@Inject(method = "slotClicked", at = @At("HEAD"))
	private void autosell$recordClick(Slot slot, int slotId, int button,
			ContainerInput type, CallbackInfo ci) {
		if (!ShopRecorder.isRecording() || slot == null || slotId < 0) {
			return;
		}
		ShopRecorder.record(this.getTitle().getString(), slotId, button, slot.getItem());
	}

	@Inject(method = "onClose", at = @At("HEAD"))
	private void autosell$finishRecording(CallbackInfo ci) {
		ShopRecorder.finish();
	}
}
