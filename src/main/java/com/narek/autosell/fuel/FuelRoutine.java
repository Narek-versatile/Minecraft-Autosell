package com.narek.autosell.fuel;

import com.narek.autosell.AutoSellClient;
import com.narek.autosell.config.AutoSellConfig;
import com.narek.autosell.core.Aim;
import com.narek.autosell.core.BalanceReader;
import com.narek.autosell.core.DebugLog;
import com.narek.autosell.core.InventoryScanner;
import com.narek.autosell.core.LoreReader;
import com.narek.autosell.core.PathFinder;
import com.narek.autosell.core.ShopNavigator;
import com.narek.autosell.core.TextFold;
import com.narek.autosell.core.Walker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Buy bones, craft bone meal, drop it on the farm, repeat.
 *
 * <p>Cycle: drain inventory into the dump chest, /shop to the bone listing,
 * size the purchase to the free space, buy, walk to the farm spot, then
 * craft-and-drop until the bones are gone.
 *
 * <p>Money safety: the confirm button's own price text is parsed and checked
 * against {@code fuelMaxSpend} before the click is sent. The shop slot indices
 * are config-driven and were read off screenshots, so this guard is what stands
 * between a mis-read slot and an emptied account.
 */
public final class FuelRoutine {

	public enum State {
		IDLE,
		WALK_DUMP,
		OPEN_DUMP,
		AWAIT_DUMP,
		DRAIN,
		CLOSE_DUMP,
		SHOP_OPEN,
		SHOP_MAIN,
		SHOP_CATEGORY,
		SHOP_ITEM,
		SHOP_STACKS,
		SHOP_ADJUST,
		SHOP_CONFIRM,
		AWAIT_GOODS,
		WALK_FARM,
		ALIGN_FARM,
		CRAFT_PLACE,
		CRAFT_PICKUP,
		CRAFT_INSERT,
		CRAFT_TAKE,
		DROP,
		CYCLE_END
	}

	private static final int PLAYER_SLOTS_IN_MENU = 36;
	private static final int MENU_HOTBAR_OFFSET = 27;
	/** One bone stack crafts into three stacks of meal. */
	private static final int PRODUCT_SLOTS_PER_CRAFT = 3;
	/**
	 * Bone meal is dropped on the ground for the farm to collect, so the spot
	 * has to be stood on rather than merely reached.
	 */
	private static final double FARM_ARRIVE = 1.2;
	/** If pathing gives up within this range, walk the rest during alignment. */
	private static final double ALIGN_RESCUE_RANGE = 6.0;
	/** Re-align only past this drift, well outside the alignment tolerance. */
	private static final double DRIFT_REALIGN = 0.8;
	private static final int MAX_REALIGNS = 3;
	/** Degrees per tick while lining up the throw. */
	private static final float DROP_TURN_RATE = 25F;
	/** Craft/drop must show progress within this window. */
	private static final int CRAFT_STALL_TICKS = 200;
	/** Ticks to wait for the server to compute a crafting result. */
	private static final int RESULT_TIMEOUT = 40;
	private static final int MAX_CRAFT_FAILURES = 3;
	/** Ticks to wait for a single container click to take effect. */
	private static final int STEP_TIMEOUT = 30;
	/** Max quantity-adjust clicks before we stop trusting the pane layout. */
	private static final int MAX_ADJUST_CLICKS = 40;
	/** Ticks to wait for the server to apply an amount click. */
	private static final int COUNTER_TIMEOUT = 20;

	private static final Pattern PRICE =
			Pattern.compile("\\$\\s*([\\d,]+(?:\\.\\d+)?)");

	private final Walker walker = new Walker();
	private final com.narek.autosell.core.BlockOpener opener =
			new com.narek.autosell.core.BlockOpener();

	private State state = State.IDLE;
	private int delay;
	private int timeout;
	private int cycle;
	private int adjustClicks;
	private int awaitingCount = -1;
	private int awaitTicks;
	private int targetStacks;
	private int qtySlot = -1;
	private int stepIndex;
	private boolean resumeCraftAfterDrop;
	private int mealDropped;
	private int bonesBought;
	private double spent;
	private String lastCraftSignature;
	private int craftStallTicks;
	private int resultWait;
	private int craftFailures;
	private int pickupSlot = -1;
	private int stepTicks;
	private int realignAttempts;
	private ShopPath shopPath = new ShopPath();
	private int craftGuard;
	private boolean dryRun;
	private double startBalance = Double.NaN;

	public boolean isRunning() {
		return state != State.IDLE;
	}

	public State state() {
		return state;
	}

	public int cycle() {
		return cycle;
	}

	public Walker walker() {
		return walker;
	}

	public int maxCycles() {
		return AutoSellConfig.get().fuelMaxCycles;
	}

