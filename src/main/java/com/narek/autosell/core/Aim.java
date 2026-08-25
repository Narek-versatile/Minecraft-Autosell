package com.narek.autosell.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Aiming maths and rate-limited turning.
 *
 * <p>Snapping the view to an exact angle every tick is what made movement look
 * jerky, and it is also why block interaction failed: the server validates
 * where the player is actually looking, so a synthetic hit result aimed at a
 * block the client is not facing gets rejected. Turning is therefore capped per
 * tick, and interaction waits until the crosshair genuinely lands on the block.
 */
public final class Aim {

	private Aim() {
	}

	/** Yaw/pitch, in degrees, from an eye position to a point. */
	public static float[] anglesTo(Vec3 eye, Vec3 point) {
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		double flat = Math.sqrt(dx * dx + dz * dz);

		float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
		return new float[] {yaw, pitch};
	}

	/** Shortest signed difference between two angles, in [-180, 180). */
	public static float delta(float from, float to) {
		float d = (to - from) % 360F;
		if (d >= 180F) {
			d -= 360F;
		}
		if (d < -180F) {
			d += 360F;
		}
		return d;
	}

	/**
	 * Steps {@code current} toward {@code target} by at most {@code maxStep}
	 * degrees. Easing the last part of the turn removes the visible snap as the
	 * angle closes.
	 */
	public static float approach(float current, float target, float maxStep) {
		float d = delta(current, target);
		float step = Math.abs(d) < maxStep * 2F ? Math.abs(d) * 0.5F + 0.5F : maxStep;
		if (Math.abs(d) <= step) {
			return target;
		}
		return current + Math.signum(d) * step;
	}

	/** The point on a block to aim at: the centre of its most visible face. */
	public static Vec3 aimPoint(Vec3 eye, BlockPos block) {
		Vec3 centre = Vec3.atCenterOf(block);
		Direction face = faceToward(eye, block);
		return centre.add(
				face.getStepX() * 0.45,
				face.getStepY() * 0.45,
				face.getStepZ() * 0.45);
	}

	/** Whichever face of the block the eye is most directly in front of. */
	public static Direction faceToward(Vec3 eye, BlockPos block) {
		Vec3 centre = Vec3.atCenterOf(block);
		double dx = eye.x - centre.x;
		double dy = eye.y - centre.y;
		double dz = eye.z - centre.z;

		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double az = Math.abs(dz);

		if (ay >= ax && ay >= az) {
			return dy > 0 ? Direction.UP : Direction.DOWN;
		}
		if (ax >= az) {
			return dx > 0 ? Direction.EAST : Direction.WEST;
		}
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** True when the crosshair is genuinely on {@code block}. */
	public static boolean crosshairOn(Minecraft mc, BlockPos block) {
		return mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(block);
	}

	/** The live hit result if it is on {@code block}, else null. */
	public static BlockHitResult hitOn(Minecraft mc, BlockPos block) {
		if (mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& hit.getBlockPos().equals(block)) {
			return hit;
		}
		return null;
	}

	public static Vec3 eyeOf(LocalPlayer player) {
		return new Vec3(player.getX(), player.getEyeY(), player.getZ());
	}
}
