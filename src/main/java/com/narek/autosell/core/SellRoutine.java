package com.narek.autosell.core;

import com.narek.autosell.AutoSellClient;
import com.narek.autosell.config.AutoSellConfig;
import com.narek.autosell.profit.ProfitTracker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The whole run, as a tick-driven state machine.
 *
 * <p>Flow: clear any trash to the dump chest first, walk back, then loot the
 * whitelist, sell, and repeat until the chest is dry.
 *
 * <p>Deliberately never blocks: every wait is a tick countdown so the render
 * thread is never parked. Each step re-reads live client state rather than
 * trusting a snapshot, because the server can move items underneath us.
 */
public final class SellRoutine {

	public enum State {
		IDLE,
		PRE_CHECK,
		DUMP_WALK,
		DUMP_OPEN,
		DUMP_AWAIT,
		DUMP_DEPOSIT,
		DUMP_CLOSE,
		RETURN_WALK,
		OPEN_CHEST,
		AWAIT_CONTAINER,
		LOOT,
		CLOSE_CONTAINER,
		VERIFY,
		SELL,
		AWAIT_SELL
	}

	/** Player-side slot count present at the tail of every container menu. */
	private static final int PLAYER_SLOTS_IN_MENU = 36;
	/** Where the hotbar begins within a menu's player-slot block. */
	private static final int MENU_HOTBAR_OFFSET = 27;

	private final Walker walker = new Walker();
	private final BlockOpener opener = new BlockOpener();

	private State state = State.IDLE;
	private int delay;
	private int timeout;
	private int cycles;
	private boolean chestExhausted;
	private int lootClicks;
	private BlockPos chestPos;
	private Direction chestFace;
	private boolean dumpedThisRun;

	public boolean isRunning() {
		return state != State.IDLE;
	}

	public State state() {
		return state;
	}

	public int cycles() {
		return cycles;
	}

	public Walker walker() {
		return walker;
	}

	/** Entry point from the triple-press. */
	public void start() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (isRunning()) {
			abort("Cancelled by triple-press.");
			return;
		}

		AutoSellConfig cfg = AutoSellConfig.get();
		resetState();

		// Remember the loot chest so we can walk back to it after a dump trip.
		if (mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK) {
			chestPos = hit.getBlockPos();
			chestFace = hit.getDirection();
			cfg.setLootChest(chestPos.getX(), chestPos.getY(), chestPos.getZ());
		} else if (cfg.hasLootChest()) {
			chestPos = new BlockPos(cfg.lootX, cfg.lootY, cfg.lootZ);
			chestFace = Direction.UP;
		}

