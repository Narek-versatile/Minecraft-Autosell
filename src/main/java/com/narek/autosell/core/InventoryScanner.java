package com.narek.autosell.core;

import com.narek.autosell.config.AutoSellConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies inventory contents into sellable / protected / offending.
 *
 * <p>Slot policy, per the chosen configuration:
 * <ul>
 *   <li>Hotbar (0-8) - always protected. Your tools and food live here.</li>
 *   <li>Armor + offhand - always protected; they are equipment slots and are
 *       not part of {@link Inventory#getNonEquipmentItems()} at all.</li>
 *   <li>Main storage (9-35) - checked. Anything here that is not on the
 *       sell-list is an offender and blocks the sale.</li>
 * </ul>
 */
public final class InventoryScanner {
	/** First main-storage slot; everything below this is hotbar. */
	public static final int MAIN_START = 9;
	/** One past the last main-storage slot. */
	public static final int MAIN_END = 36;

	private InventoryScanner() {
	}

	public static String idOf(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	public static boolean isSellable(ItemStack stack) {
		String id = idOf(stack);
		return id != null && AutoSellConfig.get().sellList.contains(id);
	}

	/** Gear: never deposited, never treated as trash, never expected to sell. */
	public static boolean isKeep(ItemStack stack) {
		String id = idOf(stack);
		return id != null && AutoSellConfig.get().keepList.contains(id);
	}

	/**
	 * Everything the dump trip should deposit: any non-gear item in the 36
	 * inventory slots. Used by the full drain; armor and offhand are equipment
	 * slots and are never included.
	 *
	 * @param keepSellable when true, sell-list items are left alone; normally
	 *                     false, so a dump clears the inventory outright
	 */
	public static List<Integer> findDepositSlots(Player player, boolean keepSellable) {
		List<Integer> slots = new ArrayList<>();
		Inventory inv = player.getInventory();
		for (int slot = 0; slot < MAIN_END; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (isKeep(stack)) {
				continue;
			}
			if (keepSellable && isSellable(stack)) {
				continue;
			}
			slots.add(slot);
		}
		return slots;
	}

	/** An offending stack: sits in checked storage but is not on the sell-list. */
	public record Offender(int slot, String itemId, int count) {
	}

	/**
	 * Scans the checked region (main storage only) for anything not on the
	 * sell-list. The hotbar is deliberately skipped.
	 */
	public static List<Offender> findOffenders(Player player) {
		List<Offender> found = new ArrayList<>();
		Inventory inv = player.getInventory();

		for (int slot = MAIN_START; slot < MAIN_END; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (!isSellable(stack) && !isKeep(stack)) {
				found.add(new Offender(slot, idOf(stack), stack.getCount()));
			}
		}
		return found;
	}

	/** True when no main-storage slot holds anything at all. */
	public static boolean isStorageEmpty(Player player) {
		Inventory inv = player.getInventory();
		for (int slot = MAIN_START; slot < MAIN_END; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack != null && !stack.isEmpty()) {
				return false;
			}
		}
		return true;
	}

	/** Number of completely empty main-storage slots. */
	public static int freeStorageSlots(Player player) {
		Inventory inv = player.getInventory();
		int free = 0;
		for (int slot = MAIN_START; slot < MAIN_END; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack == null || stack.isEmpty()) {
				free++;
			}
		}
		return free;
	}

	/**
	 * Whether {@code stack} could still be absorbed by main storage - either an
	 * empty slot, or an existing partial stack of the same item.
	 */
	public static boolean hasRoomFor(Player player, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		Inventory inv = player.getInventory();
		for (int slot = MAIN_START; slot < MAIN_END; slot++) {
			ItemStack existing = inv.getItem(slot);
			if (existing == null || existing.isEmpty()) {
				return true;
			}
			if (ItemStack.isSameItemSameComponents(existing, stack)
					&& existing.getCount() < existing.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	/** Total items sitting in main storage, for the profit log. */
	public static int countStorageItems(Player player) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int slot = MAIN_START; slot < MAIN_END; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack != null && !stack.isEmpty()) {
				total += stack.getCount();
			}
		}
		return total;
	}
}
