package com.narek.autosell.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.narek.autosell.AutoSellClient;
import com.narek.autosell.config.AutoSellConfig;
import com.narek.autosell.config.ConfigScreen;
import com.narek.autosell.core.ContainerInspector;
import com.narek.autosell.core.InventoryScanner;
import com.narek.autosell.profit.ProfitTracker;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

/**
 * {@code /autosell ...} - all client-side; these never reach the server.
 */
public final class AutoSellCommands {

	private AutoSellCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
				dispatcher.register(ClientCommands.literal("autosell")
						.executes(ctx -> {
							openConfig();
							return 1;
						})
						.then(ClientCommands.literal("config")
								.executes(ctx -> {
									openConfig();
									return 1;
								}))
						.then(ClientCommands.literal("add")
								.executes(ctx -> addHeld()))
						.then(ClientCommands.literal("remove")
								.executes(ctx -> removeHeld())
								.then(ClientCommands.argument("item", StringArgumentType.string())
										.executes(ctx -> removeById(
												StringArgumentType.getString(ctx, "item")))))
						.then(ClientCommands.literal("list")
								.executes(ctx -> listItems()))
						.then(ClientCommands.literal("clear")
								.executes(ctx -> clearList()))
						.then(ClientCommands.literal("dump")
								.then(ClientCommands.literal("set")
										.executes(ctx -> setDumpChest()))
								.then(ClientCommands.literal("clear")
										.executes(ctx -> {
											AutoSellConfig.get().clearDumpChest();
											feedback("Dump chest cleared.", ChatFormatting.YELLOW);
											return 1;
										})))
						.then(ClientCommands.literal("key")
								.then(ClientCommands.argument("glfw", IntegerArgumentType.integer(0))
										.executes(ctx -> setKey(
												IntegerArgumentType.getInteger(ctx, "glfw")))))
						.then(ClientCommands.literal("window")
								.then(ClientCommands.argument("ms", IntegerArgumentType.integer(100, 3000))
										.executes(ctx -> {
											AutoSellConfig cfg = AutoSellConfig.get();
											cfg.tripleWindowMs = IntegerArgumentType.getInteger(ctx, "ms");
											cfg.save();
											feedback("Triple-press window: " + cfg.tripleWindowMs + " ms",
													ChatFormatting.GREEN);
											return 1;
										})))
						.then(ClientCommands.literal("delay")
								.then(ClientCommands.argument("ticks", IntegerArgumentType.integer(0, 20))
										.executes(ctx -> {
											AutoSellConfig cfg = AutoSellConfig.get();
											cfg.clickDelayTicks = IntegerArgumentType.getInteger(ctx, "ticks");
											cfg.save();
											feedback("Click delay: " + cfg.clickDelayTicks + " ticks",
													ChatFormatting.GREEN);
											return 1;
										})))
						.then(ClientCommands.literal("command")
								.then(ClientCommands.argument("cmd", StringArgumentType.greedyString())
										.executes(ctx -> {
											AutoSellConfig cfg = AutoSellConfig.get();
											cfg.sellCommand = StringArgumentType.getString(ctx, "cmd")
													.replaceAll("^/", "");
											cfg.save();
											feedback("Sell command: /" + cfg.sellCommand,
													ChatFormatting.GREEN);
											return 1;
										})))
						.then(ClientCommands.literal("regex")
								.then(ClientCommands.argument("pattern", StringArgumentType.greedyString())
										.executes(ctx -> {
											AutoSellConfig cfg = AutoSellConfig.get();
											cfg.sellRegex = StringArgumentType.getString(ctx, "pattern");
											cfg.save();
											feedback("Sell regex updated.", ChatFormatting.GREEN);
											return 1;
										})))
						.then(ClientCommands.literal("hud")
								.executes(ctx -> {
									AutoSellConfig cfg = AutoSellConfig.get();
									cfg.hudEnabled = !cfg.hudEnabled;
									cfg.save();
									feedback("HUD " + (cfg.hudEnabled ? "shown" : "hidden"),
											ChatFormatting.GREEN);
									return 1;
								}))
						.then(ClientCommands.literal("loop")
								.executes(ctx -> {
									AutoSellConfig cfg = AutoSellConfig.get();
									cfg.loopUntilEmpty = !cfg.loopUntilEmpty;
									cfg.save();
									feedback("Loop until chest empty: " + cfg.loopUntilEmpty,
											ChatFormatting.GREEN);
									return 1;
								}))
						.then(ClientCommands.literal("stats")
								.executes(ctx -> stats()))
						.then(ClientCommands.literal("reset")
								.executes(ctx -> {
									ProfitTracker.get().resetSession();
									feedback("Session profit reset.", ChatFormatting.YELLOW);
									return 1;
								}))
						.then(ClientCommands.literal("stop")
								.executes(ctx -> {
									if (AutoSellClient.routine().isRunning()) {
										AutoSellClient.routine().abort("Stopped by command.");
									} else {
										feedback("Not running.", ChatFormatting.GRAY);
									}
									return 1;
								}))
						.then(ClientCommands.literal("check")
								.executes(ctx -> check()))
						.then(ClientCommands.literal("inspect")
								.executes(ctx -> {
									ContainerInspector.inspect();
									return 1;
								}))
						.then(ClientCommands.literal("burst")
								.executes(ctx -> {
									AutoSellConfig cfg = AutoSellConfig.get();
									cfg.burstLoot = !cfg.burstLoot;
									cfg.save();
									feedback("Burst transfers: " + cfg.burstLoot,
											ChatFormatting.GREEN);
									return 1;
								}))
						.then(ClientCommands.literal("walk")
								.executes(ctx -> {
									AutoSellConfig cfg = AutoSellConfig.get();
									cfg.walkEnabled = !cfg.walkEnabled;
									cfg.save();
									feedback("Auto-walk to dump chest: " + cfg.walkEnabled,
											ChatFormatting.GREEN);
									return 1;
								}))
						.then(ClientCommands.literal("loot")
								.then(ClientCommands.literal("set")
										.executes(ctx -> setLootChest())))
						.then(ClientCommands.literal("fuel")
								.executes(ctx -> {
									AutoSellClient.fuel().start(false);
									return 1;
								})
								.then(ClientCommands.literal("test")
										.executes(ctx -> {
											AutoSellClient.fuel().start(true);
											return 1;
										}))
								.then(ClientCommands.literal("stop")
										.executes(ctx -> {
											AutoSellClient.fuel().abort("Stopped by command.");
											return 1;
										}))
								.then(ClientCommands.literal("record")
										.executes(ctx -> {
											com.narek.autosell.fuel.ShopRecorder.arm();
											return 1;
										})
										.then(ClientCommands.literal("cancel")
												.executes(ctx -> {
													com.narek.autosell.fuel.ShopRecorder.cancel();
													return 1;
												})))
								.then(ClientCommands.literal("path")
										.executes(ctx -> showPath()))
								.then(ClientCommands.literal("spot")
										.executes(ctx -> setFarmSpot()))
								.then(ClientCommands.literal("status")
										.executes(ctx -> fuelStatus()))
								.then(ClientCommands.literal("cycles")
										.then(ClientCommands.argument("n", IntegerArgumentType.integer(1, 200))
												.executes(ctx -> {
													AutoSellConfig cfg = AutoSellConfig.get();
													cfg.fuelMaxCycles = IntegerArgumentType.getInteger(ctx, "n");
													cfg.save();
													feedback("Fuel cycles: " + cfg.fuelMaxCycles,
															ChatFormatting.GREEN);
													return 1;
												})))
								.then(ClientCommands.literal("floor")
										.then(ClientCommands.argument("amount", IntegerArgumentType.integer(0))
												.executes(ctx -> {
													AutoSellConfig cfg = AutoSellConfig.get();
													cfg.fuelMoneyFloor = IntegerArgumentType.getInteger(ctx, "amount");
													cfg.save();
													feedback("Money floor: $" + cfg.fuelMoneyFloor,
															ChatFormatting.GREEN);
													return 1;
												})))
								.then(ClientCommands.literal("maxspend")
										.then(ClientCommands.argument("amount", IntegerArgumentType.integer(1))
												.executes(ctx -> {
													AutoSellConfig cfg = AutoSellConfig.get();
													cfg.fuelMaxSpend = IntegerArgumentType.getInteger(ctx, "amount");
													cfg.save();
													feedback("Max spend per purchase: $" + cfg.fuelMaxSpend,
															ChatFormatting.GREEN);
													return 1;
												})))
								.then(ClientCommands.literal("stacks")
										.then(ClientCommands.argument("n", IntegerArgumentType.integer(0, 64))
												.executes(ctx -> {
													AutoSellConfig cfg = AutoSellConfig.get();
													cfg.fuelFixedStacks = IntegerArgumentType.getInteger(ctx, "n");
													cfg.save();
													feedback(cfg.fuelFixedStacks == 0
																	? "Buy amount: auto (free slots - "
																			+ cfg.fuelReserveSlots + ")"
																	: "Buy amount: " + cfg.fuelFixedStacks + " stacks",
															ChatFormatting.GREEN);
													return 1;
												})))
								.then(ClientCommands.literal("reserve")
										.then(ClientCommands.argument("slots", IntegerArgumentType.integer(0, 20))
												.executes(ctx -> {
													AutoSellConfig cfg = AutoSellConfig.get();
													cfg.fuelReserveSlots = IntegerArgumentType.getInteger(ctx, "slots");
													cfg.save();
													feedback("Reserve slots: " + cfg.fuelReserveSlots,
															ChatFormatting.GREEN);
													return 1;
												}))))
						.then(ClientCommands.literal("debug")
								.executes(ctx -> {
									boolean on = com.narek.autosell.core.DebugLog.toggle();
									feedback("Debug log " + (on ? "ON" : "OFF"),
											ChatFormatting.GREEN);
									feedback("  " + com.narek.autosell.core.DebugLog.path(),
											ChatFormatting.GRAY);
									return 1;
								}))
						.then(ClientCommands.literal("guilog")
								.executes(ctx -> {
									boolean on = com.narek.autosell.core.GuiLogger.toggle();
									AutoSellConfig cfg = AutoSellConfig.get();
									cfg.guiLogging = on;
									cfg.save();
									feedback("GUI logging " + (on ? "ON" : "OFF") + " -> "
											+ com.narek.autosell.core.GuiLogger.logPath(),
											ChatFormatting.GREEN);
									return 1;
								}))
						.then(ClientCommands.literal("inspectkey")
								.then(ClientCommands.argument("glfw", IntegerArgumentType.integer(0))
										.executes(ctx -> {
											AutoSellConfig cfg = AutoSellConfig.get();
											cfg.inspectKey = IntegerArgumentType.getInteger(ctx, "glfw");
											cfg.save();
											feedback("Inspect key: GLFW " + cfg.inspectKey,
													ChatFormatting.GREEN);
											return 1;
										})))
						.then(ClientCommands.literal("keep")
								.then(ClientCommands.literal("add")
										.executes(ctx -> keepHeld(true)))
								.then(ClientCommands.literal("remove")
										.executes(ctx -> keepHeld(false)))
								.then(ClientCommands.literal("list")
										.executes(ctx -> keepList())))));
	}

	private static void openConfig() {
		Minecraft mc = Minecraft.getInstance();
		// Deferred: a screen cannot be opened from inside command execution.
		mc.execute(() -> mc.gui.setScreen(new ConfigScreen(null)));
	}

	private static int addHeld() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		ItemStack held = mc.player.getInventory().getSelectedItem();
		String id = InventoryScanner.idOf(held);
		if (id == null) {
			feedback("Hold the item you want to sell, then run this again.",
					ChatFormatting.RED);
			return 0;
		}
		AutoSellConfig cfg = AutoSellConfig.get();
		if (cfg.sellList.add(id)) {
			cfg.save();
			feedback("Added to sell-list: " + id, ChatFormatting.GREEN);
		} else {
			feedback("Already on the sell-list: " + id, ChatFormatting.GRAY);
		}
		return 1;
	}

	private static int removeHeld() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		String id = InventoryScanner.idOf(mc.player.getInventory().getSelectedItem());
		if (id == null) {
			feedback("Hold the item you want to remove.", ChatFormatting.RED);
			return 0;
		}
		return removeById(id);
	}

	private static int removeById(String id) {
		AutoSellConfig cfg = AutoSellConfig.get();
		if (cfg.sellList.remove(id)) {
			cfg.save();
			feedback("Removed from sell-list: " + id, ChatFormatting.YELLOW);
		} else {
			feedback("Not on the sell-list: " + id, ChatFormatting.GRAY);
		}
		return 1;
	}

	private static int listItems() {
		AutoSellConfig cfg = AutoSellConfig.get();
		if (cfg.sellList.isEmpty()) {
			feedback("Sell-list is empty. Hold an item and run /autosell add",
					ChatFormatting.YELLOW);
			return 1;
		}
		feedback("Sell-list (" + cfg.sellList.size() + "):", ChatFormatting.AQUA);
		for (String id : cfg.sellList) {
			feedback("  - " + id, ChatFormatting.WHITE);
		}
		return 1;
	}

	private static int clearList() {
		AutoSellConfig cfg = AutoSellConfig.get();
		cfg.sellList.clear();
		cfg.save();
		feedback("Sell-list cleared.", ChatFormatting.YELLOW);
		return 1;
	}

	private static int setDumpChest() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return 0;
		}
		if (!(mc.hitResult instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK) {
			feedback("Look at the chest you want to use as your dump chest.",
					ChatFormatting.RED);
			return 0;
		}
		BlockPos pos = hit.getBlockPos();
		String dim = mc.level.dimension().identifier().toString();
		AutoSellConfig.get().setDumpChest(pos.getX(), pos.getY(), pos.getZ(), dim);
		feedback("Dump chest set: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(),
				ChatFormatting.GREEN);
		return 1;
	}

	private static int setKey(int glfw) {
		AutoSellConfig cfg = AutoSellConfig.get();
		cfg.triggerKey = glfw;
		cfg.save();
		feedback("Trigger key set to GLFW code " + glfw
				+ " (" + describeKey(glfw) + "). Triple-press it to run.",
				ChatFormatting.GREEN);
		return 1;
	}

	private static int stats() {
		ProfitTracker p = ProfitTracker.get();
		feedback("Session: " + p.formatSessionTotal()
				+ " from " + p.formatSessionItems() + " items across "
				+ p.sessionSales() + " sale(s).", ChatFormatting.GREEN);
		feedback("Last sale: " + p.formatLast(), ChatFormatting.WHITE);
		if (AutoSellConfig.get().csvLogEnabled) {
			feedback("Log: " + p.csvPath(), ChatFormatting.GRAY);
		}
		return 1;
	}

	private static int check() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		var offenders = InventoryScanner.findOffenders(mc.player);
		if (offenders.isEmpty()) {
			feedback("Storage is clean - " + InventoryScanner.countStorageItems(mc.player)
					+ " sellable item(s) ready.", ChatFormatting.GREEN);
		} else {
			feedback(offenders.size() + " unwanted stack(s):", ChatFormatting.RED);
			for (var o : offenders) {
				feedback("  slot " + o.slot() + ": " + o.itemId() + " x" + o.count(),
						ChatFormatting.YELLOW);
			}
		}
		return 1;
	}

	private static int setLootChest() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		if (!(mc.hitResult instanceof BlockHitResult hit)
				|| hit.getType() != HitResult.Type.BLOCK) {
			feedback("Look at the chest you loot from.", ChatFormatting.RED);
			return 0;
		}
		BlockPos pos = hit.getBlockPos();
		AutoSellConfig.get().setLootChest(pos.getX(), pos.getY(), pos.getZ());
		feedback("Loot chest set: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(),
				ChatFormatting.GREEN);
		return 1;
	}

	private static int keepHeld(boolean add) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		String id = InventoryScanner.idOf(mc.player.getInventory().getSelectedItem());
		if (id == null) {
			feedback("Hold the gear item first.", ChatFormatting.RED);
			return 0;
		}
		AutoSellConfig cfg = AutoSellConfig.get();
		boolean changed = add ? cfg.keepList.add(id) : cfg.keepList.remove(id);
		if (changed) {
			cfg.save();
			feedback((add ? "Protected: " : "Unprotected: ") + id,
					add ? ChatFormatting.GREEN : ChatFormatting.YELLOW);
		} else {
			feedback(add ? "Already protected." : "Not protected.", ChatFormatting.GRAY);
		}
		return 1;
	}

	private static int keepList() {
		AutoSellConfig cfg = AutoSellConfig.get();
		if (cfg.keepList.isEmpty()) {
			feedback("Keep-list empty. Hold your gear and run /autosell keep add",
					ChatFormatting.YELLOW);
			return 1;
		}
		feedback("Keep-list (" + cfg.keepList.size() + "):", ChatFormatting.AQUA);
		for (String id : cfg.keepList) {
			feedback("  - " + id, ChatFormatting.WHITE);
		}
		return 1;
	}

	private static int setFarmSpot() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return 0;
		}
		BlockPos pos = mc.player.blockPosition();
		float yaw = mc.player.getYRot();
		float pitch = mc.player.getXRot();
		AutoSellConfig.get().setFarmSpot(pos.getX(), pos.getY(), pos.getZ(), yaw, pitch,
				mc.player.getX(), mc.player.getY(), mc.player.getZ());
		feedback("Farm spot set: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(),
				ChatFormatting.GREEN);
		feedback(String.format("  facing %s (yaw %.1f, pitch %.1f)", compass(yaw), yaw, pitch),
				ChatFormatting.GRAY);
		feedback(String.format("  exact stand %.2f, %.2f, %.2f - it will line up here "
						+ "before dropping", mc.player.getX(), mc.player.getY(), mc.player.getZ()),
				ChatFormatting.GRAY);
		return 1;
	}

	private static int fuelStatus() {
		AutoSellConfig cfg = AutoSellConfig.get();
		feedback("Fuel config:", ChatFormatting.AQUA);
		feedback("  dump chest: " + (cfg.hasDumpChest()
				? cfg.dumpX + ", " + cfg.dumpY + ", " + cfg.dumpZ : "NOT SET"),
				cfg.hasDumpChest() ? ChatFormatting.WHITE : ChatFormatting.RED);
		feedback("  farm spot: " + (cfg.hasFarmSpot()
				? cfg.farmX + ", " + cfg.farmY + ", " + cfg.farmZ
						+ (cfg.hasFarmFacing() ? " facing " + compass(cfg.farmYaw) : " (no facing)")
				: "NOT SET"),
				cfg.hasFarmSpot() ? ChatFormatting.WHITE : ChatFormatting.RED);
		feedback("  cycles: " + cfg.fuelMaxCycles + "  floor: $" + cfg.fuelMoneyFloor
				+ "  max spend: $" + cfg.fuelMaxSpend, ChatFormatting.WHITE);
		feedback("  buy amount: " + (cfg.fuelFixedStacks > 0
				? cfg.fuelFixedStacks + " stacks (fixed)"
				: "auto (free - " + cfg.fuelReserveSlots + ")"), ChatFormatting.WHITE);
		com.narek.autosell.fuel.ShopPath path = com.narek.autosell.fuel.ShopPath.load();
		feedback("  shop path: " + (path.isEmpty()
				? "NOT RECORDED - run /autosell fuel record"
				: path.size() + " steps (/autosell fuel path)"),
				path.isEmpty() ? ChatFormatting.RED : ChatFormatting.WHITE);
		feedback("  GUI log: " + (cfg.guiLogging ? "ON" : "OFF") + "  inspect key: GLFW "
				+ cfg.inspectKey, ChatFormatting.WHITE);
		feedback("  debug log: " + (com.narek.autosell.core.DebugLog.isEnabled()
				? "ON -> " + com.narek.autosell.core.DebugLog.path() : "OFF"),
				ChatFormatting.WHITE);
		double bal = com.narek.autosell.core.BalanceReader.read();
		feedback("  balance: " + (Double.isNaN(bal)
				? "unreadable (check /autosell inspect + balanceLabel)"
				: String.format("$%,.2f", bal)),
				Double.isNaN(bal) ? ChatFormatting.RED : ChatFormatting.GREEN);
		return 1;
	}

	private static int showPath() {
		com.narek.autosell.fuel.ShopPath path = com.narek.autosell.fuel.ShopPath.load();
		if (path.isEmpty()) {
			feedback("No shop path recorded. Run /autosell fuel record",
					ChatFormatting.RED);
			return 1;
		}
		feedback("Recorded shop path (" + path.size() + " steps):", ChatFormatting.AQUA);
		for (int i = 0; i < path.size(); i++) {
			boolean last = i == path.size() - 1;
			feedback("  " + (i + 1) + ". " + path.steps.get(i).describe()
					+ (last ? "  <- purchase" : ""),
					last ? ChatFormatting.AQUA : ChatFormatting.WHITE);
		}
		return 1;
	}

	/** Cardinal name for a yaw, so the saved facing is readable. */
	private static String compass(float yaw) {
		float y = ((yaw % 360F) + 360F) % 360F;
		String[] names = {"south", "south-west", "west", "north-west",
				"north", "north-east", "east", "south-east"};
		return names[Math.round(y / 45F) % 8];
	}

	private static String describeKey(int glfw) {
		if (glfw >= GLFW.GLFW_KEY_0 && glfw <= GLFW.GLFW_KEY_9) {
			return String.valueOf((char) ('0' + (glfw - GLFW.GLFW_KEY_0)));
		}
		if (glfw >= GLFW.GLFW_KEY_A && glfw <= GLFW.GLFW_KEY_Z) {
			return String.valueOf((char) ('A' + (glfw - GLFW.GLFW_KEY_A)));
		}
		return "key " + glfw;
	}

	private static void feedback(String text, ChatFormatting colour) {
		AutoSellClient.chat(Component.literal(text).withStyle(colour));
	}
}
