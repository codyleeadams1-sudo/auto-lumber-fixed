package com.autolumber.bot;

import com.autolumber.config.LogType;
import com.autolumber.config.LumberConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The tree-chopping brain. Runs once per client tick and only ever acts
 * through normal player input: held keys and smooth camera turns.
 */
public final class LumberBot {
	private enum State { SEARCH, WALK, CHOP, COLLECT, EAT, REPLANT, PAUSE }
	private enum Goal { LOG, ITEM, EXPLORE, REPLANT }

	private static final Random RNG = new Random();
	private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO,
			Items.PUFFERFISH, Items.CHICKEN, Items.SUSPICIOUS_STEW, Items.CHORUS_FRUIT, Items.GOLDEN_APPLE,
			Items.ENCHANTED_GOLDEN_APPLE);

	private static boolean enabled = false;
	private static String status = "Off";

	private static State state = State.SEARCH;
	private static Goal goal = Goal.LOG;
	private static List<BlockPos> path;
	private static int pathIdx;
	private static BlockPos target;
	private static ItemEntity itemTarget;
	private static final Set<BlockPos> tree = new HashSet<>();
	private static int treeLogsBroken;
	private static boolean treeActive;
	private static BlockPos treeBase;
	private static LogType treeType;
	private static boolean pendingCollect, pendingReplant;
	private static BlockPos replantSpot;
	private static Item replantItem;

	private static final Map<BlockPos, Long> blacklist = new HashMap<>();
	private static final Map<BlockPos, Boolean> naturalCache = new HashMap<>();
	private static final Set<Integer> ignoredItems = new HashSet<>();
	private static final Set<Long> visitedCells = new HashSet<>();
	private static final TreeScanner scanner = new TreeScanner();

	private static long ticks;
	private static int delay;
	private static int stuckTicks;
	private static double lastProgress;
	private static int repaths;
	private static int chopTicks, collectTicks, eatTicks, replantTicks, pauseTicks;
	private static int exploreCount;
	private static int searchCooldown;
	private static int eatRetryAt;
	private static int eatStartFood, eatStartCount, eatSlot;
	private static boolean eatStarted;
	private static boolean replantClicked;
	private static float exploreHeading = Float.NaN;
	private static float lastHealth = -1;
	private static Vec3 aimJitter = Vec3.ZERO;
	private static BlockPos leafTarget;      // leaf we're breaking so a drop resting on it falls down
	private static int leafItemId = -1;
	private static int leafBreaks;
	private static int fallWait;
	private static float walkYaw = Float.NaN; // smoothed walking heading

	private LumberBot() {}

	// ================================================================ public API

	public static boolean isEnabled() {
		return enabled;
	}

	public static boolean isPaused() {
		return enabled && !Minecraft.getInstance().mouseHandler.isMouseGrabbed();
	}

	public static String status() {
		return status;
	}

	public static void toggle() {
		if (enabled) stop("Turned off");
		else start();
	}

	public static void start() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) return;
		if (LumberConfig.enabledLogs.isEmpty()) {
			chat("Pick at least one log type in the menu first.", ChatFormatting.RED);
			return;
		}
		if (findAxeSlot(mc.player, true) < 0 && !isGoodAxe(mc.player.getMainHandItem())) {
			chat("You need an axe with more than " + LumberConfig.minDurability + " durability left.", ChatFormatting.RED);
			return;
		}
		enabled = true;
		resetTask();
		blacklist.clear();
		naturalCache.clear();
		ignoredItems.clear();
		visitedCells.clear();
		scanner.reset();
		exploreCount = 0;
		exploreHeading = Float.NaN;
		lastHealth = mc.player.getHealth();
		Stats.reset();
		Stats.start();
		status = "Scanning for trees...";
		chat("Started. Scanning for trees...", ChatFormatting.GREEN);
	}

	public static void stop(String reason) {
		boolean was = enabled;
		enabled = false;
		InputControl.releaseAll();
		Rotation.release();
		resetTask();
		Stats.pause();
		status = "Off";
		if (was) chat("Stopped: " + reason, ChatFormatting.YELLOW);
	}

	private static void resetTask() {
		state = State.SEARCH;
		path = null;
		target = null;
		itemTarget = null;
		tree.clear();
		treeLogsBroken = 0;
		treeActive = false;
		pendingCollect = pendingReplant = false;
		leafTarget = null;
		leafBreaks = 0;
		fallWait = 0;
		delay = 0;
		stuckTicks = 0;
		repaths = 0;
		searchCooldown = 0;
	}

	// ================================================================ main loop

	public static void tick(Minecraft mc) {
		if (!enabled) return;
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null || mc.gameMode == null) {
			enabled = false;
			InputControl.releaseAll();
			Rotation.release();
			Stats.pause();
			return;
		}
		ticks++;
		Rotation.onTick();

		if (p.isDeadOrDying()) {
			stop("You died");
			return;
		}
		if (LumberConfig.stopWhenHurt && lastHealth >= 0 && p.getHealth() < lastHealth - 0.01f && p.hurtTime > 0) {
			stop("You took damage");
			return;
		}
		lastHealth = p.getHealth();
		if (LumberConfig.stopNearMobs && ticks % 10 == 0 && hostileNearby(p, level)) {
			stop("Hostile mob nearby");
			return;
		}

		if (mc.gui.screen() != null) {
			stop("A menu was opened");
			return;
		}
		if (!mc.mouseHandler.isMouseGrabbed()) {
			// Minecraft only mines while the mouse is captured by the game window
			InputControl.forward(false);
			InputControl.attack(false);
			status = "Click the game window to resume";
			return;
		}

		scanner.tick(level, p.blockPosition());
		visitedCells.add(cellKey(p.getBlockX(), p.getBlockZ()));
		blacklist.values().removeIf(t -> t < ticks);

		if (delay > 0) {
			delay--;
			InputControl.forward(false);
			InputControl.jump(p.isInWater());
			InputControl.attack(false);
			InputControl.use(false);
			return;
		}

		// hunger comes first when it's safe to stop what we're doing
		if (state != State.EAT && wantsToEat(p) && (state != State.CHOP || p.getFoodData().getFoodLevel() <= 6)) {
			beginEat(mc, p);
			return;
		}

		if (state != State.EAT && state != State.REPLANT && !ensureAxe(mc, p)) return;

		switch (state) {
			case SEARCH -> search(mc, p, level);
			case WALK -> walk(mc, p, level);
			case CHOP -> chop(mc, p, level);
			case COLLECT -> collect(mc, p, level);
			case EAT -> eat(mc, p);
			case REPLANT -> replant(mc, p, level);
			case PAUSE -> pause(mc, p);
		}
	}

	private static void think() {
		delay = LumberConfig.reactionDelay + (LumberConfig.reactionDelay > 0 ? RNG.nextInt(LumberConfig.reactionDelay / 2 + 2) : 0);
	}

	private static boolean hostileNearby(LocalPlayer p, ClientLevel level) {
		double r = LumberConfig.mobRadius;
		return !level.getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(r),
				e -> e instanceof Enemy && e.isAlive() && e.distanceTo(p) <= r).isEmpty();
	}

	// ================================================================ axe handling

	private static boolean isGoodAxe(ItemStack s) {
		if (s.isEmpty() || !s.is(ItemTags.AXES)) return false;
		if (!s.isDamageableItem()) return true;
		return s.getMaxDamage() - s.getDamageValue() > LumberConfig.minDurability;
	}

	private static int findAxeSlot(LocalPlayer p, boolean anywhere) {
		Inventory inv = p.getInventory();
		int best = -1, bestLeft = -1;
		int end = anywhere ? 36 : 9;
		for (int i = 0; i < end; i++) {
			ItemStack s = inv.getItem(i);
			if (!isGoodAxe(s)) continue;
			int left = s.isDamageableItem() ? s.getMaxDamage() - s.getDamageValue() : Integer.MAX_VALUE;
			if (left > bestLeft) {
				best = i;
				bestLeft = left;
			}
		}
		return best;
	}

	private static boolean ensureAxe(Minecraft mc, LocalPlayer p) {
		if (isGoodAxe(p.getMainHandItem())) return true;
		InputControl.attack(false);
		InputControl.forward(false);
		boolean holdingAxe = p.getMainHandItem().is(ItemTags.AXES);

		if (holdingAxe && !LumberConfig.autoSwapAxe) {
			stop("Your axe is almost broken (" + LumberConfig.minDurability + " uses left)");
			return false;
		}
		Inventory inv = p.getInventory();
		int hotbar = findAxeSlot(p, false);
		if (hotbar >= 0) {
			inv.setSelectedSlot(hotbar);
			if (holdingAxe) {
				Stats.axeSwaps++;
				chat("Axe low, switched to another axe.", ChatFormatting.AQUA);
			}
			think();
			return false;
		}
		int any = findAxeSlot(p, true);
		if (any >= 9) {
			mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, any, inv.getSelectedSlot(), ContainerInput.SWAP, p);
			if (holdingAxe) {
				Stats.axeSwaps++;
				chat("Axe low, swapped in a fresh axe from your inventory.", ChatFormatting.AQUA);
			}
			delay = LumberConfig.reactionDelay + 4;
			return false;
		}
		stop(holdingAxe ? "Your axe is almost broken and you have no spare axe" : "No usable axe in your inventory");
		return false;
	}

	/** Puts the item from inventory slot {@code slot} into the hotbar and selects it. Returns false if it had to wait. */
	private static boolean holdSlot(Minecraft mc, LocalPlayer p, int slot) {
		Inventory inv = p.getInventory();
		if (slot < 9) {
			if (inv.getSelectedSlot() != slot) {
				inv.setSelectedSlot(slot);
				delay = 2;
				return false;
			}
			return true;
		}
		int dest = pickFreeHotbarSlot(p);
		mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, slot, dest, ContainerInput.SWAP, p);
		inv.setSelectedSlot(dest);
		delay = 4;
		return false;
	}

	private static int pickFreeHotbarSlot(LocalPlayer p) {
		Inventory inv = p.getInventory();
		for (int i = 8; i >= 0; i--) if (inv.getItem(i).isEmpty()) return i;
		for (int i = 8; i >= 0; i--) {
			ItemStack s = inv.getItem(i);
			if (!s.is(ItemTags.AXES) && s.get(DataComponents.FOOD) == null && !s.is(ItemTags.SAPLINGS)) return i;
		}
		return 8;
	}

	// ================================================================ eating

	private static boolean wantsToEat(LocalPlayer p) {
		if (!LumberConfig.autoEat || ticks < eatRetryAt) return false;
		int food = p.getFoodData().getFoodLevel();
		if (food >= 20) return false;
		return food <= LumberConfig.eatAtHunger || (p.getHealth() < 10 && food < 18);
	}

	private static int findFood(LocalPlayer p) {
		Inventory inv = p.getInventory();
		int missing = 20 - p.getFoodData().getFoodLevel();
		int best = -1;
		double bestScore = -1e9;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			FoodProperties fp = s.get(DataComponents.FOOD);
			if (fp == null || BAD_FOOD.contains(s.getItem())) continue;
			double score = fp.nutrition() + fp.saturation() * 1.5 - Math.max(0, fp.nutrition() - missing) * 1.6;
			if (i < 9) score += 0.5; // avoid inventory shuffling when there's an equal choice
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	private static void beginEat(Minecraft mc, LocalPlayer p) {
		InputControl.forward(false);
		InputControl.attack(false);
		int slot = findFood(p);
		if (slot < 0) {
			if (p.getFoodData().getFoodLevel() <= 6 && LumberConfig.stopWithoutFood) {
				stop("You're hungry and out of safe food");
				return;
			}
			eatRetryAt = (int) ticks + 600;
			chat("Hungry but no safe food found. Carry some food!", ChatFormatting.GOLD);
			return;
		}
		eatSlot = slot;
		eatStarted = false;
		eatTicks = 0;
		state = State.EAT;
		setStatus("Eating");
	}

	private static void eat(Minecraft mc, LocalPlayer p) {
		InputControl.forward(false);
		InputControl.attack(false);
		InputControl.jump(p.isInWater());
		setStatus("Eating");

		if (!eatStarted) {
			ItemStack want = p.getInventory().getItem(eatSlot);
			if (want.get(DataComponents.FOOD) == null) {
				state = State.SEARCH;
				return;
			}
			if (!holdSlot(mc, p, eatSlot)) {
				eatSlot = p.getInventory().getSelectedSlot();
				return;
			}
			eatStarted = true;
			eatStartFood = p.getFoodData().getFoodLevel();
			eatStartCount = p.getMainHandItem().getCount();
			eatTicks = 0;
			// glance up a little, away from anything we could accidentally click
			Rotation.setTarget(p.getYRot() + (RNG.nextFloat() - 0.5f) * 20f, -25f - RNG.nextFloat() * 10f);
		}
		eatTicks++;
		if (eatTicks < 6) return; // let the head come up first
		InputControl.use(true);

		boolean finished = p.getFoodData().getFoodLevel() > eatStartFood
				|| p.getMainHandItem().getCount() < eatStartCount
				|| p.getMainHandItem().get(DataComponents.FOOD) == null;
		if (finished || eatTicks > 90) {
			InputControl.use(false);
			if (finished) Stats.meals++;
			state = State.SEARCH;
			think();
			// still hungry? eat again right away
			if (p.getFoodData().getFoodLevel() > LumberConfig.eatAtHunger) eatRetryAt = (int) ticks + 40;
		}
	}

	// ================================================================ searching

	private static boolean isWantedLog(BlockState s) {
		LogType t = LogType.of(s);
		return t != null && LumberConfig.enabledLogs.contains(t);
	}

	private static double reach(LocalPlayer p) {
		return p.blockInteractionRange();
	}

	private static Vec3 center(BlockPos b) {
		return Vec3.atCenterOf(b);
	}

	private static void search(Minecraft mc, LocalPlayer p, ClientLevel level) {
		InputControl.forward(false);
		InputControl.attack(false);
		InputControl.use(false);
		InputControl.jump(p.isInWater());

		if (searchCooldown > 0) {
			searchCooldown--;
			return;
		}
		if (p.getInventory().getFreeSlot() < 0) {
			stop("Your inventory is full");
			return;
		}

		tree.removeIf(b -> !isWantedLog(level.getBlockState(b)) || blacklist.containsKey(b));
		if (treeActive && tree.isEmpty()) {
			finishTree(p);
			return;
		}
		PathFinder finder = new PathFinder(level);
		BlockPos feet = p.blockPosition();

		// ---- 1) keep working on the current tree
		if (!tree.isEmpty()) {
			Vec3 eye = p.getEyePosition();
			List<BlockPos> logs = new ArrayList<>(tree);
			logs.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(b -> center(b).distanceToSqr(eye)));
			for (BlockPos b : logs) {
				if (center(b).distanceTo(eye) <= reach(p) - 0.2 && findAim(p, level, b) != null) {
					beginChop(b);
					return;
				}
			}
			// walk to whichever remaining log is cheapest to reach
			List<BlockPos> bestPath = null;
			BlockPos bestLog = null;
			double bestCost = Double.MAX_VALUE;
			int tries = 0;
			for (BlockPos b : logs) {
				if (tries++ >= 4) break;
				List<BlockPos> found = pathToLog(finder, feet, b, p);
				if (found != null && finder.lastCost < bestCost) {
					bestCost = finder.lastCost;
					bestPath = found;
					bestLog = b;
				} else if (found == null) {
					blacklist.put(b, ticks + 600);
				}
			}
			if (bestPath != null) {
				target = bestLog;
				startPath(bestPath, Goal.LOG);
				return;
			}
			finishTree(p); // the rest is out of reach
			return;
		}

		// ---- 2) wrap up the last tree: drops, then replanting
		if (pendingCollect) {
			if (LumberConfig.collectDrops && !findDrops(p, level).isEmpty()) {
				state = State.COLLECT;
				collectTicks = 0;
				return;
			}
			pendingCollect = false;
		}
		if (pendingReplant) {
			pendingReplant = false;
			if (prepareReplant(p, level)) return;
		}

		// ---- 3) choose the best next tree
		if (!scanner.hasCompletedSweep() && scanner.trunks().isEmpty()) {
			setStatus("Scanning for trees...");
			return;
		}
		setStatus("Choosing a tree...");
		if (chooseTree(mc, p, level, finder)) {
			exploreCount = 0;
			return;
		}

		// ---- 4) nothing good around: explore
		if (exploreCount >= 30) {
			stop("No reachable trees found nearby");
			return;
		}
		if (!explore(finder, p, level)) searchCooldown = 20;
	}

	private record Candidate(TreeScanner.Trunk trunk, double estimate, boolean visible) {}

	/** Ranks trees by walking distance, size, visibility and how far you'd need to turn. */
	private static boolean chooseTree(Minecraft mc, LocalPlayer p, ClientLevel level, PathFinder finder) {
		Vec3 eye = p.getEyePosition();
		Vec3 pos = p.position();
		List<AbstractClientPlayer> others = new ArrayList<>();
		if (LumberConfig.avoidPlayers) {
			for (AbstractClientPlayer o : level.players()) if (o != p && !o.isSpectator()) others.add(o);
		}
		double avoid2 = LumberConfig.avoidPlayerRadius * (double) LumberConfig.avoidPlayerRadius;

		List<Candidate> list = new ArrayList<>();
		for (TreeScanner.Trunk t : scanner.trunks()) {
			BlockPos b = t.base();
			if (blacklist.containsKey(b) || !isWantedLog(level.getBlockState(b))) continue;
			boolean nearPlayer = false;
			for (AbstractClientPlayer o : others) {
				if (o.position().distanceToSqr(center(b)) < avoid2) {
					nearPlayer = true;
					break;
				}
			}
			if (nearPlayer) continue;
			double horiz = Math.sqrt(Math.pow(b.getX() + 0.5 - pos.x, 2) + Math.pow(b.getZ() + 0.5 - pos.z, 2));
			double dy = Math.abs(b.getY() - p.getBlockY());
			float turn = Rotation.yawError(p, Rotation.yawTo(eye, center(b)));
			list.add(new Candidate(t, horiz + dy * 1.8 + turn / 180.0 * 3.0, false));
		}
		list.sort(Comparator.comparingDouble(Candidate::estimate));

		// visibility check on the closest few (a tree you can actually see is a more natural choice)
		List<Candidate> top = new ArrayList<>();
		for (int i = 0; i < list.size() && top.size() < 16; i++) {
			Candidate c = list.get(i);
			boolean vis = canSee(p, level, c.trunk().base());
			if (LumberConfig.visibleOnly && !vis) continue;
			top.add(new Candidate(c.trunk(), c.estimate() - (vis ? 4 : 0), vis));
		}
		top.sort(Comparator.comparingDouble(Candidate::estimate));

		BlockPos bestBase = null;
		Set<BlockPos> bestCluster = null;
		List<BlockPos> bestPath = null;
		double bestScore = Double.MAX_VALUE;
		int evaluated = 0;
		for (Candidate c : top) {
			if (evaluated >= 8) break;
			BlockPos base = c.trunk().base();
			Set<BlockPos> cluster = buildTree(level, base);
			if (cluster == null) {
				blacklist.put(base, ticks + 6000);
				scanner.forget(base);
				continue;
			}
			evaluated++;
			double cost;
			List<BlockPos> found = null;
			if (center(base).distanceTo(eye) <= reach(p) - 0.2 && findAim(p, level, base) != null) {
				cost = 0;
			} else {
				found = pathToLog(finder, p.blockPosition(), base, p);
				if (found == null) {
					blacklist.put(base, ticks + 1200);
					continue;
				}
				cost = finder.lastCost;
			}
			double score = cost - Math.min(cluster.size(), 16) * 0.3 - (c.visible() ? 3 : 0);
			if (score < bestScore) {
				bestScore = score;
				bestBase = base;
				bestCluster = cluster;
				bestPath = found;
			}
		}
		if (bestBase == null) return false;

		tree.clear();
		tree.addAll(bestCluster);
		treeBase = bestBase;
		treeType = LogType.of(level.getBlockState(bestBase));
		treeLogsBroken = 0;
		treeActive = true;
		if (bestPath == null) {
			beginChop(bestBase);
		} else {
			target = bestBase;
			startPath(bestPath, Goal.LOG);
		}
		return true;
	}

	private static boolean canSee(LocalPlayer p, ClientLevel level, BlockPos log) {
		Vec3 eye = p.getEyePosition();
		for (int dy = 0; dy <= 3; dy++) {
			BlockPos b = log.above(dy);
			if (!isWantedLog(level.getBlockState(b))) break;
			BlockHitResult hit = level.clip(new ClipContext(eye, center(b), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
			if (hit.getType() != HitResult.Type.BLOCK) return true;
			BlockState s = level.getBlockState(hit.getBlockPos());
			if (hit.getBlockPos().equals(b) || isWantedLog(s) || PathFinder.isLeafLike(s)) return true;
		}
		return false;
	}

	private static void finishTree(LocalPlayer p) {
		leafBreaks = 0;
		fallWait = 0;
		if (treeLogsBroken > 0) {
			Stats.trees++;
			pendingCollect = LumberConfig.collectDrops;
			pendingReplant = LumberConfig.replant && treeType != null && treeType.sapling() != null && treeBase != null;
			if (pendingReplant) {
				replantSpot = treeBase;
				replantItem = treeType.sapling();
			}
			// occasionally take a breather and look around, like a person would
			if (LumberConfig.humanPauses && RNG.nextFloat() < 0.35f) {
				state = State.PAUSE;
				pauseTicks = 15 + RNG.nextInt(35);
				Rotation.setTarget(p.getYRot() + (RNG.nextFloat() - 0.5f) * 90f, RNG.nextFloat() * 25f - 5f);
			}
		}
		if (treeBase != null) scanner.forget(treeBase);
		tree.clear();
		treeLogsBroken = 0;
		treeActive = false;
	}

	private static void pause(Minecraft mc, LocalPlayer p) {
		InputControl.forward(false);
		InputControl.attack(false);
		setStatus("Taking a short break");
		if (--pauseTicks <= 0) state = State.SEARCH;
	}

	/** Connected logs of one tree. Returns null if it doesn't look like a natural tree. */
	private static Set<BlockPos> buildTree(ClientLevel level, BlockPos base) {
		Set<BlockPos> logs = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(base);
		logs.add(base);
		boolean naturalLeaves = false;
		while (!queue.isEmpty() && logs.size() < 300) {
			BlockPos b = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) continue;
						BlockPos n = b.offset(dx, dy, dz);
						if (Math.abs(n.getX() - base.getX()) > 10 || Math.abs(n.getZ() - base.getZ()) > 10) continue;
						if (n.getY() < base.getY()) continue;
						BlockState s = level.getBlockState(n);
						if (isWantedLog(s)) {
							if (logs.add(n)) queue.add(n);
						} else if (!naturalLeaves && isNaturalLeaf(s)) {
							naturalLeaves = true;
						}
					}
				}
			}
		}
		if (LumberConfig.naturalTreesOnly) {
			Boolean cached = naturalCache.get(base);
			if (cached == null) {
				cached = naturalLeaves;
				naturalCache.put(base, cached);
			}
			if (!cached) return null;
		}
		return logs;
	}

	private static boolean isNaturalLeaf(BlockState s) {
		if (s.hasProperty(LeavesBlock.PERSISTENT)) return !s.getValue(LeavesBlock.PERSISTENT);
		return PathFinder.isLeafLike(s);
	}

	private static List<BlockPos> pathToLog(PathFinder finder, BlockPos feet, BlockPos log, LocalPlayer p) {
		double r = reach(p) - 0.6;
		Vec3 c = center(log);
		return finder.find(feet, n -> {
			if (n.equals(log) || n.above().equals(log)) return false;
			Vec3 eye = new Vec3(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
			return eye.distanceTo(c) <= r;
		}, log);
	}

	// ---------------------------------------------------------------- exploring

	private static long cellKey(int x, int z) {
		return ((long) (x >> 3) << 32) ^ ((z >> 3) & 0xffffffffL);
	}

	/** Walk toward the least-visited direction, preferring to keep going the same way. */
	private static boolean explore(PathFinder finder, LocalPlayer p, ClientLevel level) {
		exploreCount++;
		setStatus("Exploring for trees...");
		List<float[]> headings = new ArrayList<>();
		for (int i = 0; i < 8; i++) {
			float a = i * 45f;
			double score = 0;
			double rad = Math.toRadians(a);
			for (int d = 8; d <= 32; d += 8) {
				int x = p.getBlockX() + (int) Math.round(-Math.sin(rad) * d);
				int z = p.getBlockZ() + (int) Math.round(Math.cos(rad) * d);
				if (visitedCells.contains(cellKey(x, z))) score += 2;
			}
			if (!Float.isNaN(exploreHeading)) score += Math.abs(Mth.wrapDegrees(a - exploreHeading)) / 45f * 0.7;
			score += RNG.nextDouble() * 0.6;
			headings.add(new float[]{a, (float) score});
		}
		headings.sort(Comparator.comparingDouble(h -> h[1]));
		for (float[] h : headings) {
			double rad = Math.toRadians(h[0]);
			int dist = 18 + RNG.nextInt(8);
			int x = p.getBlockX() + (int) Math.round(-Math.sin(rad) * dist);
			int z = p.getBlockZ() + (int) Math.round(Math.cos(rad) * dist);
			for (int dy = 6; dy >= -6; dy--) {
				BlockPos t = new BlockPos(x, p.getBlockY() + dy, z);
				if (!level.hasChunkAt(t) || !finder.standable(t) || !finder.passable(t)) continue;
				List<BlockPos> found = finder.find(p.blockPosition(), n -> n.distManhattan(t) <= 2, t);
				if (found != null) {
					exploreHeading = h[0];
					target = null;
					startPath(found, Goal.EXPLORE);
					return true;
				}
				break;
			}
		}
		return false;
	}

	// ================================================================ walking

	private static void startPath(List<BlockPos> nodes, Goal g) {
		path = nodes;
		pathIdx = Math.min(1, nodes.size() - 1);
		walkYaw = Float.NaN;
		goal = g;
		state = State.WALK;
		stuckTicks = 0;
		lastProgress = Double.MAX_VALUE;
	}

	private static double hDist(LocalPlayer p, BlockPos n) {
		double dx = n.getX() + 0.5 - p.getX(), dz = n.getZ() + 0.5 - p.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static void walk(Minecraft mc, LocalPlayer p, ClientLevel level) {
		if (path == null || path.isEmpty()) {
			state = State.SEARCH;
			return;
		}
		InputControl.use(false);
		switch (goal) {
			case LOG -> setStatus("Walking to " + (treeType != null ? treeType.label() + " tree" : "tree"));
			case ITEM -> setStatus("Picking up drops");
			case EXPLORE -> setStatus("Exploring for trees...");
			case REPLANT -> setStatus("Going to replant");
		}

		// the log came into reach on the way: flow straight into chopping
		if (goal == Goal.LOG && target != null && p.onGround()
				&& center(target).distanceTo(p.getEyePosition()) <= reach(p) - 0.3
				&& findAim(p, level, target) != null) {
			beginChop(target);
			return;
		}
		if (goal == Goal.ITEM && (itemTarget == null || itemTarget.isRemoved())) {
			state = State.COLLECT;
			return;
		}

		PathFinder finder = new PathFinder(level);

		// advance past any node we're standing on (handles overshoot and drops)
		for (int i = pathIdx; i < Math.min(path.size(), pathIdx + 4); i++) {
			BlockPos n = path.get(i);
			if (hDist(p, n) < (i == path.size() - 1 ? 0.35 : 0.55) && Math.abs(p.getY() - n.getY()) < 0.75) {
				pathIdx = i + 1;
				stuckTicks = 0;
				lastProgress = Double.MAX_VALUE;
			}
		}
		if (pathIdx >= path.size()) {
			arrive(p);
			return;
		}

		BlockPos node = path.get(pathIdx);
		BlockPos prev = path.get(Math.max(0, pathIdx - 1));

		// leaves in the way of the next step? clear them when we get there
		if (hDist(p, node) < 1.9) {
			List<BlockPos> obstacles = new ArrayList<>(3);
			if (node.getY() > prev.getY()) obstacles.add(prev.above(2));
			obstacles.add(node.above());
			obstacles.add(node);
			for (BlockPos o : obstacles) {
				if (finder.passable(o)) continue;
				if (finder.breakable(o)) {
					InputControl.jump(false);
					setStatus("Clearing leaves");
					stuckTicks = 0;
					if (!mineBlock(mc, p, o, center(o))) {
						mineStall = 0;
						repath(mc, p, level);
					}
					return;
				}
				repath(mc, p, level);
				return;
			}
		}
		InputControl.attack(false);

		// steer toward the furthest node we can walk to in a straight line (no zig-zags)
		BlockPos steer = node;
		int floorY = Mth.floor(p.getY() + 0.01);
		if (p.onGround() && node.getY() == floorY) {
			for (int i = pathIdx + 1; i < Math.min(path.size(), pathIdx + 10); i++) {
				BlockPos cand = path.get(i);
				if (cand.getY() != floorY || !finder.canWalkStraight(p.position(), cand)) break;
				steer = cand;
			}
		}
		Vec3 steerPoint = new Vec3(steer.getX() + 0.5, steer.getY(), steer.getZ() + 0.5);
		double dx = steerPoint.x - p.getX(), dz = steerPoint.z - p.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		float yaw;
		if (dist < 0.9 && !node.equals(prev)) {
			// right on top of a node: face along the path instead of at a point we're about to pass
			yaw = (float) (Mth.atan2(node.getZ() - prev.getZ(), node.getX() - prev.getX()) * Mth.RAD_TO_DEG) - 90f;
		} else {
			yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
		}
		// low-pass the camera heading so it can never flip from side to side
		if (Float.isNaN(walkYaw)) walkYaw = p.getYRot();
		float headingDiff = Mth.wrapDegrees(yaw - walkYaw);
		if (Math.abs(headingDiff) > 1.5f) walkYaw += headingDiff * 0.2f;

		// never walk off a cliff: stop and re-plan if the ground ahead drops away
		boolean plannedDrop = node.getY() < floorY;
		if (p.onGround() && !plannedDrop && dropAhead(level, p, yaw) > 3) {
			InputControl.forward(false);
			InputControl.jump(false);
			setStatus("Avoiding a drop");
			repath(mc, p, level);
			return;
		}

		// eyes: a little down the path; when the tree is close, start looking up at it
		float pitch = node.getY() > prev.getY() ? 4f : 12f;
		double remaining = path.size() - pathIdx;
		if (goal == Goal.LOG && target != null && remaining <= 3) {
			pitch = Mth.clamp(Rotation.pitchTo(p.getEyePosition(), center(target)), -40f, 40f) * 0.6f + pitch * 0.4f;
		}
		Rotation.setTarget(walkYaw, pitch);
		float yawErr = Rotation.yawError(p, yaw);

		// ease off before the final stop instead of slamming into it
		boolean lastLeg = steer.equals(path.get(path.size() - 1));
		boolean forward = yawErr < 55f && !(lastLeg && dist < 0.28);
		InputControl.forward(forward);

		boolean stepUp = node.getY() > floorY && hDist(p, node) < 1.3;
		boolean bumped = p.horizontalCollision && yawErr < 30f;
		InputControl.jump(p.isInWater() || ((stepUp || bumped) && p.onGround()));

		boolean longStraight = dist > 5 && yawErr < 8f && !p.isInWater();
		p.setSprinting(LumberConfig.sprint && forward && longStraight && p.getFoodData().getFoodLevel() > 6);

		// stuck detection based on distance to the next node
		double prog = hDist(p, node) + Math.abs(p.getY() - node.getY());
		if (prog < lastProgress - 0.04) {
			lastProgress = prog;
			stuckTicks = 0;
		} else if (++stuckTicks > 45) {
			repath(mc, p, level);
		}
	}

	/** Blocks of fall (0 = solid ground) about 0.8 blocks ahead in the given heading; 99 = no ground (or lava). */
	private static int dropAhead(ClientLevel level, LocalPlayer p, float yaw) {
		double rad = Math.toRadians(yaw);
		double x = p.getX() - Math.sin(rad) * 0.8, z = p.getZ() + Math.cos(rad) * 0.8;
		int floorY = Mth.floor(p.getY() + 0.01);
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int k = 0; k <= 8; k++) {
			m.set(Mth.floor(x), floorY - 1 - k, Mth.floor(z));
			if (!level.hasChunkAt(m)) return 99;
			BlockState s = level.getBlockState(m);
			if (s.is(Blocks.LAVA) || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.FIRE)) return 99;
			if (!s.getCollisionShape(level, m).isEmpty() || !s.getFluidState().isEmpty()) return k;
		}
		return 99;
	}

	private static void arrive(LocalPlayer p) {
		InputControl.forward(false);
		InputControl.jump(false);
		p.setSprinting(false);
		path = null;
		switch (goal) {
			case LOG -> {
				if (target != null) beginChop(target);
				else state = State.SEARCH;
			}
			case ITEM -> {
				state = State.COLLECT;
				delay = 4;
			}
			case EXPLORE -> state = State.SEARCH;
			case REPLANT -> {
				state = State.REPLANT;
				replantTicks = 0;
			}
		}
	}

	private static void repath(Minecraft mc, LocalPlayer p, ClientLevel level) {
		InputControl.forward(false);
		InputControl.jump(false);
		repaths++;
		if (repaths > 3) {
			if (target != null && goal == Goal.LOG) blacklist.put(target, ticks + 1200);
			if (itemTarget != null) ignoredItems.add(itemTarget.getId());
			repaths = 0;
			state = goal == Goal.ITEM ? State.COLLECT : State.SEARCH;
			return;
		}
		PathFinder finder = new PathFinder(level);
		List<BlockPos> found = null;
		if (goal == Goal.LOG && target != null) {
			found = pathToLog(finder, p.blockPosition(), target, p);
			if (found == null) blacklist.put(target, ticks + 1200);
		} else if (goal == Goal.ITEM && itemTarget != null && leafTarget != null) {
			found = pathToLog(finder, p.blockPosition(), leafTarget, p);
			if (found == null) {
				ignoredItems.add(itemTarget.getId());
				leafTarget = null;
				state = State.COLLECT;
				return;
			}
			startPath(found, Goal.ITEM);
			return;
		} else if (goal == Goal.ITEM && itemTarget != null) {
			if (!pathToItem(finder, p, itemTarget)) {
				ignoredItems.add(itemTarget.getId());
				state = State.COLLECT;
			}
			return;
		} else if (goal == Goal.REPLANT && replantSpot != null) {
			found = pathNear(finder, p, replantSpot);
		}
		if (found != null) {
			Goal g = goal;
			startPath(found, g);
		} else {
			state = State.SEARCH;
		}
	}

	// ================================================================ chopping

	private static void beginChop(BlockPos log) {
		mineStall = 0;
		target = log;
		state = State.CHOP;
		chopTicks = 0;
		path = null;
		aimJitter = new Vec3((RNG.nextDouble() - 0.5) * 0.5, (RNG.nextDouble() - 0.5) * 0.5, (RNG.nextDouble() - 0.5) * 0.5);
		InputControl.forward(false);
	}

	private record Aim(BlockPos block, Vec3 point) {}

	private static Aim findAim(LocalPlayer p, ClientLevel level, BlockPos b) {
		Vec3 eye = p.getEyePosition();
		double r = reach(p);
		Vec3 c = center(b);
		List<Vec3> points = new ArrayList<>(8);
		points.add(c.add(aimJitter.scale(0.6)));
		points.add(c);
		points.add(c.add(0, 0.36, 0));
		points.add(c.add(0, -0.36, 0));
		points.add(c.add(0.36, 0, 0));
		points.add(c.add(-0.36, 0, 0));
		points.add(c.add(0, 0, 0.36));
		points.add(c.add(0, 0, -0.36));
		Aim obstruction = null;
		for (Vec3 pt : points) {
			if (pt.distanceTo(eye) > r) continue;
			Vec3 end = eye.add(pt.subtract(eye).scale(1.05));
			BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
			if (hit.getType() != HitResult.Type.BLOCK) continue;
			BlockPos hp = hit.getBlockPos();
			if (hp.equals(b)) return new Aim(b, pt);
			if (obstruction == null && LumberConfig.breakLeaves && PathFinder.isLeafLike(level.getBlockState(hp))
					&& hit.getLocation().distanceTo(eye) <= r - 0.1) {
				obstruction = new Aim(hp, hit.getLocation().add(center(hp)).scale(0.5));
			}
		}
		return obstruction;
	}

	private static void chop(Minecraft mc, LocalPlayer p, ClientLevel level) {
		InputControl.forward(false);
		InputControl.jump(false);
		InputControl.use(false);
		if (target == null || !isWantedLog(level.getBlockState(target))) {
			InputControl.attack(false);
			if (target != null) {
				tree.remove(target);
				if (chopTicks > 0) {
					Stats.onLog();
					treeLogsBroken++;
				}
			}
			target = null;
			state = State.SEARCH;
			think();
			// that was the last log we can get from this tree
			tree.removeIf(b -> !isWantedLog(level.getBlockState(b)));
			if (treeActive && tree.isEmpty()) finishTree(p);
			return;
		}
		setStatus("Chopping " + labelOf(level.getBlockState(target)));

		if (center(target).distanceTo(p.getEyePosition()) > reach(p)) {
			InputControl.attack(false);
			state = State.SEARCH;
			return;
		}
		Aim aim = findAim(p, level, target);
		if (aim == null) {
			InputControl.attack(false);
			blacklist.put(target, ticks + 1200);
			state = State.SEARCH;
			return;
		}
		if (++chopTicks > 600) {
			InputControl.attack(false);
			blacklist.put(target, ticks + 2400);
			state = State.SEARCH;
			return;
		}
		if (!aim.block.equals(target)) setStatus("Clearing leaves");
		if (!mineBlock(mc, p, aim.block, aim.point)) {
			// can't get the crosshair onto it from here: try another log or spot
			mineStall = 0;
			InputControl.attack(false);
			blacklist.put(target, ticks + 1200);
			state = State.SEARCH;
		}
	}

	private static int mineStall;

	/**
	 * Turns toward the block and holds attack only while the crosshair is really on it.
	 * If a leaf sits in front of it, that leaf is mined instead (it's in the way anyway).
	 * Returns false once nothing has been mined for a while, so the caller can give up.
	 */
	private static boolean mineBlock(Minecraft mc, LocalPlayer p, BlockPos block, Vec3 point) {
		InputControl.forward(false);
		Rotation.lookAt(p, point);
		HitResult hr = mc.hitResult;
		boolean attack = false;
		if (hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK) {
			BlockPos hp = bh.getBlockPos();
			if (hp.equals(block)) {
				attack = true;
			} else if (LumberConfig.breakLeaves && Rotation.errorTo(p, point) < 8f
					&& PathFinder.isLeafLike(mc.level.getBlockState(hp))) {
				attack = true;
			}
		}
		InputControl.attack(attack);
		mineStall = attack ? 0 : mineStall + 1;
		return mineStall < 40;
	}

	private static String labelOf(BlockState s) {
		LogType t = LogType.of(s);
		return t == null ? "log" : t.label() + " log";
	}

	// ================================================================ collecting

	private static boolean wantedDrop(ItemStack s) {
		if (s.is(ItemTags.LOGS)) return true;
		if (LumberConfig.replant && s.is(ItemTags.SAPLINGS)) return true;
		if (s.is(Items.MANGROVE_PROPAGULE) && LumberConfig.replant) return true;
		return LumberConfig.autoEat && s.is(Items.APPLE);
	}

	private static List<ItemEntity> findDrops(LocalPlayer p, ClientLevel level) {
		List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(12, 6, 12),
				e -> !e.isRemoved() && !ignoredItems.contains(e.getId()) && wantedDrop(e.getItem()));
		Vec3 pos = p.position();
		items.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(pos)));
		return items;
	}

	private static boolean pathToItem(PathFinder finder, LocalPlayer p, ItemEntity item) {
		BlockPos ip = item.blockPosition();
		List<BlockPos> found = finder.find(p.blockPosition(), n -> {
			double dx = n.getX() + 0.5 - item.getX(), dz = n.getZ() + 0.5 - item.getZ();
			return dx * dx + dz * dz < 0.8 && Math.abs(n.getY() - item.getY()) < 1.5;
		}, ip);
		if (found == null) return false;
		itemTarget = item;
		startPath(found, Goal.ITEM);
		return true;
	}

	private static void collect(Minecraft mc, LocalPlayer p, ClientLevel level) {
		InputControl.attack(false);
		InputControl.forward(false);
		InputControl.jump(false);
		setStatus("Picking up drops");
		if (p.getInventory().getFreeSlot() < 0) {
			stop("Your inventory is full");
			return;
		}
		if (leafTarget != null && workLeaf(mc, p, level)) return;
		if (itemTarget != null && !itemTarget.isRemoved() && ++collectTicks > 120) {
			ignoredItems.add(itemTarget.getId());
			itemTarget = null;
			collectTicks = 0;
		}
		if (itemTarget != null && !itemTarget.isRemoved() && itemTarget.distanceTo(p) < 1.2) return;
		itemTarget = null;
		List<ItemEntity> drops = findDrops(p, level);
		// something is still falling (e.g. after we broke a leaf): give it a moment to land
		for (ItemEntity e : drops) {
			if (!e.onGround() && e.getDeltaMovement().y < -0.03 && fallWait++ < 80) {
				delay = 2;
				return;
			}
		}
		PathFinder finder = new PathFinder(level);
		int tries = 0;
		for (ItemEntity e : drops) {
			if (tries++ >= 4) break;
			collectTicks = 0;
			repaths = 0;
			if (pathToItem(finder, p, e)) return;
			// can't stand next to it: if it's resting on leaves, knock it down instead of climbing
			if (startLeafBreak(finder, p, level, e)) return;
			ignoredItems.add(e.getId());
		}
		pendingCollect = false;
		state = State.SEARCH;
	}

	/** If the drop rests on leaves we can reach from the ground, plan to break that leaf. */
	private static boolean startLeafBreak(PathFinder finder, LocalPlayer p, ClientLevel level, ItemEntity item) {
		if (!LumberConfig.breakLeaves || leafBreaks >= 25) return false;
		BlockPos at = item.blockPosition();
		BlockPos leaf = null;
		for (int k = 0; k <= 2; k++) {
			BlockPos b = at.below(k);
			BlockState s = level.getBlockState(b);
			if (PathFinder.isLeafLike(s)) {
				leaf = b;
				break;
			}
			if (!s.isAir() && !s.getCollisionShape(level, b).isEmpty()) return false; // resting on something else
		}
		if (leaf == null) return false;

		leafTarget = leaf;
		mineStall = 0;
		if (center(leaf).distanceTo(p.getEyePosition()) <= reach(p) - 0.3 && findAim(p, level, leaf) != null) {
			leafItemId = item.getId();
			itemTarget = null;
			return true; // already in reach: workLeaf() takes over on the next tick
		}
		List<BlockPos> found = pathToLog(finder, p.blockPosition(), leaf, p);
		if (found == null) {
			leafTarget = null;
			return false;
		}
		itemTarget = item;
		leafItemId = item.getId();
		startPath(found, Goal.ITEM);
		return true;
	}

	/** Mines the leaf under a drop. Returns true while it's busy with it. */
	private static boolean workLeaf(Minecraft mc, LocalPlayer p, ClientLevel level) {
		if (itemTarget != null) {
			leafItemId = itemTarget.getId();
			itemTarget = null;
		}
		BlockPos leaf = leafTarget;
		if (!PathFinder.isLeafLike(level.getBlockState(leaf))) {
			// it's gone: let the item drop, then carry on collecting
			leafTarget = null;
			leafBreaks++;
			mineStall = 0;
			InputControl.attack(false);
			delay = 6;
			return true;
		}
		setStatus("Knocking drops out of the leaves");
		Aim aim = center(leaf).distanceTo(p.getEyePosition()) <= reach(p) - 0.1 ? findAim(p, level, leaf) : null;
		if (aim == null || !mineBlock(mc, p, aim.block, aim.point)) {
			InputControl.attack(false);
			mineStall = 0;
			if (leafItemId >= 0) ignoredItems.add(leafItemId);
			leafTarget = null;
			return false;
		}
		return true;
	}

	// ================================================================ replanting

	private static int findItemSlot(LocalPlayer p, Item item) {
		Inventory inv = p.getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return i;
		return -1;
	}

	private static boolean prepareReplant(LocalPlayer p, ClientLevel level) {
		if (replantSpot == null || replantItem == null) return false;
		if (!level.getBlockState(replantSpot).isAir() || !level.getBlockState(replantSpot.below()).is(BlockTags.DIRT)) return false;
		if (findItemSlot(p, replantItem) < 0) return false;
		state = State.REPLANT;
		replantTicks = 0;
		replantClicked = false;
		return true;
	}

	private static List<BlockPos> pathNear(PathFinder finder, LocalPlayer p, BlockPos spot) {
		Vec3 aim = new Vec3(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		double r = reach(p) - 0.8;
		return finder.find(p.blockPosition(), n -> {
			if (n.equals(spot) || n.above().equals(spot)) return false;
			if (Math.abs(n.getX() - spot.getX()) <= 0 && Math.abs(n.getZ() - spot.getZ()) <= 0) return false;
			Vec3 eye = new Vec3(n.getX() + 0.5, n.getY() + 1.62, n.getZ() + 0.5);
			return eye.distanceTo(aim) <= r && eye.distanceTo(aim) >= 1.4;
		}, spot);
	}

	private static void replant(Minecraft mc, LocalPlayer p, ClientLevel level) {
		InputControl.forward(false);
		InputControl.attack(false);
		InputControl.jump(false);
		setStatus("Replanting sapling");
		BlockPos ground = replantSpot.below();

		if (!level.getBlockState(replantSpot).isAir()) {
			BlockState planted = level.getBlockState(replantSpot);
			if (replantClicked && (planted.is(BlockTags.SAPLINGS) || planted.is(Blocks.MANGROVE_PROPAGULE))) {
				Stats.saplings++;
			}
			InputControl.use(false);
			state = State.SEARCH;
			think();
			return;
		}
		if (++replantTicks > 120) {
			InputControl.use(false);
			state = State.SEARCH;
			return;
		}

		Vec3 aim = new Vec3(replantSpot.getX() + 0.5 + aimJitter.x * 0.3, replantSpot.getY() + 0.001, replantSpot.getZ() + 0.5 + aimJitter.z * 0.3);
		double d = aim.distanceTo(p.getEyePosition());
		boolean standingInSpot = p.getBoundingBox().intersects(new AABB(replantSpot));
		if (d > reach(p) - 0.3 || standingInSpot) {
			List<BlockPos> found = pathNear(new PathFinder(level), p, replantSpot);
			if (found == null) {
				state = State.SEARCH;
				return;
			}
			startPath(found, Goal.REPLANT);
			return;
		}

		int slot = findItemSlot(p, replantItem);
		if (slot < 0) {
			state = State.SEARCH;
			return;
		}
		if (!p.getMainHandItem().is(replantItem)) {
			holdSlot(mc, p, slot);
			return;
		}

		Rotation.lookAt(p, aim);
		HitResult hr = mc.hitResult;
		boolean onGround = hr instanceof BlockHitResult bh && hr.getType() == HitResult.Type.BLOCK
				&& bh.getBlockPos().equals(ground) && bh.getDirection() == Direction.UP;
		if (onGround && !replantClicked) {
			InputControl.use(true);
			replantClicked = true;
			delay = 3;
		} else {
			InputControl.use(false);
		}
	}

	// ================================================================ messages

	private static void setStatus(String s) {
		status = s;
	}

	private static void chat(String msg, ChatFormatting color) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.hud.getChat().addClientSystemMessage(
				Component.literal("[AutoLumber] ").withStyle(ChatFormatting.DARK_GREEN)
						.append(Component.literal(msg).withStyle(color)));
	}
}
