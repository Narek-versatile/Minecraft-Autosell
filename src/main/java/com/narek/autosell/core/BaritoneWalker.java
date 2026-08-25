package com.narek.autosell.core;

import com.narek.autosell.AutoSellClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Drives Baritone for movement when it is installed.
 *
 * <p>Baritone is a mature pathing implementation and handles the cases a
 * hand-rolled walker keeps failing: doors, gaps, water, rerouting, and not
 * getting wedged on terrain.
 *
 * <p>Reached entirely by reflection, deliberately. That keeps Baritone an
 * optional runtime dependency - the mod still builds and runs without it - and
 * avoids linking against an LGPL library, so nothing is imposed on this mod's
 * own licensing. Every signature used here was verified against
 * baritone-fabric-1.18.0 for MC 26.2.
 */
public final class BaritoneWalker {

	private static Boolean present;

	private static Method getProvider;
	private static Method getPrimaryBaritone;
	private static Method getCustomGoalProcess;
	private static Method getPathingBehavior;
	private static Method setGoalAndPath;
	private static Method isPathing;
	private static Method cancelEverything;
	private static Constructor<?> goalNear;
	private static Constructor<?> goalBlock;

	private BaritoneWalker() {
	}

	/** True when Baritone is installed and its API resolved cleanly. */
	public static boolean isAvailable() {
		if (present == null) {
			present = resolve();
		}
		return present;
	}

	private static boolean resolve() {
		if (!FabricLoader.getInstance().isModLoaded("baritone")) {
			return false;
		}
		try {
			Class<?> api = Class.forName("baritone.api.BaritoneAPI");
			Class<?> provider = Class.forName("baritone.api.IBaritoneProvider");
			Class<?> baritone = Class.forName("baritone.api.IBaritone");
			Class<?> custom = Class.forName("baritone.api.process.ICustomGoalProcess");
			Class<?> pathing = Class.forName("baritone.api.behavior.IPathingBehavior");
			Class<?> goal = Class.forName("baritone.api.pathing.goals.Goal");

			getProvider = api.getMethod("getProvider");
			getPrimaryBaritone = provider.getMethod("getPrimaryBaritone");
			getCustomGoalProcess = baritone.getMethod("getCustomGoalProcess");
			getPathingBehavior = baritone.getMethod("getPathingBehavior");
			setGoalAndPath = custom.getMethod("setGoalAndPath", goal);
			isPathing = pathing.getMethod("isPathing");
			cancelEverything = pathing.getMethod("cancelEverything");

			goalNear = Class.forName("baritone.api.pathing.goals.GoalNear")
					.getConstructor(BlockPos.class, int.class);
			goalBlock = Class.forName("baritone.api.pathing.goals.GoalBlock")
					.getConstructor(BlockPos.class);

			AutoSellClient.LOGGER.info("[autosell] Baritone detected - using it for movement");
			return true;
		} catch (Throwable t) {
			AutoSellClient.LOGGER.warn(
					"[autosell] Baritone present but its API did not resolve; "
							+ "falling back to the built-in walker", t);
			return false;
		}
	}

	private static Object baritone() throws Exception {
		Object provider = getProvider.invoke(null);
		return getPrimaryBaritone.invoke(provider);
	}

	/**
	 * Sends Baritone to {@code target}.
	 *
	 * @param radius how close counts as arrived; 0 means the exact block
	 * @return false when the request could not be issued
	 */
	public static boolean goTo(BlockPos target, int radius) {
		if (!isAvailable() || target == null) {
			return false;
		}
		try {
			Object goal = radius <= 0
					? goalBlock.newInstance(target)
					: goalNear.newInstance(target, radius);
			Object process = getCustomGoalProcess.invoke(baritone());
			setGoalAndPath.invoke(process, goal);
			return true;
		} catch (Throwable t) {
			AutoSellClient.LOGGER.error("[autosell] Baritone goTo failed", t);
			return false;
		}
	}

	/** True while Baritone is actively moving the player. */
	public static boolean isPathing() {
		if (!isAvailable()) {
			return false;
		}
		try {
			return (Boolean) isPathing.invoke(getPathingBehavior.invoke(baritone()));
		} catch (Throwable t) {
			return false;
		}
	}

	public static void cancel() {
		if (!isAvailable()) {
			return;
		}
		try {
			cancelEverything.invoke(getPathingBehavior.invoke(baritone()));
		} catch (Throwable t) {
			AutoSellClient.LOGGER.error("[autosell] Baritone cancel failed", t);
		}
	}

	/** Straight-line distance to a block, for arrival checks. */
	public static double distanceTo(BlockPos target) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || target == null) {
			return -1;
		}
		return Math.sqrt(mc.player.distanceToSqr(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
	}
}
