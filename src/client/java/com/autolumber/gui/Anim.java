package com.autolumber.gui;

import java.util.HashMap;
import java.util.Map;

/**
 * Frame-rate independent easing. Each named value glides toward its target
 * with exponential smoothing, so animations look the same at 30 or 240 FPS.
 */
public final class Anim {
	private final Map<String, Float> values = new HashMap<>();
	private long lastNs;
	private float dt;

	/** Call once per frame before using {@link #to}. */
	public void frame() {
		long now = System.nanoTime();
		dt = lastNs == 0 ? 0 : Math.min(0.1f, (now - lastNs) / 1e9f);
		lastNs = now;
	}

	public float dt() {
		return dt;
	}

	/** Moves the value toward {@code target}; {@code speed} ≈ how many "halvings" per second ×0.7. */
	public float to(String key, float target, float speed) {
		Float cur = values.get(key);
		if (cur == null) {
			values.put(key, target);
			return target;
		}
		float k = 1f - (float) Math.exp(-speed * dt);
		float next = cur + (target - cur) * k;
		if (Math.abs(target - next) < 0.001f) next = target;
		values.put(key, next);
		return next;
	}

	/** Starts a value at a specific number the first time it's used. */
	public float from(String key, float start, float target, float speed) {
		values.putIfAbsent(key, start);
		return to(key, target, speed);
	}

	public void set(String key, float v) {
		values.put(key, v);
	}

	public static float easeOutCubic(float t) {
		t = Math.max(0, Math.min(1, t));
		float u = 1 - t;
		return 1 - u * u * u;
	}
}
