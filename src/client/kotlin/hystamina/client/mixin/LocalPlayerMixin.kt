package hystamina.client.mixin

import hystamina.HyStaminaCompatibility
import hystamina.client.HyStaminaClient
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.player.Player
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Suppress("CAST_NEVER_SUCCEEDS")
@Mixin(LocalPlayer::class)
class LocalPlayerMixin {
	@Inject(method = ["hasEnoughFoodToStartSprinting"], at = [At("HEAD")], cancellable = true)
	private fun useStaminaSprintGate(cir: CallbackInfoReturnable<Boolean>) {
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return
		}

		cir.returnValue = HyStaminaClient.canClientStartSprinting(this as Player)
	}
}