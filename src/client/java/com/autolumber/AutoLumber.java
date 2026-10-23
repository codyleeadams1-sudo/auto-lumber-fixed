package com.autolumber;

import com.autolumber.bot.LumberBot;
import com.autolumber.config.LumberConfig;
import com.autolumber.gui.LumberScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;

public class AutoLumber implements ClientModInitializer {
	public static final String MOD_ID = "autolumber";
	public static final String VERSION = "2.2.0";

	public static KeyMapping toggleKey;
	public static KeyMapping menuKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.autolumber.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_K, category));
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.autolumber.menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_RCONTROL, category));

		LumberConfig.load();

		ClientTickEvents.END_CLIENT_TICK.register(AutoLumber::onTick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> LumberBot.stop("Disconnected"));
	}

	private static void onTick(Minecraft mc) {
		if (mc.gui.screen() == null) {
			while (toggleKey.consumeClick()) LumberBot.toggle();
			if (menuKey.consumeClick()) mc.gui.setScreen(new LumberScreen());
		}
		LumberBot.tick(mc);
	}

	public static boolean isMenuKey(KeyEvent event) {
		return menuKey != null && menuKey.matches(event);
	}

	public static String keyName(KeyMapping key) {
		return key.getTranslatedKeyMessage().getString();
	}
}
