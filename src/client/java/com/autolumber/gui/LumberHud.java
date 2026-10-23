package com.autolumber.gui;

import com.autolumber.bot.LumberBot;
import com.autolumber.bot.Stats;
import com.autolumber.config.LumberConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import static com.autolumber.gui.UiKit.*;

/** Live panel in the top-right corner while the bot is running. */
public final class LumberHud {
	private static final int W = 150, H = 112;
	private static final Anim A = new Anim();

	private static String curStatus = "", prevStatus = "";
	private static float statusT = 1f;
	private static float lastAxeFrac = 1f;

	private LumberHud() {}

	public static void render(GuiGraphicsExtractor g) {
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		A.frame();
		boolean show = LumberConfig.showHud && LumberBot.isEnabled() && p != null && !mc.gui.hud.isHidden()
				&& mc.gui.screen() == null;
		float vis = A.from("vis", 0f, show ? 1f : 0f, show ? 9f : 12f);
		if (vis <= 0.01f || p == null) {
			if (!show) resetCounters();
			return;
		}
		float e = Anim.easeOutCubic(vis);
		Font f = mc.font;

		// stay clear of the vanilla potion effect icons in the same corner
		int effectRows = 0;
		boolean good = false, bad = false;
		for (MobEffectInstance inst : p.getActiveEffects()) {
			if (!inst.showIcon()) continue;
			if (inst.getEffect().value().isBeneficial()) good = true;
			else bad = true;
		}
		if (bad) effectRows = 2;
		else if (good) effectRows = 1;
		int y = Math.round(A.to("y", 6 + effectRows * 26, 10f));
		int x = g.guiWidth() - W - 6 + Math.round((1 - e) * 24);

		shadow(g, x, y, W, H, e);
		round(g, x, y, W, H, 4, alpha(BG, e * 0.9f));
		round(g, x + 6, y, W - 12, 2, 1, alpha(ACCENT, e));

		// ---- header
		round(g, x + 8, y + 8, 8, 6, 2, alpha(ACCENT, e));
		g.fill(x + 11, y + 14, x + 13, y + 17, alpha(WOOD, e));
		g.text(f, "AutoLumber", x + 21, y + 8, alpha(TEXT, e), true);

		boolean paused = LumberBot.isPaused();
		int pillColor = paused ? YELLOW : ACCENT;
		String pill = paused ? "PAUSED" : "RUNNING";
		int pw = f.width(pill) + 14;
		int px = x + W - 8 - pw;
		round(g, px, y + 6, pw, 12, 3, alpha(pillColor, e * 0.16f));
		float pulse = (float) (0.45 + 0.55 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 320.0)));
		round(g, px + 4, y + 10, 4, 4, 2, alpha(pillColor, e * pulse));
		g.text(f, pill, px + 10, y + 8, alpha(pillColor, e), false);

		// ---- status (cross-fades when it changes)
		String status = LumberBot.status();
		if (!status.equals(curStatus)) {
			prevStatus = curStatus;
			curStatus = status;
			statusT = 0;
		}
		statusT = Math.min(1f, statusT + A.dt() * 5f);
		float st = Anim.easeOutCubic(statusT);
		int sy = y + 23;
		if (st < 1f && !prevStatus.isEmpty()) {
			g.text(f, fit(f, prevStatus, W - 16), x + 8, sy - Math.round(st * 4), alpha(MUTED, e * (1 - st)), false);
		}
		g.text(f, fit(f, curStatus, W - 16), x + 8, sy + Math.round((1 - st) * 4), alpha(TEXT, e * st), false);

		g.fill(x + 8, y + 36, x + W - 8, y + 37, alpha(BORDER, e));

		// ---- stats
		int colW = (W - 16) / 3;
		stat(g, f, x + 8, y + 41, colW, count("logs", Stats.logs), "Logs", WOOD, e);
		stat(g, f, x + 8 + colW, y + 41, colW, count("trees", Stats.trees), "Trees", ACCENT, e);
		stat(g, f, x + 8 + colW * 2, y + 41, colW, count("rate", Stats.logsPerHour()), "Per hour", ACCENT_2, e);

		g.fill(x + 8, y + 64, x + W - 8, y + 65, alpha(BORDER, e));

		// ---- axe
		ItemStack hand = p.getMainHandItem();
		String axeVal;
		if (hand.is(ItemTags.AXES)) {
			if (hand.isDamageableItem()) {
				int left = hand.getMaxDamage() - hand.getDamageValue();
				lastAxeFrac = left / (float) hand.getMaxDamage();
				axeVal = left + " / " + hand.getMaxDamage();
			} else {
				lastAxeFrac = 1;
				axeVal = "unbreakable";
			}
		} else {
			axeVal = "switching...";  // eating / replanting: keep the last axe reading
		}
		float axeShown = A.to("axe", lastAxeFrac, 8f);
		row(g, f, x + 8, y + 69, W - 16, "Axe", axeVal, health(axeShown), axeShown, health(axeShown), e);

		// ---- hunger
		int food = p.getFoodData().getFoodLevel();
		float foodShown = A.to("food", food / 20f, 8f);
		row(g, f, x + 8, y + 85, W - 16, "Hunger", food + " / 20", food <= LumberConfig.eatAtHunger ? YELLOW : TEXT,
				foodShown, WOOD, e);

		// ---- footer
		g.text(f, "Session", x + 8, y + 101, alpha(MUTED, e), false);
		String time = Stats.elapsed();
		g.text(f, time, x + W - 8 - f.width(time), y + 101, alpha(MUTED, e), false);
	}

	private static String fit(Font f, String s, int w) {
		return f.width(s) <= w ? s : f.plainSubstrByWidth(s, w - f.width("...")) + "...";
	}

	/** Smoothly counting number; drops instantly when stats reset. */
	private static int count(String key, int value) {
		float cur = A.to(key, value, 0);
		if (value < cur) {
			A.set(key, value);
			return value;
		}
		return Math.round(A.to(key, value, 10f));
	}

	private static void stat(GuiGraphicsExtractor g, Font f, int x, int y, int w, int value, String label, int color, float e) {
		String v = String.valueOf(value);
		g.text(f, v, x + (w - f.width(v)) / 2, y, alpha(color, e), true);
		g.text(f, label, x + (w - f.width(label)) / 2, y + 11, alpha(MUTED, e), false);
	}

	private static void row(GuiGraphicsExtractor g, Font f, int x, int y, int w, String label, String value, int valueColor,
							float frac, int barColor, float e) {
		g.text(f, label, x, y, alpha(MUTED, e), false);
		g.text(f, value, x + w - f.width(value), y, alpha(valueColor, e), false);
		round(g, x, y + 11, w, 3, 1, alpha(0xFF0A0E11, e));
		int fw = Math.round(Math.max(0, Math.min(1, frac)) * w);
		if (fw > 0) round(g, x, y + 11, fw, 3, 1, alpha(barColor, e));
	}

	private static void resetCounters() {
		curStatus = "";
		prevStatus = "";
		statusT = 1f;
	}
}
