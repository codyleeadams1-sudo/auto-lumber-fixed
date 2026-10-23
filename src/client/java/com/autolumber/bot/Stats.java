package com.autolumber.bot;

import java.util.ArrayDeque;

/**
 * Session statistics shown in the HUD and menu. Everything resets to 0 each
 * time the bot is started.
 */
public final class Stats {
	/** Rate is measured over the most recent 10 minutes of running time. */
	private static final long WINDOW_MS = 10 * 60_000L;
	/** The rate never divides by less than this, so one quick log can't show as thousands per hour. */
	private static final long MIN_SPAN_MS = 60_000L;

	public static int logs, trees, saplings, meals, axeSwaps;
	private static final ArrayDeque<Long> logTimes = new ArrayDeque<>();
	private static long startMs, runMs;
	private static boolean running;

	private Stats() {}

	public static void start() {
		if (!running) {
			startMs = System.currentTimeMillis();
			running = true;
		}
	}

	public static void pause() {
		if (running) {
			runMs += System.currentTimeMillis() - startMs;
			running = false;
		}
	}

	public static void reset() {
		logs = trees = saplings = meals = axeSwaps = 0;
		logTimes.clear();
		runMs = 0;
		startMs = System.currentTimeMillis();
	}

	public static void onLog() {
		logs++;
		logTimes.addLast(elapsedMs());
	}

	/** Running time of this session in milliseconds (time while stopped doesn't count). */
	public static long elapsedMs() {
		return runMs + (running ? System.currentTimeMillis() - startMs : 0);
	}

	public static String elapsed() {
		long s = elapsedMs() / 1000;
		return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
	}

	/** Logs per hour over the recent window. Starts at 0 and settles as data comes in. */
	public static int logsPerHour() {
		long now = elapsedMs();
		while (!logTimes.isEmpty() && logTimes.peekFirst() < now - WINDOW_MS) logTimes.pollFirst();
		if (logTimes.isEmpty()) return 0;
		long span = Math.max(MIN_SPAN_MS, Math.min(WINDOW_MS, now));
		return (int) Math.round(logTimes.size() * 3_600_000.0 / span);
	}
}
