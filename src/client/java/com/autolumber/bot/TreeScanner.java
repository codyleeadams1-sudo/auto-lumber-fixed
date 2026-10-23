package com.autolumber.bot;

import com.autolumber.config.LogType;
import com.autolumber.config.LumberConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds tree trunks around the player by reading the blocks the game has
 * already loaded on your screen. The work is spread over several ticks
 * (a few hundred columns per tick, nearest first) so there's no lag spike,
 * and results are remembered between sweeps.
 */
public final class TreeScanner {
	private static final int COLUMNS_PER_TICK = 450;
	private static final int UP = 20, DOWN = 14;

	public record Trunk(BlockPos base, LogType type) {}

	private final Map<BlockPos, Trunk> trunks = new HashMap<>();
	private int[][] offsets = new int[0][];
	private int builtRadius = -1;
	private int cursor = 0;
	private BlockPos center;
	private int sweeps = 0;

	public void reset() {
		trunks.clear();
		cursor = 0;
		center = null;
		sweeps = 0;
	}

	public boolean hasCompletedSweep() {
		return sweeps > 0;
	}

	public Collection<Trunk> trunks() {
		return trunks.values();
	}

	public void forget(BlockPos base) {
		trunks.remove(base);
	}

	private void buildOffsets(int r) {
		List<int[]> list = new ArrayList<>();
		for (int dx = -r; dx <= r; dx++)
			for (int dz = -r; dz <= r; dz++)
				if (dx * dx + dz * dz <= r * r) list.add(new int[]{dx, dz});
		list.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
		offsets = list.toArray(new int[0][]);
		builtRadius = r;
	}

	private static boolean isGround(Level level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		if (s.isAir() || s.is(BlockTags.LEAVES) || PathFinder.isLeafLike(s)) return false;
		return !s.getCollisionShape(level, p).isEmpty();
	}

	public void tick(Level level, BlockPos playerPos) {
		if (builtRadius != LumberConfig.searchRadius) {
			buildOffsets(LumberConfig.searchRadius);
			cursor = 0;
		}
		if (center == null || cursor >= offsets.length) {
			if (cursor >= offsets.length) sweeps++;
			center = playerPos;
			cursor = 0;
			// drop trunks that are gone, out of range or no longer wanted
			int r2 = (LumberConfig.searchRadius + 8) * (LumberConfig.searchRadius + 8);
			trunks.values().removeIf(t -> {
				BlockState s = level.getBlockState(t.base);
				LogType now = LogType.of(s);
				return now == null || !LumberConfig.enabledLogs.contains(now) || t.base.distSqr(playerPos) > r2;
			});
		}
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int end = Math.min(offsets.length, cursor + COLUMNS_PER_TICK);
		for (; cursor < end; cursor++) {
			int x = center.getX() + offsets[cursor][0], z = center.getZ() + offsets[cursor][1];
			m.set(x, center.getY(), z);
			if (!level.hasChunkAt(m)) continue;
			LogType above = null;
			for (int y = center.getY() + UP; y >= center.getY() - DOWN; y--) {
				m.set(x, y, z);
				LogType t = LogType.of(level.getBlockState(m));
				// a trunk starts where a log sits on real ground (not on leaves, not floating in the air)
				if (t == null && above != null && LumberConfig.enabledLogs.contains(above) && isGround(level, m)) {
					BlockPos base = new BlockPos(x, y + 1, z);
					trunks.putIfAbsent(base, new Trunk(base, above));
				}
				above = t;
			}
		}
	}
}
