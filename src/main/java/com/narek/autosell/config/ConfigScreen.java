package com.narek.autosell.config;

import com.narek.autosell.core.InventoryScanner;
import com.narek.autosell.profit.ProfitTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * Settings screen. Deliberately plain: toggles, a key-capture row and the
 * sell-list, so the mod is usable without touching the JSON file.
 */
public class ConfigScreen extends Screen {
	private static final int ROW = 24;
	private static final int BTN_W = 200;
	private static final int BTN_H = 20;

	private final Screen parent;

	private boolean capturingKey;
	private Button keyButton;

	public ConfigScreen(Screen parent) {
		super(Component.literal("AutoSell Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		AutoSellConfig cfg = AutoSellConfig.get();
		int cx = this.width / 2 - BTN_W / 2;
		int y = 40;

		keyButton = addRenderableWidget(Button.builder(keyLabel(cfg), b -> {
			capturingKey = true;
			b.setMessage(Component.literal("Press any key..."));
		}).bounds(cx, y, BTN_W, BTN_H).build());
		y += ROW;

		addRenderableWidget(Button.builder(
				Component.literal("Triple-press window: " + cfg.tripleWindowMs + " ms"),
				b -> {
					cfg.tripleWindowMs = cycle(cfg.tripleWindowMs, 100, 1500, 100);
					cfg.save();
					b.setMessage(Component.literal(
							"Triple-press window: " + cfg.tripleWindowMs + " ms"));
				}).bounds(cx, y, BTN_W, BTN_H).build());
		y += ROW;

		addRenderableWidget(Button.builder(
				Component.literal("Click delay: " + cfg.clickDelayTicks + " ticks"),
				b -> {
					cfg.clickDelayTicks = cycle(cfg.clickDelayTicks, 0, 10, 1);
					cfg.save();
					b.setMessage(Component.literal(
							"Click delay: " + cfg.clickDelayTicks + " ticks"));
				}).bounds(cx, y, BTN_W, BTN_H).build());
		y += ROW;

		addRenderableWidget(Button.builder(
				Component.literal("Loop until chest empty: " + onOff(cfg.loopUntilEmpty)),
				b -> {
					cfg.loopUntilEmpty = !cfg.loopUntilEmpty;
					cfg.save();
					b.setMessage(Component.literal(
							"Loop until chest empty: " + onOff(cfg.loopUntilEmpty)));
				}).bounds(cx, y, BTN_W, BTN_H).build());
		y += ROW;

		addRenderableWidget(Button.builder(
				Component.literal("HUD overlay: " + onOff(cfg.hudEnabled)),
				b -> {
					cfg.hudEnabled = !cfg.hudEnabled;
					cfg.save();
					b.setMessage(Component.literal("HUD overlay: " + onOff(cfg.hudEnabled)));
				}).bounds(cx, y, BTN_W, BTN_H).build());
		y += ROW;

		addRenderableWidget(Button.builder(
				Component.literal("CSV log: " + onOff(cfg.csvLogEnabled)),
				b -> {
					cfg.csvLogEnabled = !cfg.csvLogEnabled;
					cfg.save();
					b.setMessage(Component.literal("CSV log: " + onOff(cfg.csvLogEnabled)));
				}).bounds(cx, y, BTN_W, BTN_H).build());
		y += ROW;

		addRenderableWidget(Button.builder(
				Component.literal("Add held item to sell-list"),
				b -> {
					if (minecraft == null || minecraft.player == null) {
						return;
					}
					ItemStack held = minecraft.player.getInventory().getSelectedItem();
					String id = InventoryScanner.idOf(held);
					if (id != null && cfg.sellList.add(id)) {
						cfg.save();
						b.setMessage(Component.literal("Added: " + id));
					} else {
						b.setMessage(Component.literal(
								id == null ? "Hold an item first" : "Already listed"));
					}
				}).bounds(cx, y, BTN_W, BTN_H).build());
		y += ROW;

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(cx, y + 6, BTN_W, BTN_H).build());
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
		if (capturingKey) {
			AutoSellConfig cfg = AutoSellConfig.get();
			if (event.key() != GLFW.GLFW_KEY_ESCAPE) {
				cfg.triggerKey = event.key();
				cfg.save();
			}
			capturingKey = false;
			if (keyButton != null) {
				keyButton.setMessage(keyLabel(cfg));
			}
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		graphics.centeredText(font, title, this.width / 2, 16, 0xFFFFFFFF);

		AutoSellConfig cfg = AutoSellConfig.get();
		int bottom = this.height - 40;

		graphics.centeredText(font, Component.literal(
						"Sell-list: " + cfg.sellList.size() + " item(s)   |   Session: "
								+ ProfitTracker.get().formatSessionTotal()),
				this.width / 2, bottom, 0xFFAAAAAA);

		String dump = cfg.hasDumpChest()
				? "Dump chest: " + cfg.dumpX + ", " + cfg.dumpY + ", " + cfg.dumpZ
				: "Dump chest: not set (/autosell dump set)";
		graphics.centeredText(font, Component.literal(dump),
				this.width / 2, bottom + 12, 0xFF888888);
	}

	@Override
	public void onClose() {
		AutoSellConfig.get().save();
		if (minecraft != null) {
			minecraft.gui.setScreen(parent);
		}
	}

	private static Component keyLabel(AutoSellConfig cfg) {
		return Component.literal("Trigger key: " + keyName(cfg.triggerKey) + " (x3)");
	}

	private static String keyName(int glfw) {
		if (glfw >= GLFW.GLFW_KEY_0 && glfw <= GLFW.GLFW_KEY_9) {
			return String.valueOf((char) ('0' + (glfw - GLFW.GLFW_KEY_0)));
		}
		if (glfw >= GLFW.GLFW_KEY_A && glfw <= GLFW.GLFW_KEY_Z) {
			return String.valueOf((char) ('A' + (glfw - GLFW.GLFW_KEY_A)));
		}
		String named = GLFW.glfwGetKeyName(glfw, 0);
		return named != null ? named.toUpperCase() : ("key " + glfw);
	}

	private static String onOff(boolean value) {
		return value ? "ON" : "OFF";
	}

	private static int cycle(int current, int min, int max, int step) {
		int next = current + step;
		return next > max ? min : next;
	}
}
