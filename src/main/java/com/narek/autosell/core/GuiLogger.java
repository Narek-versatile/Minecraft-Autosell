package com.narek.autosell.core;

import com.narek.autosell.AutoSellClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Passively records every server GUI that opens.
 *
 * <p>Chat is unreachable while a container screen has focus, so an on-demand
 * inspect command cannot be run at the moment it is needed. This watches
 * instead: whenever the open container's title or contents change, the whole
 * screen is appended to a log file. Play normally, then read the file.
 */
public final class GuiLogger {

	private static final Path LOG =
			FabricLoader.getInstance().getConfigDir().resolve("autosell-guidump.txt");
	private static final DateTimeFormatter STAMP =
			DateTimeFormatter.ofPattern("HH:mm:ss");

	/** Contents arrive after the screen opens, so settle before snapshotting. */
	private static final int SETTLE_TICKS = 10;
	private static final int PLAYER_SLOTS_IN_MENU = 36;

	private static boolean enabled = true;
	private static String lastSignature = "";
	private static int settle;

	private GuiLogger() {
	}

	public static Path logPath() {
		return LOG;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean toggle() {
		enabled = !enabled;
		lastSignature = "";
		return enabled;
	}

	public static void tick() {
		if (!enabled) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.gui.screen();
		if (!(screen instanceof AbstractContainerScreen<?> container)) {
			lastSignature = "";
			settle = 0;
			return;
		}
		AbstractContainerMenu menu = container.getMenu();
		if (menu == null || mc.player == null || menu == mc.player.inventoryMenu) {
			return;
		}

		String signature = signature(screen.getTitle().getString(), menu);
		if (signature.equals(lastSignature)) {
			settle = 0;
			return;
		}
		// Wait for the contents to stop changing before writing a snapshot.
		if (++settle < SETTLE_TICKS) {
			return;
		}
		settle = 0;
		lastSignature = signature;
		write(render(screen.getTitle().getString(), menu));
	}

	private static String signature(String title, AbstractContainerMenu menu) {
		StringBuilder sb = new StringBuilder(title).append('|');
		int top = Math.max(0, menu.slots.size() - PLAYER_SLOTS_IN_MENU);
		for (int i = 0; i < top; i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack != null && !stack.isEmpty()) {
				sb.append(i).append(':')
						.append(InventoryScanner.idOf(stack)).append('x')
						.append(stack.getCount()).append(';');
			}
		}
		return sb.toString();
	}

	/** Full human-readable snapshot, including lore. */
	public static String render(String title, AbstractContainerMenu menu) {
		int total = menu.slots.size();
		int top = Math.max(0, total - PLAYER_SLOTS_IN_MENU);

		StringBuilder sb = new StringBuilder();
		sb.append("\n=== ").append(LocalDateTime.now().format(STAMP))
				.append("  TITLE: ").append(title).append(" ===\n");
		sb.append("containerSlots: ").append(top)
				.append("  rows: ").append(top / 9)
				.append("  totalWithPlayer: ").append(total).append('\n');

		for (int i = 0; i < top; i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			sb.append(String.format("slot %-3d (r%d,c%d) x%-3d %-34s %s%n",
					i, i / 9, i % 9, stack.getCount(),
					InventoryScanner.idOf(stack),
					"\"" + stack.getHoverName().getString() + "\""));
			for (String lore : LoreReader.read(stack)) {
				sb.append("            | ").append(lore).append('\n');
			}
		}
		sb.append("=== end ===\n");
		return sb.toString();
	}

	private static void write(String text) {
		try {
			Files.createDirectories(LOG.getParent());
			Files.writeString(LOG, text, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			AutoSellClient.LOGGER.error("[autosell] could not write GUI log", e);
		}
	}
}
