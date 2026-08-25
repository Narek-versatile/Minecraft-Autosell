package com.narek.autosell.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Turns to face a block, then right-clicks it once the crosshair is really on
 * it.
 *
 * <p>Fabricating a {@code BlockHitResult} and firing it immediately was the
 * bug behind chests not opening: the server checks where the player is looking,
 * so an interaction sent while facing elsewhere is discarded - which looks
 * exactly like "staring into the air". This aims first, verifies against the
 * live crosshair, and only then interacts, the same order a player does it.
 */
public final class BlockOpener {

	/** Degrees per tick while turning to face the block. */
	private static final float TURN_RATE = 22F;
	/** Give up aiming after this long. */
	private static final int AIM_TIMEOUT = 60;
	/** Ticks to wait between interaction attempts. */
	private static final int RETRY_DELAY = 8;
	/** Vanilla interaction reach, with a little margin. */
	private static final double MAX_REACH = 4.5;

	public enum Status {
		IDLE,
		AIMING,
		OUT_OF_RANGE,
		OPENED,
		FAILED
	}

	private BlockPos target;
	private boolean active;
	private int ticks;
	private int attempts;
	private int cooldown;

	public void start(BlockPos target) {
		this.target = target;
		this.active = true;
		this.ticks = 0;
		this.attempts = 0;
		this.cooldown = 0;
	}

	/**
	 * Restarts if idle or aimed elsewhere.
	 *
	 * <p>Callers used to skip {@link #start} when the target matched, which
	 * silently reused an already-exhausted retry budget and reported failure
	 * immediately on a later run.
	 */
	public void ensureTargeting(BlockPos pos) {
		if (!active || target == null || !target.equals(pos) || attempts >= 4) {
			start(pos);
		}
	}

	public void stop() {
		this.active = false;
		this.target = null;
		this.attempts = 0;
		this.ticks = 0;
		this.cooldown = 0;
	}

	public boolean isActive() {
		return active;
	}

	public BlockPos target() {
		return target;
	}

	public Status tick() {
		if (!active || target == null) {
			return Status.IDLE;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null || mc.gameMode == null) {
			return Status.FAILED;
		}

		Vec3 eye = Aim.eyeOf(player);
		Vec3 point = Aim.aimPoint(eye, target);

		if (eye.distanceTo(Vec3.atCenterOf(target)) > MAX_REACH) {
			return Status.OUT_OF_RANGE;
		}

		// Ease the view onto the block rather than snapping to it.
		float[] want = Aim.anglesTo(eye, point);
		player.setYRot(Aim.approach(player.getYRot(), want[0], TURN_RATE));
		player.setXRot(Aim.approach(player.getXRot(), want[1], TURN_RATE));

		if (cooldown > 0) {
			cooldown--;
			return Status.AIMING;
		}

		// Only interact once the crosshair is genuinely on the block, and use
		// the real hit result so the face and hit vector match what the server
		// will recompute on its side.
		BlockHitResult hit = Aim.hitOn(mc, target);
		if (hit != null) {
			mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
			player.swing(InteractionHand.MAIN_HAND);
			cooldown = RETRY_DELAY;
			if (++attempts >= 4) {
				return Status.FAILED;
			}
			return Status.AIMING;
		}

		if (++ticks > AIM_TIMEOUT) {
			return Status.FAILED;
		}
		return Status.AIMING;
	}
}
