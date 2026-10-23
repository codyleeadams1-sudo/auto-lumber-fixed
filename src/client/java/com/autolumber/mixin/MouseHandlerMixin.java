package com.autolumber.mixin;

import com.autolumber.bot.Rotation;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the bot's camera movement every frame, alongside normal mouse turning. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "turnPlayer", at = @At("TAIL"))
	private void autolumber$smoothTurn(double mousea, CallbackInfo ci) {
		Rotation.onFrame();
	}
}
