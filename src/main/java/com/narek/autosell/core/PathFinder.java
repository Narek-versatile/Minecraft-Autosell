package com.narek.autosell.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Short-range A* over the block grid.
 *
 * <p>A straight line cannot get past a wall, however short the distance, so the
 * route is solved properly. Search is deliberately bounded - these hops are a
 * few blocks around a base, not cross-country travel - and a failed search
 * simply reports so, letting the caller fall back to walking straight at the
 * target.
 */
public final class PathFinder {

	/** Half-width of the search box around the midpoint. */
	private static final int MAX_RADIUS = 48;
	/** Ceiling on expanded nodes, so a hopeless search cannot stall a tick. */
	private static final int MAX_NODES = 6000;
	/** Furthest safe drop, in blocks. */
	private static final int MAX_FALL = 3;

	private PathFinder() {
	}

	/**
	 * @return waypoints from (excluding) {@code start} to {@code goal}, or an
	 *         empty list when no route was found within the bounds
	 */
	public static List<BlockPos> find(Level level, BlockPos start, BlockPos goal) {
		if (level == null || start == null || goal == null) {
			return List.of();
		}
		BlockPos from = groundOf(level, start);
		BlockPos to = nearestStandable(level, goal);
		if (from == null || to == null) {
			return List.of();
		}
		if (from.equals(to)) {
			return List.of();
		}

		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		Map<BlockPos, Integer> gScore = new HashMap<>();
		Set<BlockPos> closed = new HashSet<>();
		PriorityQueue<BlockPos> open = new PriorityQueue<>(
				Comparator.comparingInt(p -> gScore.getOrDefault(p, Integer.MAX_VALUE)
						+ heuristic(p, to)));

		gScore.put(from, 0);
		open.add(from);
		int expanded = 0;

		while (!open.isEmpty() && expanded++ < MAX_NODES) {
			BlockPos current = open.poll();
			if (current.equals(to)) {
				return reconstruct(cameFrom, current);
			}
			if (!closed.add(current)) {
				continue;
			}
			int baseCost = gScore.getOrDefault(current, Integer.MAX_VALUE);

			for (BlockPos next : neighbours(level, current)) {
				if (closed.contains(next) || outOfBounds(next, from, to)) {
					continue;
				}
				// Water costs more: passable, but slow and it pushes.
				int step = isWater(level, next) ? 3 : 2;
				int tentative = baseCost + step;
				if (tentative < gScore.getOrDefault(next, Integer.MAX_VALUE)) {
					cameFrom.put(next, current);
					gScore.put(next, tentative);
					open.add(next);
				}
			}
		}
		return List.of();
	}

	private static boolean outOfBounds(BlockPos p, BlockPos from, BlockPos to) {
		int cx = (from.getX() + to.getX()) / 2;
		int cz = (from.getZ() + to.getZ()) / 2;
		int cy = (from.getY() + to.getY()) / 2;
		return Math.abs(p.getX() - cx) > MAX_RADIUS
				|| Math.abs(p.getZ() - cz) > MAX_RADIUS
				|| Math.abs(p.getY() - cy) > 24;
	}

	private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> cameFrom, BlockPos end) {
		ArrayDeque<BlockPos> path = new ArrayDeque<>();
		BlockPos cur = end;
		while (cur != null) {
			path.addFirst(cur);
			cur = cameFrom.get(cur);
		}
		List<BlockPos> out = new ArrayList<>(path);
		if (!out.isEmpty()) {
			out.remove(0); // the tile we are already standing on
		}
		return out;
	}

	private static int heuristic(BlockPos a, BlockPos b) {
		return 2 * (Math.abs(a.getX() - b.getX())
				+ Math.abs(a.getY() - b.getY())
				+ Math.abs(a.getZ() - b.getZ()));
	}

	/** Horizontal moves, allowing a 1-block step up or a short drop. */
	private static List<BlockPos> neighbours(Level level, BlockPos from) {
		List<BlockPos> out = new ArrayList<>(8);
		int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

		for (int[] d : dirs) {
			BlockPos flat = from.offset(d[0], 0, d[1]);

			if (standable(level, flat)) {
				out.add(flat);
				continue;
			}
			// Step up one, but only with headroom to climb into.
			BlockPos up = flat.above();
			if (standable(level, up) && passable(level, from.above(2))) {
				out.add(up);
				continue;
			}
			// Or fall, up to a survivable depth.
			for (int drop = 1; drop <= MAX_FALL; drop++) {
				BlockPos down = flat.below(drop);
				if (standable(level, down)) {
					out.add(down);
					break;
				}
				if (!passable(level, down)) {
					break;
				}
			}
		}
		return out;
	}

	/** A tile the player can occupy: body space clear, and supported. */
	public static boolean standable(Level level, BlockPos pos) {
		if (!passable(level, pos) || !passable(level, pos.above())) {
			return false;
		}
		if (isWater(level, pos)) {
			return true; // swimmable
		}
		BlockState below = level.getBlockState(pos.below());
		return below.blocksMotion();
	}

	/** Nothing solid in the way. Water and plants do not block movement. */
	public static boolean passable(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) {
			return true;
		}
		if (!level.getFluidState(pos).isEmpty()) {
			return true;
		}
		return state.getCollisionShape((BlockGetter) level, pos).isEmpty();
	}

	public static boolean isWater(Level level, BlockPos pos) {
		return !level.getFluidState(pos).isEmpty();
	}

	/** Drops to the first supported tile at or below {@code pos}. */
	private static BlockPos groundOf(Level level, BlockPos pos) {
		if (standable(level, pos)) {
			return pos;
		}
		for (int dy = 1; dy <= 4; dy++) {
			BlockPos below = pos.below(dy);
			if (standable(level, below)) {
				return below;
			}
		}
		for (int dy = 1; dy <= 2; dy++) {
			BlockPos above = pos.above(dy);
			if (standable(level, above)) {
				return above;
			}
		}
		return pos;
	}

	/**
	 * A chest is not standable, so aim for the closest tile beside it that is.
	 */
	public static BlockPos nearestStandable(Level level, BlockPos goal) {
		if (standable(level, goal)) {
			return goal;
		}
		BlockPos best = null;
		int bestDist = Integer.MAX_VALUE;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos candidate = goal.offset(dx, dy, dz);
					if (!standable(level, candidate)) {
						continue;
					}
					int dist = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
					if (dist < bestDist) {
						bestDist = dist;
						best = candidate;
					}
				}
			}
		}
		return best;
	}
}
