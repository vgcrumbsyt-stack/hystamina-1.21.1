package hystamina.client.mixin;

import hystamina.HyStaminaCompatibility;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {
	@Inject(method = "renderHearts", at = @At("HEAD"), cancellable = true)
	private void hystamina$hideVanillaHearts(GuiGraphics guiGraphics, Player player, int x, int y, int height, int offsetHeartIndex, float maxHealth, int currentHealth, int displayHealth, int absorptionAmount, boolean renderHighlight, CallbackInfo ci) {
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return;
		}

		ci.cancel();
	}

	@Inject(method = "renderFood", at = @At("HEAD"), cancellable = true)
	private void hystamina$hideVanillaFoodBar(GuiGraphics guiGraphics, Player player, int x, int y, CallbackInfo ci) {
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return;
		}

		ci.cancel();
	}

	@Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
	private void hystamina$hideVanillaExperienceLevel(GuiGraphics guiGraphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return;
		}

		ci.cancel();
	}
}