	/** Bones still waiting to be crafted, in items. */
	public int bonesLeft() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player == null ? 0
				: countItem(mc.player, AutoSellConfig.get().fuelSourceItem);
	}

	public int bonesBought() {
		return bonesBought;
	}

	public int mealDropped() {
		return mealDropped;
	}

	public double spent() {
		return spent;
	}

	public void start(boolean dryRun) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (isRunning()) {
			abort("Cancelled.");
			return;
		}
		AutoSellConfig cfg = AutoSellConfig.get();
		if (!cfg.hasDumpChest()) {
			abort("No dump chest set. Look at one and run /autosell dump set");
			return;
		}
		if (!cfg.hasFarmSpot()) {
			abort("No farm spot set. Stand where you want to drop and run /autosell fuel spot");
			return;
		}

		resetState();
		this.shopPath = ShopPath.load();
		if (shopPath.isEmpty()) {
			abort("No shop path recorded. Run /autosell fuel record, then click "
					+ "category -> item -> buy stacks -> purchase in /shop.");
			return;
		}
		this.dryRun = dryRun;
		this.cycle = 0;
		this.startBalance = BalanceReader.read();
		DebugLog.runStart("FUEL RUN" + (dryRun ? " (dry)" : ""));
		DebugLog.log("fuel", "path=" + shopPath.size() + " steps, balance=" + startBalance);

		AutoSellClient.chat(Component.literal(
						dryRun
								? "Fuel DRY RUN - will walk the shop path but never click purchase."
								: "Fuel cycle started.")
				.withStyle(dryRun ? ChatFormatting.YELLOW : ChatFormatting.GRAY));

		if (!Double.isNaN(startBalance)) {
			AutoSellClient.chat(Component.literal(
							"Balance: $" + String.format("%,.2f", startBalance))
					.withStyle(ChatFormatting.GRAY));
		}
		beginCycle();
	}

	private void beginCycle() {
		AutoSellConfig cfg = AutoSellConfig.get();
		stepIndex = 0;
		mealDropped = 0;
		bonesBought = 0;
		resultWait = 0;
		craftFailures = 0;
		walker.start(new BlockPos(cfg.dumpX, cfg.dumpY, cfg.dumpZ));
		transition(State.WALK_DUMP);
	}

	/**
	 * Clears every run-scoped field.
	 *
	 * <p>Only a handful were being reset, so a second run inherited the first
	 * one's leftovers - a stale quantity slot, a pending counter wait, a
	 * half-finished craft flag, an opener that had already used up its retries.
	 * That is why a first run could be clean and the next one wedge.
	 */
	private void resetState() {
		delay = 0;
		timeout = 0;
		adjustClicks = 0;
		awaitingCount = -1;
		awaitTicks = 0;
		targetStacks = 0;
		qtySlot = -1;
		stepIndex = 0;
		resumeCraftAfterDrop = false;
		mealDropped = 0;
		bonesBought = 0;
		spent = 0;
		lastCraftSignature = null;
		craftStallTicks = 0;
		resultWait = 0;
		craftFailures = 0;
		craftGuard = 0;
		pickupSlot = -1;
		stepTicks = 0;
		realignAttempts = 0;
		cycle = 0;
		walker.stop();
		opener.stop();
	}

	public void abort(String reason) {
		DebugLog.log("fuel", "ABORT in " + state + " - " + reason);
		DebugLog.log("fuel", "  state dump: " + dumpState());
		state = State.IDLE;
		resetState();
		walker.stop();
		opener.stop();
		AutoSellClient.chat(Component.literal("Fuel: " + reason)
				.withStyle(ChatFormatting.RED));
	}

	private void finish(String why) {
		DebugLog.log("fuel", "FINISH - " + why + " (" + cycle + " cycles)");
		state = State.IDLE;
		double keepSpent = spent;
		resetState();
		spent = keepSpent;
		walker.stop();
		double now = BalanceReader.read();
		String spent = "";
		if (!Double.isNaN(now) && !Double.isNaN(startBalance)) {
			spent = String.format(" | spent $%,.2f", startBalance - now);
		}
		AutoSellClient.chat(Component.literal(
						"Fuel finished after " + cycle + " cycle(s) - " + why + spent)
				.withStyle(ChatFormatting.GREEN));
	}

	private void transition(State next) {
		if (next != this.state) {
			announce(next);
		}
		this.state = next;
		this.timeout = 0;
	}

	/** Human-readable phase, so a run is never silently doing nothing. */
	public static String describe(State s) {
		return switch (s) {
			case IDLE -> "idle";
			case WALK_DUMP -> "walking to dump chest";
			case OPEN_DUMP, AWAIT_DUMP -> "opening dump chest";
			case DRAIN -> "emptying inventory";
			case CLOSE_DUMP -> "closing dump chest";
			case SHOP_OPEN -> "opening shop";
			case SHOP_MAIN, SHOP_CATEGORY, SHOP_ITEM -> "navigating shop";
			case SHOP_STACKS -> "reading amount";
			case SHOP_ADJUST -> "setting amount";
			case SHOP_CONFIRM -> "checking price";
			case AWAIT_GOODS -> "waiting for bones";
			case WALK_FARM -> "walking to farm spot";
			case ALIGN_FARM -> "lining up on the spot";
			case CRAFT_PLACE, CRAFT_PICKUP, CRAFT_INSERT, CRAFT_TAKE -> "crafting bone meal";
			case DROP -> "dropping bone meal";
			case CYCLE_END -> "finishing cycle";
		};
	}

	private void announce(State next) {
		DebugLog.log("fuel", state + " -> " + next + "  " + dumpState());
		if (next == State.IDLE) {
			return;
		}
		AutoSellClient.chat(Component.literal("[fuel] " + describe(next))
				.withStyle(ChatFormatting.DARK_AQUA));
	}

	/** One-line snapshot of everything that governs the next decision. */
	private String dumpState() {
		Minecraft mc = Minecraft.getInstance();
		AutoSellConfig cfg = AutoSellConfig.get();
		StringBuilder sb = new StringBuilder();
		sb.append("cycle=").append(cycle)
				.append(" step=").append(stepIndex)
				.append(" target=").append(targetStacks)
				.append(" qtySlot=").append(qtySlot)
				.append(" awaiting=").append(awaitingCount)
				.append(" resume=").append(resumeCraftAfterDrop)
				.append(" resultWait=").append(resultWait)
				.append(" fails=").append(craftFailures)
				.append(" delay=").append(delay);
		if (mc.player != null) {
			sb.append(" bones=").append(countItem(mc.player, cfg.fuelSourceItem))
					.append(" meal=").append(countItem(mc.player, cfg.fuelProductItem))
					.append(" carried=")
					.append(mc.player.inventoryMenu.getCarried().isEmpty()
							? "-" : mc.player.inventoryMenu.getCarried().getHoverName().getString())
					.append(" screen=")
					.append(mc.gui.screen() == null ? "-"
							: mc.gui.screen().getTitle().getString());
		}
		sb.append(" walker=").append(walker.isActive()
				? (walker.isDelegated() ? "baritone" : walker.isAligning() ? "align" : "builtin")
				: "off");
		return sb.toString();
	}

	public void tick() {
		if (state == State.IDLE) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.getConnection() == null) {
			abort("Disconnected mid-cycle.");
			return;
		}
		if (delay > 0) {
			delay--;
			return;
		}
		AutoSellConfig cfg = AutoSellConfig.get();

		if (isCraftPhase(state)) {
			watchCraftProgress(player, cfg);
			if (state == State.IDLE) {
				return;
			}
		} else {
			lastCraftSignature = null;
			craftStallTicks = 0;
		}

		switch (state) {
			case WALK_DUMP -> tickWalk(cfg, new BlockPos(cfg.dumpX, cfg.dumpY, cfg.dumpZ),
					State.OPEN_DUMP, "dump chest");
			case OPEN_DUMP -> tickOpenAt(mc, player, cfg,
					new BlockPos(cfg.dumpX, cfg.dumpY, cfg.dumpZ), State.AWAIT_DUMP);
			case AWAIT_DUMP -> tickAwaitContainer(mc, cfg, State.DRAIN);
			case DRAIN -> tickDrain(mc, player, cfg);
			case CLOSE_DUMP -> tickClose(mc, player, cfg, State.SHOP_OPEN);
			case SHOP_OPEN -> tickShopOpen(mc, cfg);
			case SHOP_MAIN, SHOP_CATEGORY, SHOP_ITEM -> tickReplayStep(mc, player, cfg);
			case SHOP_STACKS -> tickPrepareAdjust(mc, player, cfg);
			case SHOP_ADJUST -> tickAdjust(mc, player, cfg);
			case SHOP_CONFIRM -> tickConfirm(mc, player, cfg);
			case AWAIT_GOODS -> tickAwaitGoods(player, cfg);
			case WALK_FARM -> tickWalkFarm(cfg, player);
			case ALIGN_FARM -> tickAlignFarm(cfg);
			case CRAFT_PLACE -> tickCraftPlace(mc, player, cfg);
			case CRAFT_PICKUP -> tickCraftPickup(mc, player, cfg);
			case CRAFT_INSERT -> tickCraftInsert(mc, player, cfg);
			case CRAFT_TAKE -> tickCraftTake(mc, player, cfg);
			case DROP -> tickDrop(mc, player, cfg);
			case CYCLE_END -> tickCycleEnd(cfg);
			default -> {
			}
		}
	}

	// -- shared movement / container helpers -------------------------------

	private void tickWalk(AutoSellConfig cfg, BlockPos target, State next, String label) {
		tickWalkPrecise(cfg, target, next, label, cfg.reachDistance);
	}

	private void tickWalkPrecise(AutoSellConfig cfg, BlockPos target, State next,
			String label, double arrive) {
		if (!walker.isActive()) {
			walker.start(target, arrive);
			AutoSellClient.chat(Component.literal(
							"[fuel] " + label + ": " + (walker.hasRoute()
									? walker.waypointsRemaining() + " waypoints"
									: "no route found, heading straight"))
					.withStyle(walker.hasRoute()
							? ChatFormatting.DARK_AQUA : ChatFormatting.YELLOW));
		}
		switch (walker.tick()) {
			case ARRIVED -> {
				delay = cfg.clickDelayTicks;
				transition(next);
			}
			case TIMEOUT -> abort("Gave up walking to the " + label + ".");
			case STUCK -> abort("Stuck on the way to the " + label + ".");
			default -> {
			}
		}
	}

	/** Aims at the chest and interacts once the crosshair is on it. */
	private void tickOpenAt(Minecraft mc, LocalPlayer player, AutoSellConfig cfg,
			BlockPos pos, State next) {
		if (openContainerMenu(mc) != null) {
			opener.stop();
			transition(next);
			return;
		}
		opener.ensureTargeting(pos);
		switch (opener.tick()) {
			case OUT_OF_RANGE -> {
				opener.stop();
				walker.start(pos);
				transition(State.WALK_DUMP);
			}
			case FAILED -> {
				opener.stop();
				abort("Could not open the dump chest at " + pos.getX() + ", "
						+ pos.getY() + ", " + pos.getZ() + ".");
			}
			default -> {
				if (openContainerMenu(mc) != null) {
					opener.stop();
					delay = cfg.clickDelayTicks;
					transition(next);
				}
			}
		}
	}

	private void tickAwaitContainer(Minecraft mc, AutoSellConfig cfg, State next) {
		if (openContainerMenu(mc) != null) {
			opener.stop();
			delay = cfg.clickDelayTicks;
			transition(next);
			return;
		}
		if (opener.isActive()
				&& opener.tick() == com.narek.autosell.core.BlockOpener.Status.FAILED) {
			opener.stop();
			abort("Dump chest did not respond to being opened.");
			return;
		}
		if (++timeout > cfg.containerTimeoutTicks) {
			opener.stop();
			abort("Dump chest did not open.");
		}
	}

	/** Full drain: everything except gear goes into the chest. */
	private void tickDrain(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = openContainerMenu(mc);
		if (menu == null) {
			abort("Dump chest closed unexpectedly.");
			return;
		}
		int chestSlots = menu.slots.size() - PLAYER_SLOTS_IN_MENU;
		if (chestSlots <= 0) {
			abort("Dump target is not a chest-like container.");
			return;
		}
		List<Integer> toDeposit = InventoryScanner.findDepositSlots(player, false);
		if (toDeposit.isEmpty()) {
			transition(State.CLOSE_DUMP);
			return;
		}
		int fired = 0;
		for (int playerSlot : toDeposit) {
			mc.gameMode.handleContainerInput(menu.containerId,
					playerSlotToMenuIndex(playerSlot, chestSlots), 0,
					ContainerInput.QUICK_MOVE, player);
			if (++fired >= cfg.burstMaxClicks) {
				break;
			}
		}
		delay = cfg.clickDelayTicks;
		if (++timeout > 200) {
			abort("Dump chest is not accepting items (full?).");
		}
	}

	private void tickClose(Minecraft mc, LocalPlayer player, AutoSellConfig cfg, State next) {
		if (openContainerMenu(mc) != null) {
			player.closeContainer();
			mc.gui.setScreen(null);
			delay = cfg.clickDelayTicks;
		}
		transition(next);
	}

	// -- shop --------------------------------------------------------------

	private void tickShopOpen(Minecraft mc, AutoSellConfig cfg) {
		mc.getConnection().sendCommand(cfg.shopCommand);
		delay = Math.max(cfg.clickDelayTicks, 5);
		transition(State.SHOP_MAIN);
	}

	/**
	 * Replays the next recorded navigation step.
	 *
	 * <p>Resolution order per step: the recorded slot if it still holds the
	 * same item, otherwise a search for the recorded label. Neither matching
	 * means the layout moved, and the run aborts rather than clicking blind.
	 */
	private void tickReplayStep(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = openContainerMenu(mc);
		if (menu == null) {
			if (++timeout > cfg.shopScreenTimeoutTicks) {
				abort("Shop screen never appeared (step " + (stepIndex + 1) + ").");
			}
			return;
		}
		List<ShopPath.Step> nav = shopPath.navigationSteps();
		if (stepIndex >= nav.size()) {
			transition(State.SHOP_STACKS);
			return;
		}
		ShopPath.Step step = nav.get(stepIndex);

		// Wait until the screen the step was recorded on is actually showing.
		String title = TextFold.normalize(mc.gui.screen().getTitle().getString());
		if (!step.screenTitle.isEmpty() && !title.equals(step.screenTitle)) {
			if (++timeout > cfg.shopScreenTimeoutTicks) {
				abort("Expected screen \"" + step.screenTitle + "\" but saw \"" + title
						+ "\" (step " + (stepIndex + 1) + ").");
			}
			return;
		}

		int slot = resolveStep(menu, step);
		if (slot < 0) {
			if (++timeout <= cfg.shopScreenTimeoutTicks) {
				return; // contents may still be streaming in
			}
			AutoSellClient.chat(Component.literal(
							"Fuel: step " + (stepIndex + 1) + " " + step.describe()
									+ " no longer matches this screen.")
					.withStyle(ChatFormatting.RED));
			for (String line : ShopNavigator.describe(menu)) {
				AutoSellClient.chat(Component.literal("    " + line)
						.withStyle(ChatFormatting.GRAY));
			}
			abort("Shop layout changed - re-record with /autosell fuel record.");
			return;
		}

		AutoSellClient.chat(Component.literal(
						"  step " + (stepIndex + 1) + ": slot " + slot + " "
								+ menu.slots.get(slot).getItem().getHoverName().getString())
				.withStyle(ChatFormatting.DARK_GRAY));

		mc.gameMode.handleContainerInput(
				menu.containerId, slot, step.button, ContainerInput.PICKUP, player);
		stepIndex++;
		timeout = 0;
		delay = Math.max(cfg.clickDelayTicks, 5);

		if (stepIndex >= nav.size()) {
			transition(State.SHOP_STACKS);
		}
	}

	/**
	 * Strict resolution for the purchase button: the slot must hold exactly the
	 * item that was recorded, by id and by label.
	 *
	 * <p>The lenient search used for navigation is not safe here. It once
	 * resolved to a quantity button, so the run added another stack instead of
	 * buying - spending nothing but leaving the amount wrong. A purchase click
	 * is the one place where guessing is unacceptable.
	 */
	private static int resolveConfirmStrict(AbstractContainerMenu menu, ShopPath.Step step) {
		int top = ShopNavigator.containerSlots(menu);
		for (int i = 0; i < top; i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (!step.itemId.equals(InventoryScanner.idOf(stack))) {
				continue;
			}
			if (!TextFold.normalize(stack.getHoverName().getString()).equals(step.itemName)) {
				continue;
			}
			return i;
		}
		return -1;
	}

	/** Recorded slot if the item still matches, else a label search. */
	private static int resolveStep(AbstractContainerMenu menu, ShopPath.Step step) {
		int top = ShopNavigator.containerSlots(menu);
		if (step.slot >= 0 && step.slot < top) {
			ItemStack at = menu.slots.get(step.slot).getItem();
			if (at != null && !at.isEmpty()) {
				boolean sameItem = step.itemId.equals(InventoryScanner.idOf(at));
				boolean sameName = TextFold.normalize(at.getHoverName().getString())
						.equals(step.itemName);
				if (sameItem && sameName) {
					return step.slot;
				}
			}
		}
		if (step.itemName.isEmpty()) {
			return -1;
		}
		ShopNavigator.Result byName = ShopNavigator.find(menu, step.itemName);
		return byName.ok() ? byName.match().slot() : -1;
	}

	/** Work out how many stacks to buy, then start adjusting. */
	private void tickPrepareAdjust(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = openContainerMenu(mc);
		if (menu == null) {
			if (++timeout > cfg.shopScreenTimeoutTicks) {
				abort("Buy-stacks screen never appeared.");
			}
			return;
		}
		int free = InventoryScanner.freeStorageSlots(player) + freeHotbarSlots(player);
		targetStacks = cfg.fuelFixedStacks > 0
				? cfg.fuelFixedStacks
				: Math.max(1, free - cfg.fuelReserveSlots);
		adjustClicks = 0;
		awaitingCount = -1;
		awaitTicks = 0;

		// The counter is the slot holding the item being bought.
		qtySlot = findSourceSlot(menu, cfg.fuelSourceItem);
		AutoSellClient.chat(Component.literal(
						"  free slots: " + free + " -> want " + targetStacks + " stack(s)"
								+ (cfg.fuelFixedStacks > 0 ? " (fixed)" : " (auto)"))
				.withStyle(ChatFormatting.GRAY));
		if (qtySlot < 0) {
			AutoSellClient.chat(Component.literal(
							"  no " + cfg.fuelSourceItem
									+ " counter on this screen; buying the default amount")
					.withStyle(ChatFormatting.YELLOW));
			transition(State.SHOP_CONFIRM);
			return;
		}
		List<Adjuster> buttons = discoverAdjusters(menu);
		StringBuilder names = new StringBuilder();
		for (Adjuster a : buttons) {
			names.append(a.label()).append(' ');
		}
		AutoSellClient.chat(Component.literal(
						"  counter slot " + qtySlot + " at "
								+ menu.slots.get(qtySlot).getItem().getCount()
								+ " | buttons: " + (names.isEmpty() ? "none found" : names.toString().trim()))
				.withStyle(ChatFormatting.DARK_GRAY));
		if (buttons.isEmpty()) {
			AutoSellClient.chat(Component.literal(
							"  no amount buttons on this screen; buying the default")
					.withStyle(ChatFormatting.YELLOW));
			transition(State.SHOP_CONFIRM);
			return;
		}
		transition(State.SHOP_ADJUST);
	}

	/**
	 * Clicks learned amount buttons until the counter reaches the target.
	 *
	 * <p>Greedy on the largest button that will not overshoot. Button values
	 * come from what they actually did during recording, so this adapts to the
	 * free space rather than repeating a recorded amount.
	 */
	private void tickAdjust(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = openContainerMenu(mc);
		if (menu == null) {
			abort("Buy-stacks screen closed.");
			return;
		}
		int current = readQuantity(menu);
		if (current < 0) {
			abort("Lost the amount counter. Re-record with /autosell fuel record.");
			return;
		}
		// Wait for the previous click to land. Clicking again on a stale
		// reading is what overshot the amount and left the routine still
		// adjusting when it believed it was done.
		if (awaitingCount >= 0) {
			if (current == awaitingCount) {
				awaitingCount = -1;
				awaitTicks = 0;
			} else if (++awaitTicks > COUNTER_TIMEOUT) {
				awaitingCount = -1;
				awaitTicks = 0;
			} else {
				return;
			}
		}
		if (current == targetStacks || adjustClicks >= MAX_ADJUST_CLICKS) {
			if (current != targetStacks) {
				AutoSellClient.chat(Component.literal(
								"  settled at " + current + " stacks (wanted " + targetStacks + ")")
						.withStyle(ChatFormatting.YELLOW));
			} else {
				AutoSellClient.chat(Component.literal("  amount set to " + current + " stacks")
						.withStyle(ChatFormatting.GRAY));
			}
			transition(State.SHOP_CONFIRM);
			return;
		}

		int gap = targetStacks - current;
		boolean needMore = gap > 0;

		Adjuster best = null;
		for (Adjuster adj : discoverAdjusters(menu)) {
			if ((adj.delta() > 0) != needMore) {
				continue;
			}
			if (Math.abs(adj.delta()) > Math.abs(gap)) {
				continue;
			}
			best = adj; // sorted largest-first
			break;
		}
		if (best == null) {
			AutoSellClient.chat(Component.literal(
							"  no amount button fits the remaining " + gap
									+ "; buying " + current + " stacks")
					.withStyle(ChatFormatting.YELLOW));
			transition(State.SHOP_CONFIRM);
			return;
		}

		mc.gameMode.handleContainerInput(
				menu.containerId, best.slot(), 0, ContainerInput.PICKUP, player);
		adjustClicks++;
		awaitingCount = current + best.delta();
		awaitTicks = 0;
		delay = Math.max(cfg.clickDelayTicks, 3);
	}

	/** A quantity button and the amount it applies. */
	private record Adjuster(int slot, int delta, String label) {
	}

	private static final Pattern ADJUST_LABEL =
			Pattern.compile("^\\s*([+-])\\s*(\\d+)\\s*$");

	/**
	 * Finds the quantity buttons by reading their labels.
	 *
	 * <p>They are named literally "+1", "+16", "+32", "-1", "-16", so the value
	 * is in the label and nothing has to be recorded or configured. Sorted
	 * largest-first so a greedy walk reaches the target in few clicks, and the
	 * presence of a 1-step button means any target is reachable exactly.
	 */
	private static List<Adjuster> discoverAdjusters(AbstractContainerMenu menu) {
		List<Adjuster> found = new ArrayList<>();
		int top = ShopNavigator.containerSlots(menu);
		for (int i = 0; i < top; i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String label = stack.getHoverName().getString().trim();
			Matcher m = ADJUST_LABEL.matcher(label);
			if (!m.matches()) {
				continue;
			}
			int magnitude;
			try {
				magnitude = Integer.parseInt(m.group(2));
			} catch (NumberFormatException e) {
				continue;
			}
			if (magnitude == 0) {
				continue;
			}
			int delta = "-".equals(m.group(1)) ? -magnitude : magnitude;
			found.add(new Adjuster(i, delta, label));
		}
		found.sort((a, b) -> Integer.compare(Math.abs(b.delta()), Math.abs(a.delta())));
		return found;
	}

	private void tickConfirm(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = openContainerMenu(mc);
		if (menu == null) {
			abort("Buy screen closed before confirming.");
			return;
		}
		ShopPath.Step confirmStep = shopPath.confirmStep();
		if (confirmStep == null) {
			abort("No recorded purchase step. Run /autosell fuel record first.");
			return;
		}
		int slot = resolveConfirmStrict(menu, confirmStep);
		if (slot < 0) {
			AutoSellClient.chat(Component.literal(
							"Fuel: purchase button " + confirmStep.describe()
									+ " not found on this screen.")
					.withStyle(ChatFormatting.RED));
			for (String line : ShopNavigator.describe(menu)) {
				AutoSellClient.chat(Component.literal("    " + line)
						.withStyle(ChatFormatting.GRAY));
			}
			abort("Refusing to click an unidentified purchase button.");
			return;
		}
		ItemStack confirm = menu.slots.get(slot).getItem();
		if (confirm == null || confirm.isEmpty()) {
			abort("Purchase button vanished.");
			return;
		}

		Double price = parsePrice(confirm);
		String label = confirm.getHoverName().getString();

		if (price == null) {
			abort("Could not read a price on the confirm button (\"" + label
					+ "\"). Refusing to click blind - run /autosell inspect.");
			return;
		}
		if (price > cfg.fuelMaxSpend) {
			abort(String.format(
					"Purchase is $%,.2f which exceeds the $%,.2f cap. Not buying.",
					price, cfg.fuelMaxSpend));
			return;
		}
		double balance = BalanceReader.read();
		if (!Double.isNaN(balance) && cfg.fuelMoneyFloor > 0
				&& balance - price < cfg.fuelMoneyFloor) {
			player.closeContainer();
			mc.gui.setScreen(null);
			finish(String.format("balance floor reached ($%,.2f left)", balance));
			return;
		}

		if (dryRun) {
			AutoSellClient.chat(Component.literal(
							String.format("  DRY RUN: would click slot %d and pay $%,.2f",
									slot, price))
					.withStyle(ChatFormatting.YELLOW));
			player.closeContainer();
			mc.gui.setScreen(null);
			finish("dry run complete - nothing was purchased");
			return;
		}

		AutoSellClient.chat(Component.literal(
						String.format("  buying %d stack(s) for $%,.2f", targetStacks, price))
				.withStyle(ChatFormatting.AQUA));

		mc.gameMode.handleContainerInput(
				menu.containerId, slot, 0, ContainerInput.PICKUP, player);
		spent += price;
		bonesBought = targetStacks * 64;
		delay = Math.max(cfg.clickDelayTicks, 10);
		transition(State.AWAIT_GOODS);
	}

	private void tickAwaitGoods(LocalPlayer player, AutoSellConfig cfg) {
		if (countItem(player, cfg.fuelSourceItem) > 0) {
			Minecraft mc = Minecraft.getInstance();
			player.closeContainer();
			mc.gui.setScreen(null);
			delay = cfg.clickDelayTicks;
			walker.start(new BlockPos(cfg.farmX, cfg.farmY, cfg.farmZ), FARM_ARRIVE);
			transition(State.WALK_FARM);
			return;
		}
		if (++timeout > cfg.shopScreenTimeoutTicks) {
			abort("Bought, but no " + cfg.fuelSourceItem + " arrived in the inventory.");
		}
	}

	// -- craft & drop ------------------------------------------------------

	/**
	 * Decides each pass: craft another stack, dump what has piled up, or end.
	 *
	 * <p>One bone stack yields three stacks of meal, so crafting needs free
	 * space; when it runs low the meal is dropped and crafting resumes. That is
	 * the "craft a few, drop them all, craft more" loop, driven by actual free
	 * space rather than a fixed batch size.
	 */
	private void tickCraftPlace(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		if (!atFarmSpot(player, cfg)) {
			AutoSellClient.chat(Component.literal(
							"[fuel] off the farm spot - walking back before crafting")
					.withStyle(ChatFormatting.YELLOW));
			walker.start(new BlockPos(cfg.farmX, cfg.farmY, cfg.farmZ), FARM_ARRIVE);
			transition(State.WALK_FARM);
			return;
		}

		int bones = countItem(player, cfg.fuelSourceItem);
		int meal = countItem(player, cfg.fuelProductItem);
		int free = InventoryScanner.freeStorageSlots(player) + freeHotbarSlots(player);

		if (bones <= 0) {
			if (meal > 0) {
				resumeCraftAfterDrop = false;
				transition(State.DROP);
			} else {
				transition(State.CYCLE_END);
			}
			return;
		}
		// One stack of bones becomes three of meal; without room for that,
		// clear the meal first and come straight back.
		if (free < PRODUCT_SLOTS_PER_CRAFT && meal > 0) {
			resumeCraftAfterDrop = true;
			transition(State.DROP);
			return;
		}

		AbstractContainerMenu menu = player.inventoryMenu;

		// Anything held on the cursor silently disables THROW and blocks the
		// deposit into the grid, so clear it before doing anything else.
		if (!menu.getCarried().isEmpty()) {
			clearCursor(mc, player, menu);
			delay = cfg.craftDelayTicks;
			return;
		}
		// A grid still holding a full stack cannot accept another, which is
		// what left bones stranded on the cursor in the first place.
		if (gridOccupied(menu)) {
			returnGrid(mc, player, menu);
			delay = cfg.craftDelayTicks;
			return;
		}

		int srcSlot = findItemSlot(player, cfg.fuelSourceItem);
		if (srcSlot < 0) {
			transition(State.CYCLE_END);
			return;
		}

		// Placement is split across two states on purpose - see tickCraftPickup.
		pickupSlot = srcSlot;
		stepTicks = 0;
		transition(State.CRAFT_PICKUP);
	}

	/**
	 * Lifts one stack of the source item onto the cursor.
	 *
	 * <p>Pickup and insert are separate states because container clicks carry a
	 * {@code stateId}: two clicks issued in the same tick are both stamped with
	 * the same id, so the server accepts the first, moves its state on, then
	 * rejects the second as stale and resyncs the whole container. That resync
	 * is what threw the ingredients straight back into the inventory and made
	 * them appear to whip between slots.
	 *
	 * <p>Each step also waits for its own effect to show up rather than trusting
	 * a fixed delay, so this stays correct at any ping.
	 */
	private void tickCraftPickup(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = player.inventoryMenu;

		if (!menu.getCarried().isEmpty()) {
			// The stack is up; move on to placing it.
			stepTicks = 0;
			transition(State.CRAFT_INSERT);
			return;
		}
		if (stepTicks == 0) {
			if (pickupSlot < 0 || pickupSlot >= InventoryScanner.MAIN_END) {
				transition(State.CRAFT_PLACE);
				return;
			}
			mc.gameMode.handleContainerInput(menu.containerId,
					inventoryMenuIndex(pickupSlot), 0, ContainerInput.PICKUP, player);
		}
		if (++stepTicks > STEP_TIMEOUT) {
			DebugLog.log("fuel", "pickup did not take effect on slot " + pickupSlot);
			stepTicks = 0;
			transition(State.CRAFT_PLACE);
		}
	}

	/** Drops the carried stack into the crafting grid, one click, then waits. */
	private void tickCraftInsert(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = player.inventoryMenu;

		if (menu.getCarried().isEmpty() && gridOccupied(menu)) {
			// Landed in the grid: hand over to the result watcher.
			resultWait = 0;
			stepTicks = 0;
			transition(State.CRAFT_TAKE);
			return;
		}
		if (menu.getCarried().isEmpty() && stepTicks > 0) {
			// Nothing held and nothing in the grid: the click was undone, so
			// start the pickup again instead of waiting out the timeout.
			stepTicks = 0;
			transition(State.CRAFT_PLACE);
			return;
		}
		if (stepTicks == 0 && !menu.getCarried().isEmpty()) {
			mc.gameMode.handleContainerInput(menu.containerId,
					InventoryMenu.CRAFT_SLOT_START, 0, ContainerInput.PICKUP, player);
		}
		if (++stepTicks > STEP_TIMEOUT) {
			DebugLog.log("fuel", "insert into grid did not take effect; retrying");
			stepTicks = 0;
			craftFailures++;
			if (craftFailures >= MAX_CRAFT_FAILURES) {
				abort("Could not place " + cfg.fuelSourceItem
						+ " into the crafting grid - the server keeps rejecting it.");
				return;
			}
			transition(State.CRAFT_PLACE);
		}
	}

	/**
	 * Takes the crafted output, waiting for the server to produce it.
	 *
	 * <p>Crafting results are computed server-side only -
	 * {@code InventoryMenu.slotsChanged} hands off to
	 * {@code CraftingMenu.slotChangedCraftingGrid}, which needs a
	 * {@code ServerLevel}. The client's result slot therefore stays empty until
	 * a round-trip completes. Treating that momentary emptiness as "nothing to
	 * craft" pulled the ingredients straight back out and re-placed them, which
	 * is what produced the wobbling and the stack stranded on the cursor.
	 *
	 * <p>So an empty result while the grid still holds ingredients means
	 * "waiting", not "finished".
	 */
	private void tickCraftTake(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = player.inventoryMenu;

		if (!menu.getCarried().isEmpty()) {
			clearCursor(mc, player, menu);
			delay = cfg.craftDelayTicks;
			return;
		}

		ItemStack result = menu.slots.get(InventoryMenu.RESULT_SLOT).getItem();
		boolean gridHasItems = gridOccupied(menu);

		if (result != null && !result.isEmpty()) {
			resultWait = 0;

			// Shift-clicking the result crafts the whole grid stack at once,
			// but only into space that exists. Clear the meal out first when
			// there is nowhere to put the next batch.
			if (!InventoryScanner.hasRoomFor(player, result)) {
				returnGrid(mc, player, menu);
				resumeCraftAfterDrop = true;
				transition(State.DROP);
				return;
			}
			mc.gameMode.handleContainerInput(menu.containerId, InventoryMenu.RESULT_SLOT,
					0, ContainerInput.QUICK_MOVE, player);
			delay = cfg.craftDelayTicks;
			return;
		}

		if (gridHasItems) {
			// Ingredients are in but no result yet: the server has not answered.
			if (++resultWait > RESULT_TIMEOUT) {
				resultWait = 0;
				craftFailures++;
				returnGrid(mc, player, menu);
				if (craftFailures >= MAX_CRAFT_FAILURES) {
					abort("Server produced no crafting result for "
							+ cfg.fuelSourceItem + " after " + MAX_CRAFT_FAILURES
							+ " tries - is that recipe available here?");
					return;
				}
				delay = cfg.craftDelayTicks;
				transition(State.CRAFT_PLACE);
			}
			return;
		}

		// Grid and result both empty: this stack is fully converted.
		resultWait = 0;
		delay = cfg.craftDelayTicks;
		transition(State.CRAFT_PLACE);
	}

	/** Throws every stack of the crafted product onto the ground. */
	private void tickDrop(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		if (!atFarmSpot(player, cfg)) {
			AutoSellClient.chat(Component.literal(
							"[fuel] not on the farm spot - walking back before dropping")
					.withStyle(ChatFormatting.YELLOW));
			walker.start(new BlockPos(cfg.farmX, cfg.farmY, cfg.farmZ), FARM_ARRIVE);
			transition(State.WALK_FARM);
			return;
		}
		// Drifted off the exact spot: slide back before throwing. Hysteresis
		// is deliberately wider than the alignment tolerance so a spot it can
		// only reach approximately does not bounce between the two states.
		if (cfg.hasFarmPrecise() && offSpot(player, cfg) > DRIFT_REALIGN
				&& realignAttempts < MAX_REALIGNS) {
			realignAttempts++;
			transition(State.ALIGN_FARM);
			return;
		}

		// Thrown items fly where the player looks, so line up before throwing.
		if (cfg.hasFarmFacing()) {
			float yawErr = Math.abs(Aim.delta(player.getYRot(), cfg.farmYaw));
			float pitchErr = Math.abs(Aim.delta(player.getXRot(), cfg.farmPitch));
			if (yawErr > 3F || pitchErr > 3F) {
				player.setYRot(Aim.approach(player.getYRot(), cfg.farmYaw, DROP_TURN_RATE));
				player.setXRot(Aim.approach(player.getXRot(), cfg.farmPitch, DROP_TURN_RATE));
				return; // aim first; throwing off-target loses the lot
			}
			player.setYRot(cfg.farmYaw);
			player.setXRot(cfg.farmPitch);
		}

		AbstractContainerMenu menu = player.inventoryMenu;
		Inventory inv = player.getInventory();

		// THROW is a no-op whenever something is held, so free the cursor
		// before attempting to drop anything.
		if (!menu.getCarried().isEmpty()) {
			clearCursor(mc, player, menu);
			delay = cfg.craftDelayTicks;
			return;
		}

		int dropped = 0;
		for (int slot = 0; slot < InventoryScanner.MAIN_END; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (!cfg.fuelProductItem.equals(InventoryScanner.idOf(stack))) {
				continue;
			}
			// THROW with button 1 drops the whole stack.
			// One click per tick here too: the inventory menu is stateful and
			// does not self-heal the way a re-scanned chest transfer does.
			mc.gameMode.handleContainerInput(menu.containerId, inventoryMenuIndex(slot),
					1, ContainerInput.THROW, player);
			mealDropped += stack.getCount();
			dropped++;
			break;
		}

		if (dropped > 0) {
			delay = cfg.craftDelayTicks;
			return;
		}
		if (resumeCraftAfterDrop && countItem(player, cfg.fuelSourceItem) > 0) {
			resumeCraftAfterDrop = false;
			transition(State.CRAFT_PLACE);
			return;
		}
		transition(State.CYCLE_END);
	}

	/**
	 * Walks to a block it can actually stand on near the farm spot.
	 *
	 * <p>A spot recorded while straddling two blocks, or on an edge, can resolve
	 * to a block position that is not standable - which Baritone reports as
	 * simply unreachable. Pathing therefore aims at the nearest standable block
	 * and leaves the exact sub-block placement to the alignment step, which can
	 * strafe onto positions that are not block centres.
	 *
	 * <p>Getting close is also good enough: if pathing gives up while already
	 * near the spot, alignment can cover the remainder on foot.
	 */
	private void tickWalkFarm(AutoSellConfig cfg, LocalPlayer player) {
		Minecraft mc = Minecraft.getInstance();
		BlockPos exact = new BlockPos(cfg.farmX, cfg.farmY, cfg.farmZ);
		BlockPos goal = exact;
		if (mc.level != null) {
			BlockPos standable = PathFinder.nearestStandable(mc.level, exact);
			if (standable != null) {
				goal = standable;
			}
		}
		if (!walker.isActive()) {
			walker.start(goal, FARM_ARRIVE);
			DebugLog.log("fuel", "walking to farm: exact=" + exact + " pathTo=" + goal);
		}
		double away = Math.sqrt(player.distanceToSqr(
				exact.getX() + 0.5, exact.getY() + 0.5, exact.getZ() + 0.5));

		switch (walker.tick()) {
			case ARRIVED -> {
				delay = cfg.craftDelayTicks;
				transition(State.ALIGN_FARM);
			}
			case TIMEOUT, STUCK -> {
				walker.stop();
				if (away <= ALIGN_RESCUE_RANGE) {
					// Close enough for the alignment step to finish on foot.
					DebugLog.log("fuel", "pathing gave up " + String.format("%.1f", away)
							+ "m out; aligning from here");
					transition(State.ALIGN_FARM);
				} else {
					abort(String.format(
							"Could not reach the farm spot (%.1f blocks away). "
									+ "Re-record it standing somewhere walkable.", away));
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Slides onto the exact recorded position before crafting begins.
	 *
	 * <p>Baritone gets us to the right block; throw arcs start from the exact
	 * sub-block position, so "somewhere in that block" lands items off-target.
	 */
	private void tickAlignFarm(AutoSellConfig cfg) {
		if (!cfg.hasFarmPrecise()) {
			transition(State.CRAFT_PLACE);
			return;
		}
		if (!walker.isAligning()) {
			walker.startAlign(
					new Vec3(cfg.farmPosX, cfg.farmPosY, cfg.farmPosZ),
					cfg.hasFarmFacing() ? cfg.farmYaw : Minecraft.getInstance().player.getYRot());
		}
		switch (walker.tickAlign()) {
			case ARRIVED -> {
				AutoSellClient.chat(Component.literal("[fuel] on the spot")
						.withStyle(ChatFormatting.DARK_AQUA));
				delay = cfg.craftDelayTicks;
				transition(State.CRAFT_PLACE);
			}
			case TIMEOUT -> {
				Minecraft mc = Minecraft.getInstance();
				double off = mc.player == null ? -1 : offSpot(mc.player, cfg);
				DebugLog.log("fuel", String.format(
						"align gave up %.2f blocks from the spot", off));
				AutoSellClient.chat(Component.literal(String.format(
								"[fuel] settled %.2f blocks off the spot - continuing", off))
						.withStyle(ChatFormatting.YELLOW));
				transition(State.CRAFT_PLACE);
			}
			default -> {
			}
		}
	}

	/** Distance from the exact recorded stand position. */
	private static double offSpot(LocalPlayer player, AutoSellConfig cfg) {
		if (!cfg.hasFarmPrecise()) {
			return 0;
		}
		double dx = cfg.farmPosX - player.getX();
		double dz = cfg.farmPosZ - player.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static boolean isCraftPhase(State s) {
		return s == State.CRAFT_PLACE || s == State.CRAFT_PICKUP
				|| s == State.CRAFT_INSERT || s == State.CRAFT_TAKE || s == State.DROP;
	}

	/**
	 * Aborts loudly if the craft/drop loop stops making progress.
	 *
	 * <p>The stuck-cursor bug presented as the routine sitting there doing
	 * nothing with no indication whether it had finished or died. Progress is
	 * now measured directly, so a stall reports itself instead of looking like
	 * a completed run.
	 */
	private void watchCraftProgress(LocalPlayer player, AutoSellConfig cfg) {
		String signature = countItem(player, cfg.fuelSourceItem)
				+ "/" + countItem(player, cfg.fuelProductItem)
				+ "/" + player.inventoryMenu.getCarried().getCount();

		if (signature.equals(lastCraftSignature)) {
			if (++craftStallTicks > CRAFT_STALL_TICKS) {
				String carried = player.inventoryMenu.getCarried().isEmpty()
						? "nothing"
						: player.inventoryMenu.getCarried().getHoverName().getString();
				DebugLog.log("fuel", "STALL " + dumpState());
				abort("Crafting stalled for "
						+ (CRAFT_STALL_TICKS / 20) + "s (holding " + carried
						+ "). Stopping so it does not sit here silently.");
			}
		} else {
			lastCraftSignature = signature;
			craftStallTicks = 0;
		}
	}

	private void tickCycleEnd(AutoSellConfig cfg) {
		cycle++;
		if (cycle >= cfg.fuelMaxCycles) {
			finish("cycle limit reached");
			return;
		}
		double balance = BalanceReader.read();
		if (!Double.isNaN(balance) && cfg.fuelMoneyFloor > 0
				&& balance <= cfg.fuelMoneyFloor) {
			finish(String.format("balance floor reached ($%,.2f left)", balance));
			return;
		}
		AutoSellClient.chat(Component.literal("Fuel: cycle " + cycle + " done.")
				.withStyle(ChatFormatting.GRAY));
		beginCycle();
	}

	// -- helpers -----------------------------------------------------------

	/** True when any crafting-grid slot still holds something. */
	private static boolean gridOccupied(AbstractContainerMenu menu) {
		for (int i = InventoryMenu.CRAFT_SLOT_START; i < InventoryMenu.CRAFT_SLOT_END; i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack != null && !stack.isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Puts whatever is on the cursor back into the inventory, or throws it out
	 * if there is genuinely nowhere for it to go.
	 */
	private static void clearCursor(Minecraft mc, LocalPlayer player,
			AbstractContainerMenu menu) {
		ItemStack carried = menu.getCarried();
		if (carried.isEmpty()) {
			return;
		}
		Inventory inv = player.getInventory();

		// Prefer merging onto a matching partial stack, then any empty slot.
		for (int slot = 0; slot < InventoryScanner.MAIN_END; slot++) {
			ItemStack existing = inv.getItem(slot);
			if (existing != null && !existing.isEmpty()
					&& ItemStack.isSameItemSameComponents(existing, carried)
					&& existing.getCount() < existing.getMaxStackSize()) {
				mc.gameMode.handleContainerInput(menu.containerId,
						inventoryMenuIndex(slot), 0, ContainerInput.PICKUP, player);
				return;
			}
		}
		for (int slot = 0; slot < InventoryScanner.MAIN_END; slot++) {
			ItemStack existing = inv.getItem(slot);
			if (existing == null || existing.isEmpty()) {
				mc.gameMode.handleContainerInput(menu.containerId,
						inventoryMenuIndex(slot), 0, ContainerInput.PICKUP, player);
				return;
			}
		}
		// Nowhere to put it: clicking outside the window drops the held stack.
		mc.gameMode.handleContainerInput(menu.containerId,
				AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0,
				ContainerInput.PICKUP, player);
	}

	/**
	 * Empties one occupied crafting slot. Deliberately one click per call -
	 * batching container clicks into a single tick makes the server reject all
	 * but the first as stale.
	 */
	private static void returnGrid(Minecraft mc, LocalPlayer player,
			AbstractContainerMenu menu) {
		for (int i = InventoryMenu.CRAFT_SLOT_START; i < InventoryMenu.CRAFT_SLOT_END; i++) {
			ItemStack left = menu.slots.get(i).getItem();
			if (left != null && !left.isEmpty()) {
				mc.gameMode.handleContainerInput(
						menu.containerId, i, 0, ContainerInput.QUICK_MOVE, player);
				return;
			}
		}
	}

	private int readQuantity(AbstractContainerMenu menu) {
		if (qtySlot < 0 || qtySlot >= ShopNavigator.containerSlots(menu)) {
			return -1;
		}
		ItemStack stack = menu.slots.get(qtySlot).getItem();
		if (stack == null || stack.isEmpty()) {
			return -1;
		}
		return stack.getCount();
	}

	/** The slot on the buy screen holding the item being purchased. */
	private static int findSourceSlot(AbstractContainerMenu menu, String itemId) {
		int top = ShopNavigator.containerSlots(menu);
		for (int i = 0; i < top; i++) {
			ItemStack stack = menu.slots.get(i).getItem();
			if (stack != null && !stack.isEmpty()
					&& itemId.equals(InventoryScanner.idOf(stack))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Whether a slot is a quantity adjuster and which way it goes.
	 *
	 * @return TRUE to add, FALSE to remove, null when it is not an adjuster
	 */
	private static Boolean adjusterDirection(ItemStack stack) {
		StringBuilder text = new StringBuilder(stack.getHoverName().getString());
		for (String line : LoreReader.read(stack)) {
			text.append(' ').append(line);
		}
		// Check the raw text for +/- first: folding strips punctuation, and
		// these buttons are often labelled with nothing but a sign.
		String raw = text.toString();
		String folded = TextFold.normalize(raw);
		boolean add = raw.contains("+") || folded.contains("ADD")
				|| folded.contains("INCREASE") || folded.contains("MORE");
		boolean sub = raw.contains("-") || folded.contains("REMOVE")
				|| folded.contains("DECREASE") || folded.contains("LESS")
				|| folded.contains("SUBTRACT");
		if (add == sub) {
			return null; // neither, or contradictory
		}
		return add;
	}

	/** Price from the button's name or lore, e.g. "Click to purchase $6,528.00". */
	private static Double parsePrice(ItemStack stack) {
		Matcher m = PRICE.matcher(stack.getHoverName().getString());
		if (m.find()) {
			return toDouble(m.group(1));
		}
		for (String line : LoreReader.read(stack)) {
			Matcher lm = PRICE.matcher(line);
			if (lm.find()) {
				return toDouble(lm.group(1));
			}
		}
		return null;
	}

	private static Double toDouble(String raw) {
		try {
			return Double.parseDouble(raw.replace(",", ""));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Within reach of the configured farm spot. */
	private static boolean atFarmSpot(LocalPlayer player, AutoSellConfig cfg) {
		if (!cfg.hasFarmSpot()) {
			return true; // nothing configured; do not block the run
		}
		double d = player.distanceToSqr(
				cfg.farmX + 0.5, cfg.farmY + 0.5, cfg.farmZ + 0.5);
		double allowed = FARM_ARRIVE + 0.8;
		return d <= allowed * allowed;
	}

	private static int freeHotbarSlots(LocalPlayer player) {
		Inventory inv = player.getInventory();
		int free = 0;
		for (int i = 0; i < InventoryScanner.MAIN_START; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack == null || stack.isEmpty()) {
				free++;
			}
		}
		return free;
	}

	private static int findItemSlot(LocalPlayer player, String itemId) {
		Inventory inv = player.getInventory();
		for (int slot = 0; slot < InventoryScanner.MAIN_END; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack != null && !stack.isEmpty()
					&& itemId.equals(InventoryScanner.idOf(stack))) {
				return slot;
			}
		}
		return -1;
	}

	private static int countItem(LocalPlayer player, String itemId) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < InventoryScanner.MAIN_END; slot++) {
			ItemStack stack = inv.getItem(slot);
			if (stack != null && !stack.isEmpty()
					&& itemId.equals(InventoryScanner.idOf(stack))) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * Player inventory index (0-35) to its index in {@link InventoryMenu},
	 * where slots 9-35 map to 9-35 and the hotbar sits at 36-44.
	 */
	private static int inventoryMenuIndex(int playerSlot) {
		if (playerSlot >= InventoryScanner.MAIN_START) {
			return playerSlot; // INV_SLOT_START is also 9
		}
		return InventoryMenu.USE_ROW_SLOT_START + playerSlot;
	}

	private static int playerSlotToMenuIndex(int playerSlot, int chestSlots) {
		if (playerSlot >= InventoryScanner.MAIN_START) {
			return chestSlots + (playerSlot - InventoryScanner.MAIN_START);
		}
		return chestSlots + MENU_HOTBAR_OFFSET + playerSlot;
	}

	private static AbstractContainerMenu openContainerMenu(Minecraft mc) {
		Screen screen = mc.gui.screen();
		if (!(screen instanceof AbstractContainerScreen<?> container)) {
			return null;
		}
		AbstractContainerMenu menu = container.getMenu();
		if (menu == null || mc.player == null || menu == mc.player.inventoryMenu) {
			return null;
		}
		if (menu.slots.size() <= PLAYER_SLOTS_IN_MENU) {
			return null;
		}
		return menu;
	}
}
