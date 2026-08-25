package com.narek.autosell.fuel;

import com.narek.autosell.AutoSellClient;
import com.narek.autosell.config.AutoSellConfig;
import com.narek.autosell.core.InventoryScanner;
import com.narek.autosell.core.TextFold;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Captures the player's shop clicks and works out what each one did.
 *
 * <p>After every click the screen title and the amount counter are watched for
 * a few ticks. A title change means navigation; a counter change means a
 * quantity button, and the size of the change is stored as that button's step.
 * That is what makes replay able to buy any amount instead of repeating the
 * exact sequence that happened to be recorded.
 */
public final class ShopRecorder {

	private static final int PLAYER_SLOTS_IN_MENU = 36;
	/** Ticks to wait for a click's effect before calling it navigation. */
	private static final int SETTLE_TICKS = 20;

	private static boolean recording;
	private static ShopPath path = new ShopPath();

	private static ShopPath.Step pending;
	private static String titleBefore = "";
	private static int countBefore = -1;
	private static int settle;

	private ShopRecorder() {
	}

	public static boolean isRecording() {
		return recording;
	}

	public static void arm() {
		recording = true;
		path = new ShopPath();
		pending = null;
		settle = 0;
		AutoSellClient.chat(Component.literal("Recording shop path.")
				.withStyle(ChatFormatting.AQUA));
		AutoSellClient.chat(Component.literal(
						"  Open /shop, then click: category -> item -> buy stacks -> "
								+ "the amount buttons -> purchase. Press Esc when done.")
				.withStyle(ChatFormatting.GRAY));
		AutoSellClient.chat(Component.literal(
						"  Click each different amount button at least once - the mod learns "
								+ "what each is worth and picks them itself later.")
				.withStyle(ChatFormatting.GRAY));
	}

	public static void cancel() {
		recording = false;
		pending = null;
		path = new ShopPath();
		AutoSellClient.chat(Component.literal("Shop recording cancelled.")
				.withStyle(ChatFormatting.YELLOW));
	}

	/** Called from the container screen mixin on every player click. */
	public static void record(String screenTitle, int slot, int button, ItemStack stack) {
		if (!recording || stack == null || stack.isEmpty() || slot < 0) {
			return;
		}
		// Any earlier click that never showed an effect was navigation.
		classifyPending(null, -1);

		ShopPath.Step step = new ShopPath.Step();
		step.screenTitle = TextFold.normalize(screenTitle);
		step.slot = slot;
		step.button = button;
		step.itemId = String.valueOf(InventoryScanner.idOf(stack));
		step.itemNameRaw = stack.getHoverName().getString();
		step.itemName = TextFold.normalize(step.itemNameRaw);

		pending = step;
		titleBefore = step.screenTitle;
		countBefore = readCounter();
		settle = 0;
	}

	/** Watches for the effect of the last click. */
	public static void tick() {
		if (!recording || pending == null) {
			return;
		}
		String title = currentTitle();
		int count = readCounter();

		if (!title.equals(titleBefore)) {
			classifyPending(ShopPath.Kind.NAVIGATE, 0);
			return;
		}
		if (countBefore >= 0 && count >= 0 && count != countBefore) {
			classifyPending(ShopPath.Kind.ADJUST, count - countBefore);
			return;
		}
		if (++settle > SETTLE_TICKS) {
			classifyPending(ShopPath.Kind.NAVIGATE, 0);
		}
	}

	private static void classifyPending(ShopPath.Kind kind, int delta) {
		if (pending == null) {
			return;
		}
		ShopPath.Step step = pending;
		pending = null;
		settle = 0;

		step.kind = kind == null ? ShopPath.Kind.NAVIGATE : kind;
		step.delta = delta;

		// Re-clicking a button we already learned adds nothing.
		if (step.kind == ShopPath.Kind.ADJUST) {
			for (ShopPath.Step known : path.steps) {
				if (known.kind == ShopPath.Kind.ADJUST && known.slot == step.slot) {
					known.delta = step.delta;
					AutoSellClient.chat(Component.literal(
									"  amount button \"" + known.label() + "\" = "
											+ (step.delta > 0 ? "+" : "") + step.delta + " stacks")
							.withStyle(ChatFormatting.DARK_GRAY));
					return;
				}
			}
		}
		path.steps.add(step);
		AutoSellClient.chat(Component.literal("  " + path.size() + ". " + step.describe())
				.withStyle(ChatFormatting.DARK_GRAY));
	}

	/** Called when the container closes; the final click is the purchase. */
	public static void finish() {
		if (!recording) {
			return;
		}
		classifyPending(null, 0);
		recording = false;

		if (path.isEmpty()) {
			AutoSellClient.chat(Component.literal(
							"Shop recording ended with no clicks - nothing saved.")
					.withStyle(ChatFormatting.RED));
			return;
		}

		// The last non-adjust click is what completed the purchase.
		for (int i = path.steps.size() - 1; i >= 0; i--) {
			if (path.steps.get(i).kind != ShopPath.Kind.ADJUST) {
				path.steps.get(i).kind = ShopPath.Kind.PURCHASE;
				break;
			}
		}
		path.save();
		report();
	}

	private static void report() {
		AutoSellClient.chat(Component.literal("Shop path saved:")
				.withStyle(ChatFormatting.GREEN));
		for (ShopPath.Step s : path.steps) {
			ChatFormatting colour = switch (s.kind) {
				case PURCHASE -> ChatFormatting.AQUA;
				case ADJUST -> ChatFormatting.YELLOW;
				case NAVIGATE -> ChatFormatting.WHITE;
			};
			AutoSellClient.chat(Component.literal("  " + s.describe()).withStyle(colour));
		}
		int adjusters = path.adjusters().size();
		if (adjusters == 0) {
			AutoSellClient.chat(Component.literal(
							"  No amount buttons learned - it will buy whatever the screen "
									+ "defaults to. Re-record and click them to fix that.")
					.withStyle(ChatFormatting.YELLOW));
		} else {
			AutoSellClient.chat(Component.literal(
							"  " + adjusters + " amount button(s) learned - the amount is now "
									+ "chosen from your free space each cycle.")
					.withStyle(ChatFormatting.GRAY));
		}
		if (path.confirmStep() == null) {
			AutoSellClient.chat(Component.literal("  No purchase step found - re-record.")
					.withStyle(ChatFormatting.RED));
		}
	}

	// -- live readings -----------------------------------------------------

	private static AbstractContainerMenu openMenu() {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.gui.screen();
		if (!(screen instanceof AbstractContainerScreen<?> container)) {
			return null;
		}
		AbstractContainerMenu menu = container.getMenu();
		if (menu == null || mc.player == null || menu == mc.player.inventoryMenu) {
			return null;
		}
		return menu;
	}

	private static String currentTitle() {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.gui.screen();
		return screen == null ? "" : TextFold.normalize(screen.getTitle().getString());
	}

	/** Stack count of the item being bought, which is the amount counter. */
	private static int readCounter() {
		AbstractContainerMenu menu = openMenu();
		if (menu == null) {
			return -1;
		}
		String wanted = AutoSellConfig.get().fuelSourceItem;
		int top = Math.max(0, menu.slots.size() - PLAYER_SLOTS_IN_MENU);
		for (int i = 0; i < top; i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack != null && !stack.isEmpty()
					&& wanted.equals(InventoryScanner.idOf(stack))) {
				return stack.getCount();
			}
		}
		return -1;
	}
}
