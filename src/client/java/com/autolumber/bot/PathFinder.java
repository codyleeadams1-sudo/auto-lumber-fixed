package com.autolumber.bot;

import com.autolumber.config.LumberConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A* over the positions a player can stand on. Moves are limited to what a
 * normal survival player can do: walk, walk diagonally, jump up one block and
 * drop down up to three blocks. Leaves can optionally be broken through.
 */
public final class PathFinder {
	private static final int MAX_NODES = 8000;
	private static final int MAX_DROP = 3;
	private static final double LEAF_COST = 4.0;
	private static final double EDGE_COST = 3.0;   // extra cost for standing next to a big drop
	private static final double STEP_UP_COST = 2.0; // climbing hills costs more than walking flat

	private static final Set<Block> DANGER = Set.of(
			Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
			Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.WITHER_ROSE,
			Blocks.POINTED_DRIPSTONE, Blocks.COBWEB, Blocks.LAVA);

	private final Level level;

	public PathFinder(Level level) {
		this.level = level;
	}

	// ------------------------------------------------------------ block rules

	public static boolean isLeafLike(BlockState s) {
		return s.is(BlockTags.LEAVES) || s.is(Blocks.NETHER_WART_BLOCK) || s.is(Blocks.WARPED_WART_BLOCK)
				|| s.is(Blocks.SHROOMLIGHT);
	}

	private boolean loaded(BlockPos p) {
		return level.hasChunkAt(p);
	}

	/** Air-like: a player body can be in this block. */
	public boolean passable(BlockPos p) {
		if (!loaded(p)) return false;
		BlockState s = level.getBlockState(p);
		if (DANGER.contains(s.getBlock())) return false;
		if (!s.getFluidState().isEmpty()) return false;
		return s.getCollisionShape(level, p).isEmpty();
	}

	/** A leaf the bot is allowed to break to make room. */
	public boolean breakable(BlockPos p) {
		if (!LumberConfig.breakLeaves || !loaded(p)) return false;
		return isLeafLike(level.getBlockState(p));
	}

	private boolean clear(BlockPos p) {
		return passable(p) || breakable(p);
	}

	private boolean solidFloor(BlockPos p) {
		if (!loaded(p)) return false;
		BlockState s = level.getBlockState(p);
		if (DANGER.contains(s.getBlock())) return false;
		// never stand on leaves or logs: that's how the bot used to climb trees
		if (isLeafLike(s) || s.is(BlockTags.LOGS)) return false;
		return !s.getCollisionShape(level, p).isEmpty();
	}

	/** Anything that stops a fall (solid block or fluid). */
	private boolean supports(BlockPos p) {
		if (!loaded(p)) return false;
		BlockState s = level.getBlockState(p);
		return !s.getCollisionShape(level, p).isEmpty() || !s.getFluidState().isEmpty();
	}

