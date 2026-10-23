package com.autolumber.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumSet;
import java.util.Set;

/** All user settings, saved to config/autolumber.json. */
public final class LumberConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("autolumber.json");

	// --- log selection
	public static final Set<LogType> enabledLogs = EnumSet.allOf(LogType.class);

	// --- chopping
	public static boolean breakLeaves = true;
	public static boolean collectDrops = true;
	public static boolean replant = true;
	public static boolean naturalTreesOnly = true;
	public static boolean visibleOnly = false;
	public static boolean avoidPlayers = true;
	public static int searchRadius = 32;
	public static int avoidPlayerRadius = 16;

	// --- axe
	public static boolean autoSwapAxe = true;
	public static int minDurability = 10;

	// --- survival
	public static boolean autoEat = true;
	public static int eatAtHunger = 14;        // eat when hunger bar (0-20) is at or below this
	public static boolean stopWithoutFood = true;
	public static boolean stopWhenHurt = true;
	public static boolean stopNearMobs = true;
	public static int mobRadius = 8;

	// --- movement
	public static boolean sprint = false;
	public static boolean humanPauses = true;
	public static int turnSpeed = 18;          // 5 (slow, lazy) .. 45 (snappy)
	public static int reactionDelay = 4;       // ticks to "think" between actions

	// --- display
	public static boolean showHud = true;

	private LumberConfig() {}

	public static void load() {
		if (!Files.exists(FILE)) return;
		try {
			JsonObject o = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
			if (o.has("enabledLogs")) {
				enabledLogs.clear();
				for (JsonElement e : o.getAsJsonArray("enabledLogs")) {
					try {
						enabledLogs.add(LogType.valueOf(e.getAsString()));
					} catch (IllegalArgumentException ignored) {
					}
				}
			}
			breakLeaves = bool(o, "breakLeaves", breakLeaves);
			collectDrops = bool(o, "collectDrops", collectDrops);
			replant = bool(o, "replant", replant);
			naturalTreesOnly = bool(o, "naturalTreesOnly", naturalTreesOnly);
			visibleOnly = bool(o, "visibleOnly", visibleOnly);
			avoidPlayers = bool(o, "avoidPlayers", avoidPlayers);
			searchRadius = clamp(num(o, "searchRadius", searchRadius), 8, 64);
			avoidPlayerRadius = clamp(num(o, "avoidPlayerRadius", avoidPlayerRadius), 4, 48);
			autoSwapAxe = bool(o, "autoSwapAxe", autoSwapAxe);
			minDurability = clamp(num(o, "minDurability", minDurability), 1, 200);
			autoEat = bool(o, "autoEat", autoEat);
			eatAtHunger = clamp(num(o, "eatAtHunger", eatAtHunger), 6, 19);
			stopWithoutFood = bool(o, "stopWithoutFood", stopWithoutFood);
			stopWhenHurt = bool(o, "stopWhenHurt", stopWhenHurt);
			stopNearMobs = bool(o, "stopNearMobs", stopNearMobs);
			mobRadius = clamp(num(o, "mobRadius", mobRadius), 3, 24);
			sprint = bool(o, "sprint", sprint);
			humanPauses = bool(o, "humanPauses", humanPauses);
			turnSpeed = clamp(num(o, "turnSpeed", turnSpeed), 5, 45);
			reactionDelay = clamp(num(o, "reactionDelay", reactionDelay), 0, 20);
			showHud = bool(o, "showHud", showHud);
		} catch (Exception e) {
			System.err.println("[AutoLumber] Could not read config, using defaults: " + e);
		}
	}

	public static void save() {
		JsonObject o = new JsonObject();
		JsonArray logs = new JsonArray();
		for (LogType t : enabledLogs) logs.add(t.name());
		o.add("enabledLogs", logs);
		o.addProperty("breakLeaves", breakLeaves);
		o.addProperty("collectDrops", collectDrops);
		o.addProperty("replant", replant);
		o.addProperty("naturalTreesOnly", naturalTreesOnly);
		o.addProperty("visibleOnly", visibleOnly);
		o.addProperty("avoidPlayers", avoidPlayers);
		o.addProperty("searchRadius", searchRadius);
		o.addProperty("avoidPlayerRadius", avoidPlayerRadius);
		o.addProperty("autoSwapAxe", autoSwapAxe);
		o.addProperty("minDurability", minDurability);
		o.addProperty("autoEat", autoEat);
		o.addProperty("eatAtHunger", eatAtHunger);
		o.addProperty("stopWithoutFood", stopWithoutFood);
		o.addProperty("stopWhenHurt", stopWhenHurt);
		o.addProperty("stopNearMobs", stopNearMobs);
		o.addProperty("mobRadius", mobRadius);
		o.addProperty("sprint", sprint);
		o.addProperty("humanPauses", humanPauses);
		o.addProperty("turnSpeed", turnSpeed);
		o.addProperty("reactionDelay", reactionDelay);
		o.addProperty("showHud", showHud);
		try {
			Files.createDirectories(FILE.getParent());
			Path tmp = FILE.resolveSibling("autolumber.json.tmp");
			Files.writeString(tmp, GSON.toJson(o), StandardCharsets.UTF_8);
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			System.err.println("[AutoLumber] Could not save config: " + e);
		}
	}

	private static boolean bool(JsonObject o, String k, boolean def) {
		return o.has(k) ? o.get(k).getAsBoolean() : def;
	}

	private static int num(JsonObject o, String k, int def) {
		return o.has(k) ? o.get(k).getAsInt() : def;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
