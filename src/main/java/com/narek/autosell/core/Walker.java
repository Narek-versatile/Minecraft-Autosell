package com.narek.autosell.core;

import com.narek.autosell.config.AutoSellConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows an A* route, steering smoothly rather than snapping.
 *
 * <p>Three things made the earlier version jerky and unreliable, all fixed
 * here: the view snapped to an exact angle every tick, it sprinted flat out
 * regardless of what was coming, and it jumped whenever it failed to progress -
 * including in mid-air after a fall, which just produced bunny-hopping.
 *
 * <p>Now the turn is rate-limited, forward speed scales down for sharp turns
 * and near the destination, and jumping requires being on the ground with an
 * actual reason.
 */
public final class Walker {

	public enum Status {
		IDLE,
		WALKING,
		ARRIVED,
		TIMEOUT,
		STUCK
	}

	/** Max degrees to turn per tick. */
	private static final float TURN_RATE = 18F;
	/** Beyond this heading error, turn before committing to speed. */
	private static final float SHARP_TURN = 60F;
	private static final int STUCK_TICKS = 60;
	private static final double PROGRESS_EPSILON = 0.02;
	private static final double WAYPOINT_REACH = 0.8;
	private static final int REPATH_INTERVAL = 200;
	/** Minimum gap between jumps, so it cannot bunny-hop. */
	private static final int JUMP_COOLDOWN = 10;
	/** How long Baritone may sit idle before we nudge it again. */
	private static final int BARITONE_IDLE_TICKS = 40;
	private static final int BARITONE_MAX_RETRIES = 5;
	/** How close the exact-position mode has to get, in blocks. */
	private static final double ALIGN_TOLERANCE = 0.12;
	/** Close enough when it cannot do better, e.g. wedged between blocks. */
	private static final double ALIGN_GOOD_ENOUGH = 0.4;
	private static final double ALIGN_DEADZONE = 0.04;
	private static final double ALIGN_MIN_SPEED = 0.12;
	private static final double ALIGN_MAX_SPEED = 0.45;
	private static final double ALIGN_PROGRESS_EPSILON = 0.01;
	private static final int ALIGN_NO_PROGRESS_TICKS = 30;
	private static final int ALIGN_TIMEOUT = 200;
	/** Start easing off once this close to the final target. */
	private static final double SLOW_RADIUS = 3.0;

	private BlockPos target;
	private double arriveDistance = 3.0;
	/** True while movement is delegated to Baritone. */
	private boolean usingBaritone;
	private int baritoneGrace;
	/** Exact-position mode: strafe onto a point without turning. */
	private Vec3 alignPoint;
	private float alignYaw;
	private int alignTicks;
	private double alignBestDist = Double.MAX_VALUE;
	private int alignNoProgress;
	private int baritoneRetries;
	private List<BlockPos> route = new ArrayList<>();
	private int legIndex;
	private boolean active;
	private int ticks;
	private int stuckTicks;
	private int sinceRepath;
	private int jumpCooldown;
	private int repathAttempts;
	private double lastDistSq = Double.MAX_VALUE;
	private boolean pathed;

	private boolean jumpThisTick;
	private float speed = 1F;
	private boolean sprintThisTick;
	private float alignForward;
	private float alignStrafe;

	public void start(BlockPos target) {
		start(target, AutoSellConfig.get().reachDistance);
	}

	/**
	 * @param arriveDistance how close counts as arrived. A chest only needs to
	 *                       be in reach; a spot you must stand on does not.
	 */
	public void start(BlockPos target, double arriveDistance) {
		this.target = target;
		this.arriveDistance = arriveDistance;
		// Baritone handles doors, gaps, water and rerouting far better than the
		// built-in walker; use it whenever it is installed.
		this.usingBaritone = BaritoneWalker.isAvailable()
				&& BaritoneWalker.goTo(target, (int) Math.max(0, Math.floor(arriveDistance)));
		this.baritoneGrace = 0;
		this.baritoneRetries = 0;
		this.active = true;
		this.ticks = 0;
		this.stuckTicks = 0;
		this.sinceRepath = 0;
		this.repathAttempts = 0;
		this.jumpCooldown = 0;
		this.lastDistSq = Double.MAX_VALUE;
		solveRoute();
	}

	/**
	 * Fine alignment onto an exact position while holding {@code holdYaw}.
	 *
	 * <p>Moves by strafing rather than turning, because the facing is what
	 * aims the throw - turning to walk would ruin the aim it just set.
	 */
	public void startAlign(Vec3 point, float holdYaw) {
		this.alignPoint = point;
		this.alignYaw = holdYaw;
		this.alignTicks = 0;
		this.alignBestDist = Double.MAX_VALUE;
		this.alignNoProgress = 0;
		this.active = true;
		this.usingBaritone = false;
		this.target = null;
	}

	public boolean isAligning() {
		return active && alignPoint != null;
	}

	/**
	 * @return ARRIVED once on the spot, TIMEOUT if it cannot settle
	 */
	public Status tickAlign() {
		if (alignPoint == null) {
			return Status.IDLE;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null) {
			stop();
			return Status.IDLE;
		}

		double dx = alignPoint.x - player.getX();
		double dz = alignPoint.z - player.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);

