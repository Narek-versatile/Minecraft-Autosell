package com.narek.autosell.core;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds shop entries by what they say, not by where they sit.
 *
 * <p>Fixed slot indices proved unsafe: indices read off a screenshot resolved
 * to obsidian instead of bone. Display names are what the player actually reads,
 * so they are matched instead, and an ambiguous or missing match refuses to
 * click rather than guessing.
 */
public final class ShopNavigator {

	private static final int PLAYER_SLOTS_IN_MENU = 36;

	public record Match(int slot, String name, String reason) {
	}

	public record Failure(String reason, List<String> candidates) {
	}

	public record Result(Match match, Failure failure) {
		public boolean ok() {
			return match != null;
		}
	}

	private ShopNavigator() {
	}

	public static int containerSlots(AbstractContainerMenu menu) {
		return Math.max(0, menu.slots.size() - PLAYER_SLOTS_IN_MENU);
	}

	/** Delegates to {@link TextFold}, which handles the server's small caps. */
	public static String normalize(String raw) {
		return TextFold.normalize(raw);
	}

	/**
	 * Resolves {@code target} within the container.
	 *
	 * <p>Tiers, strictest first: exact name, then name-contains, then
	 * lore-contains. A tier producing exactly one hit wins; more than one is
	 * ambiguous and fails rather than picking arbitrarily.
	 */
	public static Result find(AbstractContainerMenu menu, String target) {
		String want = normalize(target);
		if (want.isEmpty()) {
			return new Result(null, new Failure("empty search term", List.of()));
		}
		int top = containerSlots(menu);

		List<Integer> exact = new ArrayList<>();
		List<Integer> nameContains = new ArrayList<>();
		List<Integer> loreContains = new ArrayList<>();
		List<String> present = new ArrayList<>();

		for (int i = 0; i < top; i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String name = normalize(stack.getHoverName().getString());
			present.add(i + ":" + name);

			if (name.equals(want)) {
				exact.add(i);
			} else if (name.contains(want)) {
				nameContains.add(i);
			} else {
				for (String line : LoreReader.read(stack)) {
					if (normalize(line).contains(want)) {
						loreContains.add(i);
						break;
					}
				}
			}
		}

		Result tier = pick(menu, exact, "exact name", want, present);
		if (tier != null) {
			return tier;
		}
		tier = pick(menu, nameContains, "name contains", want, present);
		if (tier != null) {
			return tier;
		}
		tier = pick(menu, loreContains, "lore contains", want, present);
		if (tier != null) {
			return tier;
		}
		return new Result(null, new Failure("no slot matching \"" + want + "\"", present));
	}

	private static Result pick(AbstractContainerMenu menu, List<Integer> hits,
			String reason, String want, List<String> present) {
		if (hits.isEmpty()) {
			return null;
		}
		if (hits.size() > 1) {
			List<String> which = new ArrayList<>();
			for (int slot : hits) {
				which.add(slot + ":"
						+ normalize(menu.slots.get(slot).getItem().getHoverName().getString()));
			}
			return new Result(null, new Failure(
					"\"" + want + "\" is ambiguous by " + reason + " (" + hits.size()
							+ " matches)", which));
		}
		int slot = hits.get(0);
		String name = menu.slots.get(slot).getItem().getHoverName().getString();
		return new Result(new Match(slot, name, reason), null);
	}

	/** Every filled slot as "index:NAME", for reporting a failure usefully. */
	public static List<String> describe(AbstractContainerMenu menu) {
		List<String> out = new ArrayList<>();
		int top = containerSlots(menu);
		for (int i = 0; i < top; i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack != null && !stack.isEmpty()) {
				out.add(i + ":" + normalize(stack.getHoverName().getString()));
			}
		}
		return out;
	}
}
