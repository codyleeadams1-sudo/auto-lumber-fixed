package com.autolumber.bot;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Drives the player through the normal vanilla key bindings, exactly as if
 * the keys were being held on the keyboard / mouse. Works for both "hold"
 * and "toggle" key modes.
 */
public final class InputControl {
	private static boolean controlling = false;

	private InputControl() {}

	public static void set(KeyMapping key, boolean want) {
		controlling = true;
		if (key.isDown() != want) {
			key.setDown(want);
			// Toggle-mode keys ignore setDown(false) and flip on setDown(true).
			if (key.isDown() != want) key.setDown(true);
		}
	}

	public static void forward(boolean down) {
		set(Minecraft.getInstance().options.keyUp, down);
	}

	public static void jump(boolean down) {
		set(Minecraft.getInstance().options.keyJump, down);
	}

	public static void attack(boolean down) {
		set(Minecraft.getInstance().options.keyAttack, down);
	}

	public static void use(boolean down) {
		set(Minecraft.getInstance().options.keyUse, down);
	}

	/** Lets go of every key the bot was pressing. */
	public static void releaseAll() {
		if (!controlling) return;
		Minecraft mc = Minecraft.getInstance();
		set(mc.options.keyUp, false);
		set(mc.options.keyJump, false);
		set(mc.options.keyAttack, false);
		set(mc.options.keyUse, false);
		if (mc.player != null) mc.player.setSprinting(false);
		controlling = false;
		// Re-sync with whatever the user is physically holding.
		KeyMapping.setAll();
	}
}
