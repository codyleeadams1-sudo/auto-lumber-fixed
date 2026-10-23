package com.autolumber.gui;

import com.autolumber.AutoLumber;
import com.autolumber.bot.LumberBot;
import com.autolumber.bot.Stats;
import com.autolumber.config.LogType;
import com.autolumber.config.LumberConfig;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static com.autolumber.gui.UiKit.*;

/** The AutoLumber control panel. */
public class LumberScreen extends Screen {
	private static final int W = 384, H = 244, SIDE = 92, HEAD = 34;

	private enum Tab {
		OVERVIEW("Overview"), CHOPPING("Chopping"), LOGS("Log Types"), SURVIVAL("Survival"), MOVEMENT("Movement");
		final String label;
		Tab(String l) { label = l; }
	}

	private interface Widget {}
	private record Toggle(String label, String hint, Supplier<Boolean> get, Consumer<Boolean> set) implements Widget {}
	private record Slider(String label, int min, int max, IntSupplier get, IntConsumer set, String unit) implements Widget {}

	private static final int[] LOG_COLORS = {
			0xFFB8945F, 0xFF7A5A3A, 0xFFD8D3C2, 0xFFA67C52, 0xFFB0603A, 0xFF4E3620,
			0xFF7A3B32, 0xFFE6A8B8, 0xFFD9CFC6, 0xFFC9B27A, 0xFF8E2E45, 0xFF2E8C83};

	private static Tab tab = Tab.OVERVIEW;
	private final long openMs = System.currentTimeMillis();
	private final Anim anim = new Anim();
	private Slider dragging;
	private int dragX, dragW;
	private float tabAnim = 0;

	public LumberScreen() {
		super(Component.literal("AutoLumber"));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		super.removed();
		LumberConfig.save();
	}

	// ---------------------------------------------------------------- tab contents

	private List<Widget> widgets(Tab t) {
		List<Widget> w = new ArrayList<>();
		switch (t) {
			case CHOPPING -> {
				w.add(new Toggle("Break leaves", "Clears leaves blocking the path or log", () -> LumberConfig.breakLeaves, v -> LumberConfig.breakLeaves = v));
				w.add(new Toggle("Pick up drops", "Collects logs, saplings and apples", () -> LumberConfig.collectDrops, v -> LumberConfig.collectDrops = v));
				w.add(new Toggle("Replant saplings", "Plants a sapling where the tree stood", () -> LumberConfig.replant, v -> LumberConfig.replant = v));
				w.add(new Toggle("Natural trees only", "Skips logs without natural leaves (builds)", () -> LumberConfig.naturalTreesOnly, v -> LumberConfig.naturalTreesOnly = v));
				w.add(new Toggle("Visible trees only", "Only picks trees you have line of sight to", () -> LumberConfig.visibleOnly, v -> LumberConfig.visibleOnly = v));
				w.add(new Toggle("Avoid other players", "Leaves trees near other players alone", () -> LumberConfig.avoidPlayers, v -> LumberConfig.avoidPlayers = v));
				w.add(new Toggle("Auto-swap axe", "Switches to a fresh axe before one breaks", () -> LumberConfig.autoSwapAxe, v -> LumberConfig.autoSwapAxe = v));
				w.add(new Slider("Search radius", 8, 64, () -> LumberConfig.searchRadius, v -> LumberConfig.searchRadius = v, " blocks"));
				w.add(new Slider("Keep axe at", 1, 200, () -> LumberConfig.minDurability, v -> LumberConfig.minDurability = v, " uses"));
				w.add(new Slider("Player distance", 4, 48, () -> LumberConfig.avoidPlayerRadius, v -> LumberConfig.avoidPlayerRadius = v, " blocks"));
			}
			case SURVIVAL -> {
				w.add(new Toggle("Auto eat", "Eats the best safe food when hungry", () -> LumberConfig.autoEat, v -> LumberConfig.autoEat = v));
				w.add(new Toggle("Stop without food", "Stops if starving with nothing to eat", () -> LumberConfig.stopWithoutFood, v -> LumberConfig.stopWithoutFood = v));
				w.add(new Toggle("Stop when hurt", "Stops the moment you take damage", () -> LumberConfig.stopWhenHurt, v -> LumberConfig.stopWhenHurt = v));
				w.add(new Toggle("Stop near mobs", "Stops if a hostile mob gets close", () -> LumberConfig.stopNearMobs, v -> LumberConfig.stopNearMobs = v));
				w.add(new Toggle("Show HUD", "Live panel in the top-left corner", () -> LumberConfig.showHud, v -> LumberConfig.showHud = v));
				w.add(new Slider("Eat at hunger", 6, 19, () -> LumberConfig.eatAtHunger, v -> LumberConfig.eatAtHunger = v, " / 20"));
				w.add(new Slider("Mob distance", 3, 24, () -> LumberConfig.mobRadius, v -> LumberConfig.mobRadius = v, " blocks"));
			}
			case MOVEMENT -> {
				w.add(new Toggle("Sprint on long walks", "Sprints on long straight stretches", () -> LumberConfig.sprint, v -> LumberConfig.sprint = v));
				w.add(new Toggle("Human pauses", "Short breaks and glances between trees", () -> LumberConfig.humanPauses, v -> LumberConfig.humanPauses = v));
				w.add(new Slider("Turn speed", 5, 45, () -> LumberConfig.turnSpeed, v -> LumberConfig.turnSpeed = v, ""));
				w.add(new Slider("Reaction delay", 0, 20, () -> LumberConfig.reactionDelay, v -> LumberConfig.reactionDelay = v, " ticks"));
			}
			default -> {}
		}
		return w;
	}

