package com.narek.autosell;

import com.narek.autosell.command.AutoSellCommands;
import com.narek.autosell.config.AutoSellConfig;
import com.narek.autosell.core.SellRoutine;
import com.narek.autosell.core.TriplePress;
import com.narek.autosell.hud.AutoSellHud;
import com.narek.autosell.profit.ProfitTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoSellClient implements ClientModInitializer {
	public static final String MOD_ID = "autosell";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final TriplePress TRIPLE = new TriplePress();
	private static final SellRoutine ROUTINE = new SellRoutine();
	private static final com.narek.autosell.fuel.FuelRoutine FUEL =
			new com.narek.autosell.fuel.FuelRoutine();

	public static SellRoutine routine() {
		return ROUTINE;
	}

	public static TriplePress triplePress() {
		return TRIPLE;
	}

	public static com.narek.autosell.fuel.FuelRoutine fuel() {
		return FUEL;
	}

	/**
	 * Used by the input mixin to drive synthetic movement. Whichever routine is
	 * currently walking owns the input; only one runs at a time.
	 */
	public static com.narek.autosell.core.Walker walker() {
		return FUEL.walker().isActive() ? FUEL.walker() : ROUTINE.walker();
	}

	@Override
	public void onInitializeClient() {
		AutoSellConfig.get();

		HudElementRegistry.addLast(
				Identifier.fromNamespaceAndPath(MOD_ID, "overlay"), new AutoSellHud());

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			ROUTINE.tick();
			FUEL.tick();
			com.narek.autosell.fuel.ShopRecorder.tick();
			if (AutoSellConfig.get().guiLogging) {
				com.narek.autosell.core.GuiLogger.tick();
			}
		});

		// The sell confirmation arrives as a system message, not player chat.
		ClientReceiveMessageEvents.GAME.register(
				(message, overlay) -> ProfitTracker.get().onGameMessage(message));

		ClientCommandBridge.register();

		LOGGER.info("[autosell] ready - triple-press to run");
	}

	/**
	 * Client-side only chat output; nothing is ever sent to the server.
	 *
	 * <p>Routed through the vanilla chat listener, which is the same path
	 * incoming system messages take - so the re-entrancy guard keeps the profit
	 * parser from reading our own output back as a sale.
	 */
	private static boolean emittingOwnMessage;

	public static void chat(Component message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		emittingOwnMessage = true;
		try {
			mc.gui.chatListener().handleSystemMessage(message, false);
		} finally {
			emittingOwnMessage = false;
		}
	}

	/** True while {@link #chat} is writing, so listeners can skip our lines. */
	public static boolean isEmittingOwnMessage() {
		return emittingOwnMessage;
	}

	/** Split out so command registration stays testable in isolation. */
	private static final class ClientCommandBridge {
		static void register() {
			AutoSellCommands.register();
		}
	}

	/**
	 * Called from the keyboard mixin on every physical key press.
	 *
	 * @return true when the routine was triggered
	 */
	public static boolean onKeyPressed(int key) {
		// Inspect works with a container focused, where chat is unreachable.
		if (key == AutoSellConfig.get().inspectKey) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null && mc.gui.screen() instanceof AbstractContainerScreen<?>) {
				com.narek.autosell.core.ContainerInspector.inspect();
				return true;
			}
		}
		if (!acceptsTrigger()) {
			TRIPLE.reset();
			return false;
		}
		if (key != AutoSellConfig.get().triggerKey) {
			TRIPLE.reset();
			return false;
		}
		if (TRIPLE.press()) {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.level == null) {
				return false;
			}
			if (FUEL.isRunning()) {
				chat(net.minecraft.network.chat.Component.literal(
						"AutoSell: fuel cycle is running - /autosell fuel stop first."));
				return false;
			}
			ProfitTracker.get().beginRun();
			ROUTINE.start();
			return true;
		}
		return false;
	}

	/**
	 * The trigger is only live in-world or with a container open.
	 *
	 * <p>Without this, typing "111" into chat or a sign - or rebinding the key
	 * on the config screen - would start a run. Restricting it to the two
	 * screens the routine actually supports keeps the default "1" usable as a
	 * normal hotbar key everywhere else.
	 */
	private static boolean acceptsTrigger() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return false;
		}
		Screen screen = mc.gui.screen();
		return screen == null || screen instanceof AbstractContainerScreen<?>;
	}
}