		// Hold the throw angle throughout.
		player.setYRot(alignYaw);

		if (dist <= ALIGN_TOLERANCE) {
			stop();
			return Status.ARRIVED;
		}
		if (++alignTicks > ALIGN_TIMEOUT) {
			stop();
			return Status.TIMEOUT;
		}

		// Give up gracefully rather than grinding against geometry: a spot
		// wedged between blocks may simply not be reachable to the centimetre.
		if (dist < alignBestDist - ALIGN_PROGRESS_EPSILON) {
			alignBestDist = dist;
			alignNoProgress = 0;
		} else if (++alignNoProgress > ALIGN_NO_PROGRESS_TICKS) {
			stop();
			return dist <= ALIGN_GOOD_ENOUGH ? Status.ARRIVED : Status.TIMEOUT;
		}

		// Project the offset onto the held facing.
		double yawRad = Math.toRadians(alignYaw);
		double forwardX = -Math.sin(yawRad);
		double forwardZ = Math.cos(yawRad);
		double forward = dx * forwardX + dz * forwardZ;
		double right = dx * forwardZ - dz * forwardX;

		// moveVector is (leftImpulse, forwardImpulse): vanilla builds it as
		// Vec2(calculateImpulse(left, right), calculateImpulse(forward, back)),
		// so +x is LEFT. Moving right therefore needs a negative x - feeding
		// the rightward component in directly steers the wrong way and turns
		// this into a positive feedback loop that oscillates on the spot.
		double left = -right;

		// Deadzone per axis, so a satisfied axis stops contributing instead of
		// hunting around zero.
		if (Math.abs(forward) < ALIGN_DEADZONE) {
			forward = 0;
		}
		if (Math.abs(left) < ALIGN_DEADZONE) {
			left = 0;
		}
		if (forward == 0 && left == 0) {
			stop();
			return Status.ARRIVED;
		}

		// Proportional speed, capped low: overshooting a 0.12-block target is
		// what makes fine positioning oscillate.
		double magnitude = Math.max(Math.abs(forward), Math.abs(left));
		double speedScale = Math.min(ALIGN_MAX_SPEED,
				Math.max(ALIGN_MIN_SPEED, dist * 0.9)) / magnitude;

