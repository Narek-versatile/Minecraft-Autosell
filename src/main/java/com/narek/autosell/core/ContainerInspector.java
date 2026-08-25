package com.narek.autosell.core;

import com.narek.autosell.AutoSellClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Dumps the open container's slots to chat and to a file.
 *
 * <p>Exists so server GUI layouts can be read from live data instead of
 * guessed from screenshots - a mis-click in a shop spends real money, so the
 * slot indices behind any automated click path should be verified, not assumed.
 */
public final class ContainerInspector {

	private static final Path DUMP =
			FabricLoader.getInstance().getConfigDir().resolve("autosell-guidump.txt");

	private ContainerInspector() {
	}

	public static Path dumpPath() {
		return DUMP;
	}

	/** @return number of non-empty slots reported, or -1 if no container open */
	public static int inspect() {
		Minecraft mc = Minecraft.getInstance();
		Screen screen = mc.gui.screen();
		if (!(screen instanceof AbstractContainerScreen<?> container)) {
			AutoSellClient.chat(Component.literal(
							"AutoSell: no container GUI open. Open the shop screen first.")
					.withStyle(ChatFormatting.RED));
			return -1;
		}

		AbstractContainerMenu menu = container.getMenu();
		String title = screen.getTitle().getString();
		int total = menu.slots.size();
		int topSlots = Math.max(0, total - 36);

		StringBuilder file = new StringBuilder();
		file.append("=== ").append(LocalDateTime.now()).append(" ===\n");
		file.append("title: ").append(title).append('\n');
		file.append("menuType: ").append(safeType(menu)).append('\n');
		file.append("totalSlots: ").append(total)
				.append("  containerSlots: ").append(topSlots)
				.append("  rows: ").append(topSlots / 9).append('\n');

		AutoSellClient.chat(Component.literal("=== GUI: " + title + " ===")
				.withStyle(ChatFormatting.AQUA));
		AutoSellClient.chat(Component.literal(
						"container slots: " + topSlots + " (" + (topSlots / 9) + " rows), total "
								+ total)
				.withStyle(ChatFormatting.GRAY));

		int found = 0;
		for (int i = 0; i < topSlots; i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			found++;
			int row = i / 9;
			int col = i % 9;
			String id = InventoryScanner.idOf(stack);
			String name = stack.getHoverName().getString();

			String line = "slot " + i + " (r" + row + ",c" + col + ") x" + stack.getCount()
					+ "  " + id + "  \"" + name + "\"";
			file.append(line).append('\n');

			// Lore carries the shop's real semantics ("Click to buy stacks").
			for (String lore : LoreReader.read(stack)) {
				file.append("        | ").append(lore).append('\n');
			}

			AutoSellClient.chat(Component.literal(line).withStyle(ChatFormatting.WHITE));
		}

		file.append("--- end (").append(found).append(" filled slots) ---\n\n");
		write(file.toString());

		AutoSellClient.chat(Component.literal(
						found + " filled slot(s). Full dump with lore: " + DUMP)
				.withStyle(ChatFormatting.GREEN));
		return found;
	}

	private static String safeType(AbstractContainerMenu menu) {
		try {
			return String.valueOf(menu.getType());
		} catch (UnsupportedOperationException e) {
			// Server-driven menus can refuse to report a type.
			return "<none>";
		}
	}

	private static void write(String text) {
		try {
			Files.createDirectories(DUMP.getParent());
			Files.writeString(DUMP, text, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException e) {
			AutoSellClient.LOGGER.error("[autosell] could not write GUI dump", e);
		}
	}
}
