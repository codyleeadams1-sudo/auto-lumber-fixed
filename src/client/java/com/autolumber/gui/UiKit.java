package com.autolumber.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Shared colours and drawing helpers for the AutoLumber UI. */
public final class UiKit {
	public static final int BG = 0xF00D1114;
	public static final int PANEL = 0xF0151B20;
	public static final int PANEL_HI = 0xF01D252C;
	public static final int CARD = 0xF0192026;
	public static final int BORDER = 0xFF26313A;
	public static final int TEXT = 0xFFE9EEF2;
	public static final int MUTED = 0xFF8A97A3;
	public static final int ACCENT = 0xFF46C46E;      // leaf green
	public static final int ACCENT_2 = 0xFF2E9E8F;    // teal
	public static final int WOOD = 0xFFC08A4E;
	public static final int RED = 0xFFE0565B;
	public static final int YELLOW = 0xFFE8C14A;

	private UiKit() {}

	/** Rectangle with softly cut corners (radius up to ~3). */
	public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int c) {
		if (w <= 0 || h <= 0) return;
		r = Math.min(r, Math.min(w, h) / 2);
		if (r <= 0) {
			g.fill(x, y, x + w, y + h, c);
			return;
		}
		g.fill(x + r, y, x + w - r, y + h, c);
		g.fill(x, y + r, x + r, y + h - r, c);
		g.fill(x + w - r, y + r, x + w, y + h - r, c);
		if (r >= 2) {
			g.fill(x + 1, y + 1, x + r, y + r, c);
			g.fill(x + w - r, y + 1, x + w - 1, y + r, c);
			g.fill(x + 1, y + h - r, x + r, y + h - 1, c);
			g.fill(x + w - r, y + h - r, x + w - 1, y + h - 1, c);
		}
	}

	public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, float alpha) {
		for (int i = 6; i >= 1; i--) {
			int a = Math.round((0x0A * (7 - i)) * alpha);
			round(g, x - i, y - i + 2, w + i * 2, h + i * 2, i + 2, a << 24);
		}
	}

	public static int alpha(int argb, float f) {
		int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, f)));
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	public static int lerp(int c1, int c2, float t) {
		t = Math.max(0, Math.min(1, t));
		int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
		int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
		int gr = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
		int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
		return (a << 24) | (r << 16) | (gr << 8) | b;
	}

	/** Colour for a 0..1 fraction: red -> yellow -> green. */
	public static int health(float f) {
		return f < 0.5f ? lerp(RED, YELLOW, f * 2) : lerp(YELLOW, ACCENT, (f - 0.5f) * 2);
	}

	public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float f, int color) {
		round(g, x, y, w, h, 1, 0xFF0A0E11);
		int fw = Math.round(Math.max(0, Math.min(1, f)) * w);
		if (fw > 0) round(g, x, y, fw, h, 1, color);
	}

	public static boolean in(double mx, double my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}
}