		alignForward = (float) (forward * speedScale);
		alignStrafe = (float) (left * speedScale);
		return Status.WALKING;
	}

	public void stop() {
		alignPoint = null;
		alignForward = 0F;
		alignStrafe = 0F;
		if (usingBaritone) {
			BaritoneWalker.cancel();
			usingBaritone = false;
		}
		this.active = false;
		this.target = null;
		this.route = new ArrayList<>();
		this.legIndex = 0;
		this.jumpThisTick = false;
		this.sprintThisTick = false;
		this.speed = 1F;
	}

	public boolean isActive() {
		return active;
	}

	public boolean hasRoute() {
		return usingBaritone || (pathed && legIndex < route.size());
	}

	/** True when Baritone is doing the walking. */
	public boolean isDelegated() {
		return usingBaritone;
	}

	public int waypointsRemaining() {
		return Math.max(0, route.size() - legIndex);
	}

	public boolean inLiquid() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && (mc.player.isInWater() || mc.player.isInLava());
	}

	/** True while Baritone is idle mid-route (planning, not failed). */
	public boolean isReplanning() {
		return usingBaritone && baritoneGrace > 5;
	}

	public double distanceRemaining() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || target == null) {
			return -1;
		}
		return Math.sqrt(mc.player.distanceToSqr(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5));
	}

	private void solveRoute() {
		Minecraft mc = Minecraft.getInstance();
		route = new ArrayList<>();
		legIndex = 0;
		pathed = false;
		if (mc.player == null || mc.level == null || target == null) {
			return;
		}
		List<BlockPos> found = PathFinder.find(mc.level, mc.player.blockPosition(), target);
		if (!found.isEmpty()) {
			route = found;
			pathed = true;
		}
	}

	// -- input handed to the mixin ----------------------------------------

	public Input buildInput() {
		if (alignPoint != null) {
			// alignStrafe is already the LEFT impulse, matching moveVector.x,
			// so the booleans must read it the same way or the two disagree
			// and cancel each other out.
			return new Input(alignForward > 0.01F, alignForward < -0.01F,
					alignStrafe > 0.01F, alignStrafe < -0.01F, false, false, false);
		}
		return new Input(true, false, false, false, jumpThisTick, false, sprintThisTick);
	}

	/** Forward impulse for this tick; magnitude below 1 walks slower. */
	public Vec2 moveVector() {
		if (alignPoint != null) {
			return new Vec2(alignStrafe, alignForward);
		}
		return new Vec2(0F, speed);
	}

	// -- the step ----------------------------------------------------------

	public Status tick() {
		if (!active || target == null) {
			return Status.IDLE;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			stop();
			return Status.IDLE;
		}
		AutoSellConfig cfg = AutoSellConfig.get();
		Vec3 pos = player.position();

		if (usingBaritone) {
			return tickBaritone(cfg);
		}

		double targetDistSq = player.distanceToSqr(
				target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
		if (targetDistSq <= arriveDistance * arriveDistance) {
			stop();
			return Status.ARRIVED;
		}
		if (++ticks > cfg.walkTimeoutTicks) {
			stop();
			return Status.TIMEOUT;
		}
		if (jumpCooldown > 0) {
			jumpCooldown--;
		}

		retireReachedWaypoints(pos);

		boolean inLiquid = player.isInWater() || player.isInLava();
		boolean falling = !player.onGround() && !inLiquid;

		// -- steering ------------------------------------------------------
		double[] aim = currentAim();
		double dx = aim[0] - pos.x;
		double dz = aim[1] - pos.z;

		float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float error = Math.abs(Aim.delta(player.getYRot(), wantYaw));
		player.setYRot(Aim.approach(player.getYRot(), wantYaw, TURN_RATE));
		player.setXRot(Aim.approach(player.getXRot(), 0F, TURN_RATE));

		// -- speed ---------------------------------------------------------
		double distance = Math.sqrt(targetDistSq);
		speed = 1F;
		if (error > SHARP_TURN) {
			// Turning hard: creep so we do not overshoot the corner.
			speed = 0.35F;
		} else if (error > 20F) {
			speed = 0.7F;
		}
		if (distance < SLOW_RADIUS) {
			speed = Math.min(speed, 0.45F);
		}
		if (falling) {
			// No steering authority mid-air; do not fight the fall.
			speed = Math.min(speed, 0.5F);
		}
		sprintThisTick = cfg.walkSprint
				&& !inLiquid
				&& !falling
				&& error < 15F
				&& distance > 6.0
				&& waypointsRemaining() != 1;

		// -- jumping -------------------------------------------------------
		jumpThisTick = false;
		if (inLiquid) {
			jumpThisTick = true; // swim up / stay at the surface
		} else if (!falling && jumpCooldown == 0) {
			boolean stepUp = legIndex < route.size()
					&& route.get(legIndex).getY() > player.blockPosition().getY();
			boolean blocked = player.horizontalCollision && player.onGround();
			if (stepUp || blocked) {
				jumpThisTick = true;
				jumpCooldown = JUMP_COOLDOWN;
			}
		}

		// -- progress ------------------------------------------------------
		int stuckLimit = inLiquid ? STUCK_TICKS * 3 : STUCK_TICKS;
		if (falling) {
			// Falling is progress of a sort; do not accumulate stuck time.
			stuckTicks = 0;
		} else if (lastDistSq - targetDistSq < PROGRESS_EPSILON) {
			if (++stuckTicks > stuckLimit) {
				if (repathAttempts < 3) {
					repathAttempts++;
					stuckTicks = 0;
					sinceRepath = 0;
					solveRoute();
				} else {
					stop();
					return Status.STUCK;
				}
			}
		} else {
			stuckTicks = 0;
		}
		lastDistSq = targetDistSq;

		if (pathed && ++sinceRepath > REPATH_INTERVAL) {
			sinceRepath = 0;
			solveRoute();
		}
		return Status.WALKING;
	}

	/**
	 * Baritone owns the movement; we only watch for arrival or for it giving
	 * up. No synthetic input is produced, so the mixin stays out of the way.
	 */
	private Status tickBaritone(AutoSellConfig cfg) {
		jumpThisTick = false;
		sprintThisTick = false;
		speed = 1F;

		double distance = BaritoneWalker.distanceTo(target);
		if (distance >= 0 && distance <= arriveDistance) {
			stop();
			return Status.ARRIVED;
		}
		if (++ticks > cfg.walkTimeoutTicks) {
			stop();
			return Status.TIMEOUT;
		}
		if (BaritoneWalker.isPathing()) {
			baritoneGrace = 0;
			return Status.WALKING;
		}
		// Baritone idles between path segments while it plans the next one.
		// Treating that as failure was what made it look like the run had
		// died; instead, re-issue the goal a few times before giving up.
		if (++baritoneGrace > BARITONE_IDLE_TICKS) {
			baritoneGrace = 0;
			if (++baritoneRetries > BARITONE_MAX_RETRIES) {
				stop();
				return Status.STUCK;
			}
			BaritoneWalker.goTo(target, (int) Math.max(0, Math.floor(arriveDistance)));
		}
		return Status.WALKING;
	}

	private void retireReachedWaypoints(Vec3 pos) {
		while (legIndex < route.size()) {
			BlockPos wp = route.get(legIndex);
			double dx = (wp.getX() + 0.5) - pos.x;
			double dz = (wp.getZ() + 0.5) - pos.z;
			if (dx * dx + dz * dz <= WAYPOINT_REACH * WAYPOINT_REACH) {
				legIndex++;
			} else {
				break;
			}
		}
	}

	/** The point to steer at: next waypoint, else the target itself. */
	private double[] currentAim() {
		if (legIndex < route.size()) {
			BlockPos wp = route.get(legIndex);
			return new double[] {wp.getX() + 0.5, wp.getZ() + 0.5};
		}
		return new double[] {target.getX() + 0.5, target.getZ() + 0.5};
	}
}
