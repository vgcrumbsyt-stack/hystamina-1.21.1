package hystamina.mixin

import hystamina.HyStaminaCompatibility
import hystamina.StaminaSystem
import net.minecraft.world.entity.player.Player
import net.minecraft.world.food.FoodData
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(FoodData::class)
class FoodDataMixin {
	@Inject(method = ["tick"], at = [At("HEAD")], cancellable = true)
	private fun replaceVanillaHunger(player: Player, ci: CallbackInfo) {
		if (!HyStaminaCompatibility.isActiveFor(player)) {
			return
		}

		StaminaSystem.onFoodTick(this as FoodData, player)
		ci.cancel()
	}
}