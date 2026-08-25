package com.narek.autosell.core;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

/** Plain-text lore lines of a stack, formatting stripped. */
public final class LoreReader {

	private LoreReader() {
	}

	public static List<String> read(ItemStack stack) {
		List<String> out = new ArrayList<>();
		if (stack == null || stack.isEmpty()) {
			return out;
		}
		ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
		for (Component line : lore.lines()) {
			out.add(line.getString());
		}
		return out;
	}

	/** True when any lore line contains {@code needle}, case-insensitively. */
	public static boolean loreContains(ItemStack stack, String needle) {
		String lower = needle.toLowerCase();
		for (String line : read(stack)) {
			if (line.toLowerCase().contains(lower)) {
				return true;
			}
		}
		return false;
	}
}