	// ---------------------------------------------------------------- layout

	private int ox() { return (this.width - W) / 2; }
	private int oy() { return (this.height - H) / 2; }
	private int cx() { return ox() + SIDE + 10; }
	private int cy() { return oy() + HEAD + 8; }
	private int cw() { return W - SIDE - 20; }

	private int[] tabRect(int i) { return new int[]{ox() + 6, oy() + HEAD + 8 + i * 22, SIDE - 12, 19}; }
	private int[] powerRect() { return new int[]{ox() + W - 86, oy() + 8, 78, 18}; }

	private static boolean in(double mx, double my, int[] r) {
		return UiKit.in(mx, my, r[0], r[1], r[2], r[3]);
	}

	/** Time-based easing toward a target (same feel at any frame rate). */
	private float approach(String key, float target, float speed) {
		return anim.to(key, target, speed * 60f);
	}

	private int countUp(String key, int value) {
		float cur = anim.from(key, 0, value, 0);
		if (value < cur) {
			anim.set(key, value);
			return value;
		}
		return Math.round(anim.to(key, value, 9f));
	}

	// ---------------------------------------------------------------- render

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float partial) {
		super.extractRenderState(g, mx, my, partial);
		float t = Math.min(1f, (System.currentTimeMillis() - openMs) / 180f);
		float ease = 1 - (1 - t) * (1 - t) * (1 - t);
		anim.frame();
		tabAnim = Math.min(1f, tabAnim + anim.dt() * 7f);

		g.fill(0, 0, this.width, this.height, alpha(0xA0050709, ease));

		g.pose().pushMatrix();
		float scale = 0.94f + 0.06f * ease;
		g.pose().translate(this.width / 2f, this.height / 2f);
		g.pose().scale(scale, scale);
		g.pose().translate(-this.width / 2f, -this.height / 2f + (1 - ease) * 8);

		int x = ox(), y = oy();
		shadow(g, x, y, W, H, ease);
		round(g, x, y, W, H, 4, alpha(BG, ease));
		round(g, x, y, SIDE, H, 4, alpha(PANEL, ease));
		g.fill(x + SIDE, y, x + SIDE + 1, y + H, alpha(BORDER, ease));

		drawHeader(g, x, y, mx, my, ease);
		drawTabs(g, mx, my, ease);

		float slide = 1 - Anim.easeOutCubic(tabAnim);
		int cx = cx() + Math.round(slide * 12);
		switch (tab) {
			case OVERVIEW -> drawOverview(g, cx, cy(), mx, my);
			case LOGS -> drawLogs(g, cx, cy(), mx, my);
			default -> drawWidgets(g, cx, cy(), mx, my, widgets(tab));
		}
		g.pose().popMatrix();
	}

	private void drawHeader(GuiGraphicsExtractor g, int x, int y, int mx, int my, float a) {
		// logo: a little tree
		round(g, x + 10, y + 9, 12, 10, 3, alpha(ACCENT, a));
		g.fill(x + 15, y + 19, x + 17, y + 25, alpha(WOOD, a));
		g.text(this.font, "AutoLumber", x + 27, y + 9, alpha(TEXT, a), true);
		g.text(this.font, "v" + AutoLumber.VERSION, x + 27, y + 20, alpha(MUTED, a), false);

		String st = LumberBot.isEnabled() ? "\u25CF " + LumberBot.status() : "\u25CB Idle";
		int stx = x + SIDE + 10;
		int room = powerRect()[0] - 8 - stx;
		if (this.font.width(st) > room) st = this.font.plainSubstrByWidth(st, room - 10) + "...";
		g.text(this.font, st, stx, y + 13,
				alpha(LumberBot.isEnabled() ? ACCENT : MUTED, a), false);

		int[] pr = powerRect();
		boolean on = LumberBot.isEnabled();
		boolean hov = in(mx, my, pr);
		float glow = (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 300.0));
		int base = on ? RED : ACCENT;
		if (!on) round(g, pr[0] - 2, pr[1] - 2, pr[2] + 4, pr[3] + 4, 4, alpha(ACCENT, a * 0.25f * glow));
		round(g, pr[0], pr[1], pr[2], pr[3], 3, alpha(hov ? lerp(base, 0xFFFFFFFF, 0.15f) : base, a));
		String label = on ? "STOP" : "START";
		g.text(this.font, label, pr[0] + (pr[2] - this.font.width(label)) / 2, pr[1] + 5, alpha(0xFFFFFFFF, a), true);
		g.fill(x + SIDE + 1, y + HEAD - 1, x + W, y + HEAD, alpha(BORDER, a));
	}

	private void drawTabs(GuiGraphicsExtractor g, int mx, int my, float a) {
		Tab[] tabs = Tab.values();
		for (int i = 0; i < tabs.length; i++) {
			int[] r = tabRect(i);
			boolean sel = tabs[i] == tab;
			float h = approach("tab" + i, sel ? 1f : (in(mx, my, r) ? 0.45f : 0f), 0.3f);
			if (h > 0.01f) round(g, r[0], r[1], r[2], r[3], 3, alpha(PANEL_HI, a * h));
			if (sel) round(g, r[0], r[1] + 3, 2, r[3] - 6, 1, alpha(ACCENT, a));
			g.text(this.font, tabs[i].label, r[0] + 9, r[1] + 6, alpha(sel ? TEXT : MUTED, a), false);
		}
		String k1 = "Toggle  " + AutoLumber.keyName(AutoLumber.toggleKey);
		String k2 = "Menu    " + AutoLumber.keyName(AutoLumber.menuKey);
		g.text(this.font, k1, ox() + 9, oy() + H - 26, alpha(MUTED, a), false);
		g.text(this.font, k2, ox() + 9, oy() + H - 14, alpha(MUTED, a), false);
	}

	private void card(GuiGraphicsExtractor g, int x, int y, int w, int h, String label, String value, int color) {
		round(g, x, y, w, h, 3, CARD);
		round(g, x, y + 4, 2, h - 8, 1, alpha(color, 0.8f));
		String l = this.font.width(label) > w - 10 ? this.font.plainSubstrByWidth(label, w - 14) + "..." : label;
		g.text(this.font, l, x + 7, y + 5, MUTED, false);
		g.text(this.font, value, x + 7, y + 16, color, true);
	}

	private void drawOverview(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
		int w = cw();
		int cwid = (w - 8) / 3, ch = 30;
		card(g, x, y, cwid, ch, "Logs chopped", String.valueOf(countUp("c:logs", Stats.logs)), WOOD);
		card(g, x + cwid + 4, y, cwid, ch, "Trees felled", String.valueOf(countUp("c:trees", Stats.trees)), ACCENT);
		card(g, x + (cwid + 4) * 2, y, cwid, ch, "Logs / hour", String.valueOf(countUp("c:rate", Stats.logsPerHour())), TEXT);
		int y2 = y + ch + 4;
		card(g, x, y2, cwid, ch, "Run time", Stats.elapsed(), TEXT);
		card(g, x + cwid + 4, y2, cwid, ch, "Saplings", String.valueOf(countUp("c:sap", Stats.saplings)), ACCENT_2);
		card(g, x + (cwid + 4) * 2, y2, cwid, ch, "Meals \u00B7 Swaps", Stats.meals + " / " + Stats.axeSwaps, YELLOW);

		int y3 = y2 + ch + 8;
		LocalPlayer p = this.minecraft != null ? this.minecraft.player : null;
		round(g, x, y3, w, 44, 3, CARD);
		if (p != null) {
			ItemStack hand = p.getMainHandItem();
			float frac = 0;
			String axe = "Not holding an axe";
			if (hand.is(ItemTags.AXES)) {
				if (hand.isDamageableItem()) {
					int left = hand.getMaxDamage() - hand.getDamageValue();
					frac = left / (float) hand.getMaxDamage();
					axe = hand.getHoverName().getString() + "  " + left + "/" + hand.getMaxDamage();
				} else {
					frac = 1;
					axe = hand.getHoverName().getString();
				}
			}
			float shownAxe = approach("axeBar", frac, 0.15f);
			g.text(this.font, axe, x + 6, y3 + 5, TEXT, false);
			bar(g, x + 6, y3 + 15, w - 12, 4, shownAxe, health(frac));
			int food = p.getFoodData().getFoodLevel();
			float shownFood = approach("foodBar", food / 20f, 0.15f);
			g.text(this.font, "Hunger " + food + "/20", x + 6, y3 + 24, TEXT, false);
			bar(g, x + 6, y3 + 34, w - 12, 4, shownFood, WOOD);
		}

		int y4 = y3 + 52;
		String logs = LumberConfig.enabledLogs.size() == LogType.values().length ? "all log types"
				: LumberConfig.enabledLogs.size() + " log type" + (LumberConfig.enabledLogs.size() == 1 ? "" : "s");
		g.text(this.font, "Chopping " + logs + " within " + LumberConfig.searchRadius + " blocks.", x, y4, MUTED, false);
		g.text(this.font, "Auto eat " + (LumberConfig.autoEat ? "on" : "off") + "  \u00B7  Replant "
				+ (LumberConfig.replant ? "on" : "off") + "  \u00B7  Axe kept at " + LumberConfig.minDurability, x, y4 + 11, MUTED, false);

		int[] rs = {x + w - 70, y4 + 26, 70, 16};
		round(g, rs[0], rs[1], rs[2], rs[3], 3, in(mx, my, rs) ? 0xFF33404C : 0xFF26313A);
		g.text(this.font, "Reset stats", rs[0] + (rs[2] - this.font.width("Reset stats")) / 2, rs[1] + 4, TEXT, false);
	}

	private int[] resetRect() {
		int x = cx(), w = cw();
		int y4 = cy() + (30 + 4) * 2 + 8 + 52;
		return new int[]{x + w - 70, y4 + 26, 70, 16};
	}

	private int[] logRect(int i) {
		int colW = (cw() - 6) / 2;
		return new int[]{cx() + (i % 2) * (colW + 6), cy() + (i / 2) * 22, colW, 19};
	}

	private int[] allRect() { return new int[]{cx(), cy() + 6 * 22 + 6, 60, 16}; }
	private int[] noneRect() { return new int[]{cx() + 66, cy() + 6 * 22 + 6, 60, 16}; }

	private void drawLogs(GuiGraphicsExtractor g, int x, int y, int mx, int my) {
		LogType[] types = LogType.values();
		int shift = x - cx();
		for (int i = 0; i < types.length; i++) {
			int[] r = logRect(i);
			r[0] += shift;
			boolean on = LumberConfig.enabledLogs.contains(types[i]);
			float h = approach("log" + i, on ? 1f : 0f, 0.3f);
			boolean hov = in(mx, my, r);
			round(g, r[0], r[1], r[2], r[3], 3, hov ? PANEL_HI : CARD);
			round(g, r[0] + 5, r[1] + 5, 9, 9, 2, alpha(LOG_COLORS[i], 0.35f + 0.65f * h));
			g.text(this.font, types[i].label(), r[0] + 19, r[1] + 6, lerp(MUTED, TEXT, h), false);
			drawSwitch(g, r[0] + r[2] - 24, r[1] + 5, h);
		}
		int[] a = allRect(), n = noneRect();
		a[0] += shift;
		n[0] += shift;
		round(g, a[0], a[1], a[2], a[3], 3, in(mx, my, a) ? 0xFF33404C : 0xFF26313A);
		g.text(this.font, "All", a[0] + (a[2] - this.font.width("All")) / 2, a[1] + 4, TEXT, false);
		round(g, n[0], n[1], n[2], n[3], 3, in(mx, my, n) ? 0xFF33404C : 0xFF26313A);
		g.text(this.font, "None", n[0] + (n[2] - this.font.width("None")) / 2, n[1] + 4, TEXT, false);
		g.text(this.font, LumberConfig.enabledLogs.size() + " selected", n[0] + n[2] + 10, n[1] + 4, MUTED, false);
	}

	private void drawSwitch(GuiGraphicsExtractor g, int x, int y, float h) {
		round(g, x, y, 18, 9, 4, lerp(0xFF2A333C, ACCENT, h));
		int kx = x + 1 + Math.round(h * 9);
		round(g, kx, y + 1, 7, 7, 3, 0xFFFFFFFF);
	}

	private int rowY(List<Widget> ws, int index) {
		int y = cy();
		for (int i = 0; i < index; i++) y += ws.get(i) instanceof Toggle ? 18 : 24;
		return y;
	}

	private void drawWidgets(GuiGraphicsExtractor g, int x, int y, int mx, int my, List<Widget> ws) {
		int w = cw();
		for (int i = 0; i < ws.size(); i++) {
			int ry = rowY(ws, i);
			if (ws.get(i) instanceof Toggle t) {
				boolean hov = UiKit.in(mx, my, x, ry, w, 16);
				if (hov) round(g, x - 3, ry, w + 6, 16, 3, PANEL_HI);
				float h = approach("t:" + t.label(), t.get().get() ? 1f : 0f, 0.3f);
				g.text(this.font, t.label(), x, ry + 4, TEXT, false);
				int lw = this.font.width(t.label());
				String hint = t.hint();
				int room = w - lw - 40;
				if (room > 30) {
					if (this.font.width(hint) > room) hint = this.font.plainSubstrByWidth(hint, room - 8) + "...";
					g.text(this.font, hint, x + lw + 8, ry + 4, 0xFF5F6B76, false);
				}
				drawSwitch(g, x + w - 20, ry + 3, h);
			} else if (ws.get(i) instanceof Slider s) {
				int v = s.get().getAsInt();
				g.text(this.font, s.label(), x, ry + 2, TEXT, false);
				String val = v + s.unit();
				g.text(this.font, val, x + w - this.font.width(val), ry + 2, ACCENT, false);
				int by = ry + 14;
				float f = (v - s.min()) / (float) (s.max() - s.min());
				float shown = dragging == s ? f : approach("s:" + s.label(), f, 0.35f);
				round(g, x, by, w, 4, 2, 0xFF0A0E11);
				round(g, x, by, Math.max(2, Math.round(shown * w)), 4, 2, ACCENT);
				int kx = x + Math.round(shown * w);
				boolean active = dragging == s || UiKit.in(mx, my, x, ry, w, 22);
				round(g, kx - 4, by - 2, 8, 8, 3, active ? 0xFFFFFFFF : 0xFFD5DCE2);
			}
		}
		if (tab == Tab.MOVEMENT) {
			int ny = rowY(ws, ws.size()) + 8;
			g.text(this.font, "The camera turns every frame with smooth easing,", x, ny, MUTED, false);
			g.text(this.font, "and paths are walked in straight lines.", x, ny + 10, MUTED, false);
			g.text(this.font, "Lower turn speed = lazier, more relaxed aim.", x, ny + 22, MUTED, false);
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x(), my = event.y();
		if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);

		if (in(mx, my, powerRect())) {
			if (LumberBot.isEnabled()) {
				LumberBot.stop("Turned off");
			} else {
				// the bot stops whenever a menu is open, so close this one and go
				this.onClose();
				LumberBot.start();
			}
			return true;
		}
		Tab[] tabs = Tab.values();
		for (int i = 0; i < tabs.length; i++) {
			if (in(mx, my, tabRect(i))) {
				if (tab != tabs[i]) {
					tab = tabs[i];
					tabAnim = 0;
				}
				return true;
			}
		}
		switch (tab) {
			case OVERVIEW -> {
				if (in(mx, my, resetRect())) {
					Stats.reset();
					return true;
				}
			}
			case LOGS -> {
				LogType[] types = LogType.values();
				for (int i = 0; i < types.length; i++) {
					if (in(mx, my, logRect(i))) {
						if (!LumberConfig.enabledLogs.remove(types[i])) LumberConfig.enabledLogs.add(types[i]);
						return true;
					}
				}
				if (in(mx, my, allRect())) {
					LumberConfig.enabledLogs.addAll(List.of(types));
					return true;
				}
				if (in(mx, my, noneRect())) {
					LumberConfig.enabledLogs.clear();
					return true;
				}
			}
			default -> {
				List<Widget> ws = widgets(tab);
				int x = cx(), w = cw();
				for (int i = 0; i < ws.size(); i++) {
					int ry = rowY(ws, i);
					if (ws.get(i) instanceof Toggle t && UiKit.in(mx, my, x - 3, ry, w + 6, 16)) {
						t.set().accept(!t.get().get());
						return true;
					}
					if (ws.get(i) instanceof Slider s && UiKit.in(mx, my, x - 4, ry, w + 8, 22)) {
						dragging = s;
						dragX = x;
						dragW = w;
						updateSlider(mx);
						return true;
					}
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging != null) {
			updateSlider(event.x());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging != null) {
			dragging = null;
			LumberConfig.save();
			return true;
		}
		return super.mouseReleased(event);
	}

	private void updateSlider(double mx) {
		double f = Math.max(0, Math.min(1, (mx - dragX) / dragW));
		dragging.set().accept((int) Math.round(dragging.min() + f * (dragging.max() - dragging.min())));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (AutoLumber.isMenuKey(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
