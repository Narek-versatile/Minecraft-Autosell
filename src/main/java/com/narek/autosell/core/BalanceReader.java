package com.narek.autosell.core;

import com.narek.autosell.config.AutoSellConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the player's balance off the sidebar scoreboard.
 *
 * <p>Passive on purpose - no command is sent to ask for it. The sidebar line is
 * matched by label ("BALANCE") and the first money-looking number on that line
 * is taken as the value.
 */
public final class BalanceReader {

	private static final Pattern MONEY =
			Pattern.compile("\\$?\\s*([\\d,]+(?:\\.\\d+)?)");

	private BalanceReader() {
	}

	/**
	 * Balance from whichever source is available.
	 *
	 * <p>Prefers an open shop screen: PlanetPVP puts an exact
	 * "Balance: $439,036.80" on the player-head slot, which beats the sidebar's
	 * abbreviated rendering.
	 */
	public static double read() {
		double fromShop = readFromOpenContainer();
		if (!Double.isNaN(fromShop)) {
			return fromShop;
		}
		return readFromSidebar();
	}

	/** Scans an open container's lore for a "Balance: $..." line. */
	public static double readFromOpenContainer() {
		Minecraft mc = Minecraft.getInstance();
		net.minecraft.client.gui.screens.Screen screen = mc.gui.screen();
		if (!(screen instanceof net.minecraft.client.gui.screens.inventory
				.AbstractContainerScreen<?> container)) {
			return Double.NaN;
		}
		net.minecraft.world.inventory.AbstractContainerMenu menu = container.getMenu();
		if (menu == null || mc.player == null || menu == mc.player.inventoryMenu) {
			return Double.NaN;
		}
		int top = Math.max(0, menu.slots.size() - 36);
		for (int i = 0; i < top; i++) {
			net.minecraft.world.item.ItemStack stack = menu.slots.get(i).getItem();
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			for (String line : LoreReader.read(stack)) {
				if (!line.toLowerCase().contains("balance")) {
					continue;
				}
				Double v = firstMoney(line);
				if (v != null) {
					return v;
				}
			}
		}
		return Double.NaN;
	}

	/** @return the balance, or NaN when the sidebar has no matching line */
	public static double readFromSidebar() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return Double.NaN;
		}
		Scoreboard board = mc.level.getScoreboard();
		Objective sidebar = board.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (sidebar == null) {
			return Double.NaN;
		}
		String label = AutoSellConfig.get().balanceLabel.toLowerCase();

		for (PlayerScoreEntry entry : board.listPlayerScores(sidebar)) {
			if (entry.isHidden()) {
				continue;
			}
			// ownerName() carries the team prefix/suffix the server renders,
			// which is where these sidebars put both label and value.
			String line = entry.ownerName().getString();
			if (!line.toLowerCase().contains(label)) {
				continue;
			}
			Double value = firstMoney(stripLabel(line, label));
			if (value != null) {
				return value;
			}
			// Some servers put the label on the line and the number in the score.
			return entry.value();
		}
		return Double.NaN;
	}

	private static String stripLabel(String line, String lowerLabel) {
		int idx = line.toLowerCase().indexOf(lowerLabel);
		return idx < 0 ? line : line.substring(idx + lowerLabel.length());
	}

	private static Double firstMoney(String text) {
		Matcher m = MONEY.matcher(text);
		while (m.find()) {
			String raw = m.group(1).replace(",", "");
			if (raw.isEmpty()) {
				continue;
			}
			try {
				return Double.parseDouble(raw);
			} catch (NumberFormatException ignored) {
				// keep scanning
			}
		}
		return null;
	}

	public static boolean available() {
		return !Double.isNaN(read());
	}
}