	/** True if any open neighbour of this spot drops further than a safe fall. */
	private boolean nearBigDrop(BlockPos feet) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos b = feet.relative(d);
			if (!passable(b) || !passable(b.above())) continue;
			boolean ground = false;
			for (int k = 1; k <= MAX_DROP + 1; k++) {
				if (supports(b.below(k))) {
					ground = true;
					break;
				}
			}
			if (!ground) return true;
		}
		return false;
	}

	private double edgeCost(BlockPos feet) {
		return nearBigDrop(feet) ? EDGE_COST : 0;
	}

	public boolean standable(BlockPos feet) {
		return clear(feet) && clear(feet.above()) && solidFloor(feet.below());
	}

	private double breakCost(BlockPos p) {
		return passable(p) ? 0 : LEAF_COST;
	}

	/**
	 * True if a player can walk in a straight line from {@code from} to the
	 * centre of {@code to} on the same level without bumping into anything,
	 * falling, or needing to break leaves. Checks the full body width.
	 */
	public boolean canWalkStraight(net.minecraft.world.phys.Vec3 from, BlockPos to) {
		int y = to.getY();
		double tx = to.getX() + 0.5, tz = to.getZ() + 0.5;
		double dx = tx - from.x, dz = tz - from.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.01) return true;
		double nx = -dz / len, nz = dx / len; // sideways unit vector
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (double t = 0; t <= len; t += 0.25) {
			double cx = from.x + dx * t / len, cz = from.z + dz * t / len;
			for (double side = -0.32; side <= 0.33; side += 0.32) {
				m.set(Math.floor(cx + nx * side), y, Math.floor(cz + nz * side));
				if (!passable(m) || !passable(m.above()) || !solidFloor(m.below())) return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------ search

	private record Node(BlockPos pos, double g, double f, Node parent) {}

	/** Walking cost of the last successful search (roughly blocks walked). */
	public double lastCost;

	public List<BlockPos> find(BlockPos start, Predicate<BlockPos> goal, BlockPos heuristicTarget) {
		PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
		Map<BlockPos, Double> best = new HashMap<>();
		Node first = new Node(start, 0, h(start, heuristicTarget), null);
		open.add(first);
		best.put(start, 0.0);
		int expanded = 0;

		while (!open.isEmpty() && expanded < MAX_NODES) {
			Node cur = open.poll();
			if (cur.g > best.getOrDefault(cur.pos, Double.MAX_VALUE)) continue;
			if (goal.test(cur.pos)) {
				lastCost = cur.g;
				return rebuild(cur);
			}
			expanded++;

			for (Move m : neighbours(cur.pos)) {
				double g = cur.g + m.cost;
				if (g < best.getOrDefault(m.pos, Double.MAX_VALUE)) {
					best.put(m.pos, g);
					open.add(new Node(m.pos, g, g + h(m.pos, heuristicTarget), cur));
				}
			}
		}
		return null;
	}

	private static double h(BlockPos a, BlockPos b) {
		int dx = Math.abs(a.getX() - b.getX()), dy = Math.abs(a.getY() - b.getY()), dz = Math.abs(a.getZ() - b.getZ());
		int min = Math.min(dx, dz), max = Math.max(dx, dz);
		return (max - min) + min * 1.414 + dy * 0.8;
	}

	private static List<BlockPos> rebuild(Node n) {
		List<BlockPos> out = new ArrayList<>();
		for (; n != null; n = n.parent) out.add(n.pos);
		Collections.reverse(out);
		return out;
	}

	private record Move(BlockPos pos, double cost) {}

	private List<Move> neighbours(BlockPos p) {
		List<Move> out = new ArrayList<>(12);
		// cardinal moves: flat, step up, drop down
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos n = p.relative(d);
			if (standable(n)) {
				out.add(new Move(n, 1 + breakCost(n) + breakCost(n.above()) + edgeCost(n)));
				continue;
			}
			BlockPos up = n.above();
			if (clear(p.above(2)) && standable(up)) {
				out.add(new Move(up, STEP_UP_COST + breakCost(p.above(2)) + breakCost(up) + breakCost(up.above()) + edgeCost(up)));
				continue;
			}
			if (passable(n) && passable(n.above())) {
				for (int k = 1; k <= MAX_DROP; k++) {
					BlockPos down = n.below(k);
					if (!passable(down)) break;
					if (solidFloor(down.below())) {
						out.add(new Move(down, 1 + k * 0.6 + edgeCost(down)));
						break;
					}
				}
			}
		}
		// diagonal walking only through fully open space (no corner cutting)
		int[][] diag = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
		for (int[] d : diag) {
			BlockPos n = p.offset(d[0], 0, d[1]);
			BlockPos a = p.offset(d[0], 0, 0), b = p.offset(0, 0, d[1]);
			if (passable(n) && passable(n.above()) && solidFloor(n.below())
					&& passable(a) && passable(a.above()) && passable(b) && passable(b.above())) {
				out.add(new Move(n, 1.414 + edgeCost(n)));
			}
		}
		return out;
	}
}