		AutoSellClient.chat(Component.literal("AutoSell started.")
				.withStyle(ChatFormatting.GRAY));
		transition(State.PRE_CHECK);
	}

	/** Clears every run-scoped field so a later run cannot inherit leftovers. */
	private void resetState() {
		delay = 0;
		timeout = 0;
		cycles = 0;
		chestExhausted = false;
		dumpedThisRun = false;
		lootClicks = 0;
		chestPos = null;
		chestFace = null;
		walker.stop();
		opener.stop();
	}

	public void abort(String reason) {
		DebugLog.log("sell", "ABORT in " + state + " - " + reason);
		state = State.IDLE;
		resetState();
		walker.stop();
		opener.stop();
		AutoSellClient.chat(Component.literal("AutoSell: " + reason)
				.withStyle(ChatFormatting.RED));
	}

	private void finish() {
		DebugLog.log("sell", "FINISH after " + cycles + " cycle(s)");
		state = State.IDLE;
		int done = cycles;
		resetState();
		cycles = done;
		walker.stop();
		AutoSellClient.chat(Component.literal(
						"AutoSell finished - " + cycles + " cycle(s), "
								+ ProfitTracker.get().formatRunTotal() + " this run.")
				.withStyle(ChatFormatting.GREEN));
	}

	private void transition(State next) {
		if (next != this.state) {
			DebugLog.log("sell", state + " -> " + next
					+ " cycle=" + cycles + " exhausted=" + chestExhausted
					+ " dumped=" + dumpedThisRun + " clicks=" + lootClicks);
		}
		this.state = next;
		this.timeout = 0;
	}

	public void tick() {
		if (state == State.IDLE) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.getConnection() == null) {
			abort("Disconnected mid-run.");
			return;
		}
		if (delay > 0) {
			delay--;
			return;
		}

		AutoSellConfig cfg = AutoSellConfig.get();

		switch (state) {
			case PRE_CHECK -> tickPreCheck(player, cfg);
			case DUMP_WALK -> tickWalk(cfg, dumpPos(cfg), State.DUMP_OPEN, "dump chest");
			case DUMP_OPEN -> tickOpenAt(mc, player, cfg, dumpPos(cfg), State.DUMP_AWAIT);
			case DUMP_AWAIT -> tickAwaitContainer(mc, cfg, State.DUMP_DEPOSIT);
			case DUMP_DEPOSIT -> tickDeposit(mc, player, cfg);
			case DUMP_CLOSE -> tickClose(mc, player, cfg, State.RETURN_WALK);
			case RETURN_WALK -> tickWalk(cfg, chestPos, State.OPEN_CHEST, "loot chest");
			case OPEN_CHEST -> tickOpenChest(mc, player, cfg);
			case AWAIT_CONTAINER -> tickAwaitContainer(mc, cfg, State.LOOT);
			case LOOT -> tickLoot(mc, player, cfg);
			case CLOSE_CONTAINER -> tickClose(mc, player, cfg, State.VERIFY);
			case VERIFY -> tickVerify(player, cfg);
			case SELL -> tickSell(mc, player, cfg);
			case AWAIT_SELL -> tickAwaitSell(cfg);
			default -> {
			}
		}
	}

	// -- states ------------------------------------------------------------

	/** Decide whether a dump trip is needed before we start looting. */
	private void tickPreCheck(LocalPlayer player, AutoSellConfig cfg) {
		List<InventoryScanner.Offender> offenders = InventoryScanner.findOffenders(player);
		if (offenders.isEmpty()) {
			transition(State.OPEN_CHEST);
			return;
		}
		if (!cfg.walkEnabled || !cfg.hasDumpChest()) {
			reportOffenders(offenders, cfg);
			state = State.IDLE;
			return;
		}
		if (dumpedThisRun) {
			// We already made the trip and trash is still here - stop rather
			// than loop between the two chests forever.
			reportOffenders(offenders, cfg);
			state = State.IDLE;
			return;
		}

		AutoSellClient.chat(Component.literal(
						"AutoSell: " + offenders.size()
								+ " unwanted stack(s) - walking to the dump chest.")
				.withStyle(ChatFormatting.YELLOW));
		dumpedThisRun = true;
		walker.start(dumpPos(cfg));
		transition(State.DUMP_WALK);
	}

	private void tickWalk(AutoSellConfig cfg, BlockPos target, State next, String label) {
		if (target == null) {
			abort("No " + label + " position known.");
			return;
		}
		if (!walker.isActive()) {
			walker.start(target);
			AutoSellClient.chat(Component.literal(
							"AutoSell: walking to " + label + " (" + (walker.hasRoute()
									? walker.waypointsRemaining() + " waypoints"
									: "direct") + ")")
					.withStyle(ChatFormatting.GRAY));
		}
		switch (walker.tick()) {
			case ARRIVED -> {
				delay = cfg.clickDelayTicks;
				transition(next);
			}
			case TIMEOUT -> abort("Gave up walking to the " + label + " (timeout).");
			case STUCK -> abort("Stuck on the way to the " + label
					+ ". Clear the path or walk it yourself, then triple-press again.");
			default -> {
			}
		}
	}

	/** Aims at the chest and interacts once the crosshair is on it. */
	private void tickOpenAt(Minecraft mc, LocalPlayer player, AutoSellConfig cfg,
			BlockPos pos, State next) {
		if (pos == null) {
			abort("No target chest position.");
			return;
		}
		if (openContainerMenu(mc) != null) {
			opener.stop();
			transition(next);
			return;
		}
		opener.ensureTargeting(pos);
		switch (opener.tick()) {
			case OUT_OF_RANGE -> {
				// Walk the rest of the way in, then aim again.
				opener.stop();
				walker.start(pos);
				transition(state == State.DUMP_OPEN ? State.DUMP_WALK : State.RETURN_WALK);
			}
			case FAILED -> {
				opener.stop();
				abort("Could not open the chest at " + pos.getX() + ", " + pos.getY()
						+ ", " + pos.getZ() + " - is it still there?");
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

	private void tickOpenChest(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		// Auto-detect: an already-open container wins over ray-casting.
		if (openContainerMenu(mc) != null) {
			lootClicks = 0;
			transition(State.LOOT);
			return;
		}

		if (chestPos == null) {
			if (mc.hitResult instanceof BlockHitResult hit
					&& hit.getType() == HitResult.Type.BLOCK) {
				chestPos = hit.getBlockPos();
			} else {
				abort("No chest open and none in your crosshair. "
						+ "Look at the chest, then triple-press.");
				return;
			}
		}
		opener.ensureTargeting(chestPos);
		switch (opener.tick()) {
			case OUT_OF_RANGE -> {
				opener.stop();
				walker.start(chestPos);
				transition(State.RETURN_WALK);
			}
			case FAILED -> {
				opener.stop();
				abort("Could not open the loot chest - is it still there?");
			}
			default -> {
				if (openContainerMenu(mc) != null) {
					opener.stop();
					delay = cfg.clickDelayTicks;
					transition(State.AWAIT_CONTAINER);
				} else {
					delay = 0;
					transition(State.AWAIT_CONTAINER);
				}
			}
		}
	}

	private void tickAwaitContainer(Minecraft mc, AutoSellConfig cfg, State next) {
		if (openContainerMenu(mc) != null) {
			opener.stop();
			lootClicks = 0;
			delay = cfg.clickDelayTicks;
			transition(next);
			return;
		}
		// Keep aiming and retrying while we wait.
		if (opener.isActive() && opener.tick() == BlockOpener.Status.FAILED) {
			opener.stop();
			abort("Chest did not respond to being opened.");
			return;
		}
		if (++timeout > cfg.containerTimeoutTicks) {
			opener.stop();
			abort("Chest did not open in time.");
		}
	}

	/** Deposit every non-gear stack into the open dump chest. */
	private void tickDeposit(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
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

		// Everything goes in: the dump runs before looting, so there is nothing
		// worth preserving, and a partial dump left odds and ends behind.
		List<Integer> toDeposit = InventoryScanner.findDepositSlots(player, false);
		if (toDeposit.isEmpty()) {
			AutoSellClient.chat(Component.literal("AutoSell: trash deposited.")
					.withStyle(ChatFormatting.GREEN));
			transition(State.DUMP_CLOSE);
			return;
		}

		int fired = 0;
		for (int playerSlot : toDeposit) {
			int menuIndex = playerSlotToMenuIndex(playerSlot, chestSlots);
			mc.gameMode.handleContainerInput(
					menu.containerId, menuIndex, 0, ContainerInput.QUICK_MOVE, player);
			if (++fired >= cfg.burstMaxClicks || !cfg.burstLoot) {
				break;
			}
		}
		delay = cfg.clickDelayTicks;

		if (++lootClicks > 200) {
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

	private void tickLoot(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		AbstractContainerMenu menu = openContainerMenu(mc);
		if (menu == null) {
			// Server closed the GUI on us; treat what we have as the load.
			transition(State.VERIFY);
			return;
		}

		int chestSlots = menu.slots.size() - PLAYER_SLOTS_IN_MENU;
		if (chestSlots <= 0) {
			abort("Open screen is not a chest-like container.");
			return;
		}

		int fired = 0;
		boolean sawSellable = false;
		boolean ranOutOfRoom = false;

		for (int i = 0; i < chestSlots; i++) {
			Slot slot = menu.slots.get(i);
			ItemStack stack = slot.getItem();
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (!InventoryScanner.isSellable(stack)) {
				continue; // whitelist policy: leave anything unlisted alone
			}
			sawSellable = true;

			if (!InventoryScanner.hasRoomFor(player, stack)) {
				ranOutOfRoom = true;
				break;
			}

			// QUICK_MOVE == shift-click; the server decides the destination.
			// Vanilla's shift+double-click fires exactly this, once per matching
			// slot, all inside a single tick - so bursting matches what the
			// client already does when a player uses that shortcut.
			mc.gameMode.handleContainerInput(
					menu.containerId, i, 0, ContainerInput.QUICK_MOVE, player);
			lootClicks++;
			fired++;

			if (!cfg.burstLoot || fired >= cfg.burstMaxClicks) {
				break;
			}
		}

		// A rejected click leaves the slot untouched, so without a ceiling we
		// would re-click the same slot forever.
		if (lootClicks > chestSlots * 3 + 40) {
			abort("Chest stopped responding to transfers - stopping to avoid a loop.");
			return;
		}

		if (ranOutOfRoom) {
			transition(State.CLOSE_CONTAINER);
			return;
		}
		if (!sawSellable) {
			chestExhausted = true;
			transition(State.CLOSE_CONTAINER);
			return;
		}
		delay = cfg.clickDelayTicks;
	}

	private void tickVerify(LocalPlayer player, AutoSellConfig cfg) {
		List<InventoryScanner.Offender> offenders = InventoryScanner.findOffenders(player);
		if (!offenders.isEmpty()) {
			if (cfg.walkEnabled && cfg.hasDumpChest() && !dumpedThisRun) {
				dumpedThisRun = true;
				walker.start(dumpPos(cfg));
				transition(State.DUMP_WALK);
				return;
			}
			reportOffenders(offenders, cfg);
			state = State.IDLE;
			return;
		}
		if (InventoryScanner.isStorageEmpty(player)) {
			if (cycles == 0) {
				abort("Nothing sellable found - inventory and chest are empty.");
			} else {
				finish();
			}
			return;
		}
		transition(State.SELL);
	}

	private void tickSell(Minecraft mc, LocalPlayer player, AutoSellConfig cfg) {
		ProfitTracker.get().armForSale();
		mc.getConnection().sendCommand(cfg.sellCommand);
		transition(State.AWAIT_SELL);
	}

	private void tickAwaitSell(AutoSellConfig cfg) {
		if (ProfitTracker.get().consumeSaleConfirmed()) {
			cycles++;
			if (cfg.loopUntilEmpty && !chestExhausted && cycles < cfg.maxCycles) {
				delay = cfg.clickDelayTicks;
				lootClicks = 0;
				transition(State.OPEN_CHEST);
			} else {
				if (cycles >= cfg.maxCycles && !chestExhausted) {
					AutoSellClient.chat(Component.literal(
									"AutoSell: hit the " + cfg.maxCycles
											+ "-cycle safety cap; chest may still hold items.")
							.withStyle(ChatFormatting.YELLOW));
				}
				finish();
			}
			return;
		}
		if (++timeout > cfg.sellTimeoutTicks) {
			abort("No sell confirmation from the server after "
					+ (cfg.sellTimeoutTicks / 20) + "s. Check /autosell regex.");
		}
	}

	// -- helpers -----------------------------------------------------------

	private static BlockPos dumpPos(AutoSellConfig cfg) {
		return cfg.hasDumpChest() ? new BlockPos(cfg.dumpX, cfg.dumpY, cfg.dumpZ) : null;
	}

	/**
	 * Maps a player inventory index (0-35) to its index inside a container
	 * menu, where main storage comes first and the hotbar last.
	 */
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
		if (menu == null || mc.player == null) {
			return null;
		}
		if (menu == mc.player.inventoryMenu) {
			return null;
		}
		if (menu.slots.size() <= PLAYER_SLOTS_IN_MENU) {
			return null;
		}
		return menu;
	}

	private BlockHitResult resolveChestHit(Minecraft mc) {
		if (chestPos != null && chestFace != null) {
			return new BlockHitResult(Vec3.atCenterOf(chestPos), chestFace, chestPos, false);
		}
		if (mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK) {
			return hit;
		}
		return null;
	}

	private void reportOffenders(List<InventoryScanner.Offender> offenders, AutoSellConfig cfg) {
		AutoSellClient.chat(Component.literal(
						"AutoSell blocked - " + offenders.size()
								+ " unwanted stack(s) in storage. Nothing was sold.")
				.withStyle(ChatFormatting.RED));

		for (InventoryScanner.Offender o : offenders) {
			AutoSellClient.chat(Component.literal(
							"  slot " + o.slot() + ": " + o.itemId() + " x" + o.count())
					.withStyle(ChatFormatting.YELLOW));
		}

		if (cfg.hasDumpChest()) {
			String where = cfg.dumpX + ", " + cfg.dumpY + ", " + cfg.dumpZ;
			Minecraft mc = Minecraft.getInstance();
			String distance = "";
			if (mc.player != null) {
				double d = Math.sqrt(mc.player.distanceToSqr(
						cfg.dumpX + 0.5, cfg.dumpY + 0.5, cfg.dumpZ + 0.5));
				distance = String.format(" (%.0f blocks away)", d);
			}
			AutoSellClient.chat(Component.literal(
							"  Drop them at your dump chest: " + where + distance)
					.withStyle(ChatFormatting.AQUA));
		} else {
			AutoSellClient.chat(Component.literal(
							"  No dump chest set. Look at one and run /autosell dump set")
					.withStyle(ChatFormatting.GRAY));
		}
		AutoSellClient.chat(Component.literal("  Triple-press again once storage is clean.")
				.withStyle(ChatFormatting.GRAY));
	}
}
