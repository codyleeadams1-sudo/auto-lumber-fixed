package com.autolumber.bot;

import com.autolumber.config.LumberConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Smooth, mouse-like camera movement.
 *
 * The bot only sets a target; the camera is moved every rendered frame (from
 * the mouse handler, like real mouse input) with a critically damped spring,
 * so turns accelerate, glide and settle without snapping or overshooting.
 * A faint, slow hand drift keeps the view from looking robotic.
 */
public final class Rotation {
	private static boolean active;
	private static float targetYaw, targetPitch;
	private static double velYaw, velPitch;
	private static long lastFrameNs;
	private static double clock;
	private static final double SEED = Math.random() * 1000;

	private Rotation() {}

	// ------------------------------------------------------------ math helpers

	public static float yawTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dz = to.z - from.z;
		return (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90f;
	}

	public static float pitchTo(Vec3 from, Vec3 to) {
		double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		return (float) -(Mth.atan2(dy, h) * Mth.RAD_TO_DEG);
	}

	public static float yawError(LocalPlayer p, float yaw) {
		return Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
	}

	/** Angle (degrees) between where the player looks and the given point. */
	public static float errorTo(LocalPlayer p, Vec3 point) {
		Vec3 eye = p.getEyePosition();
		float dy = Mth.wrapDegrees(yawTo(eye, point) - p.getYRot());
		float dp = pitchTo(eye, point) - p.getXRot();
		return (float) Math.sqrt(dy * dy + dp * dp);
	}

	// ------------------------------------------------------------ targets

	public static void lookAt(LocalPlayer p, Vec3 point) {
		Vec3 eye = p.getEyePosition();
		setTarget(yawTo(eye, point), pitchTo(eye, point));
	}

	public static void setTarget(float yaw, float pitch) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (!active && p != null) {
			velYaw = velPitch = 0;
		}
		active = true;
		// keep the target close to the current yaw so wrap-around never spins the long way
		float base = p != null ? p.getYRot() : 0;
		targetYaw = base + Mth.wrapDegrees(yaw - base);
		targetPitch = Mth.clamp(pitch, -89f, 89f);
	}

	public static void release() {
		active = false;
		velYaw = velPitch = 0;
	}

	// ------------------------------------------------------------ per-frame motion

	/** Called every frame from the mouse handler. */
	public static void onFrame() {
		long now = System.nanoTime();
		double dt = lastFrameNs == 0 ? 0 : (now - lastFrameNs) / 1e9;
		lastFrameNs = now;
		step(Math.min(dt, 0.05));
	}

	/** Called every tick; covers the case where frames aren't delivering mouse updates (window not focused). */
	public static void onTick() {
		if (System.nanoTime() - lastFrameNs > 150_000_000L) {
			lastFrameNs = 0;
			step(0.05);
		}
	}

	private static void step(double dt) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (!active || p == null || dt <= 0) return;
		clock += dt;

		// spring stiffness and top speed from the "turn speed" setting
		double speed = LumberConfig.turnSpeed;
		double w = 5.0 + speed * 0.32;           // natural frequency (rad/s)
		double maxVel = 120 + speed * 22;         // deg/s

		// slow hand drift (a few tenths of a degree) on top of the target
		double driftYaw = 0.08 * Math.sin(clock * 1.7 + SEED) + 0.04 * Math.sin(clock * 3.1 + SEED * 2);
		double driftPitch = 0.06 * Math.sin(clock * 1.3 + SEED * 3) + 0.03 * Math.sin(clock * 2.6 + SEED);

		double errYaw = Mth.wrapDegrees(targetYaw + driftYaw - p.getYRot());
		double errPitch = targetPitch + driftPitch - p.getXRot();

		velYaw += (w * w * errYaw - 2 * w * velYaw) * dt;
		velPitch += (w * w * errPitch - 2 * w * velPitch) * dt;
		velYaw = Mth.clamp(velYaw, -maxVel, maxVel);
		velPitch = Mth.clamp(velPitch, -maxVel * 0.8, maxVel * 0.8);

		double dYaw = velYaw * dt;
		double dPitch = velPitch * dt;
		if (Math.abs(dYaw) < 1e-4 && Math.abs(dPitch) < 1e-4) return;

		// same entry point as real mouse movement (turn() scales by 0.15)
		p.turn(dYaw / 0.15, dPitch / 0.15);
	}
}
