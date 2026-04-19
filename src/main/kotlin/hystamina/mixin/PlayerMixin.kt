package hystamina.mixin

import hystamina.HyStaminaCompatibility
import hystamina.StaminaSystem
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.food.FoodProperties
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Suppress("CAST_NEVER_SUCCEEDS")
@Mixin(Player::class)
class PlayerMixin {
	@Unique
	private var restoreSprintAfterAttack = false

	@Inject(method = ["causeFoodExhaustion"], at = [At("HEAD")], cancellable = true)
	private fun disableVanillaExhaustion(exhaustion: Float, ci: CallbackInfo) {
		if (!HyStaminaCompatibility.isActiveFor(this as Player)) {
			return
		}

		ci.cancel()
	}

	@Inject(method = ["canEat"], at = [At("HEAD")], cancellable = true)
	private fun alwaysAllowFoodConsumption(canAlwaysEat: Boolean, cir: CallbackInfoReturnable<Boolean>) {
		if (!HyStaminaCompatibility.isActiveFor(this as Player)) {
			return
		}

		cir.returnValue = StaminaSystem.canEat(this as Player, canAlwaysEat)
	}

	@Inject(method = ["canSprint"], at = [At("HEAD")], cancellable = true)
	private fun blockSprintWhileExhausted(cir: CallbackInfoReturnable<Boolean>) {
		if (!HyStaminaCompatibility.isActiveFor(this as Player)) {
			return
		}

		cir.returnValue = StaminaSystem.canStartSprinting(this as Player)
	}

	@Inject(method = ["attack"], at = [At("HEAD")])
	private fun captureSprintBeforeAttack(target: Entity, ci: CallbackInfo) {
		val player = this as Player
		restoreSprintAfterAttack = HyStaminaCompatibility.isActiveFor(player) && player.isSprinting
	}

	@Inject(method = ["attack"], at = [At("RETURN")])
	private fun restoreSprintAfterAttack(target: Entity, ci: CallbackInfo) {
		if (!restoreSprintAfterAttack) {
			return
		}

		restoreSprintAfterAttack = false
		val player = this as Player
		if (!HyStaminaCompatibility.isActiveFor(player)) {
			return
		}

		if (!StaminaSystem.canStartSprinting(player)) {
			return
		}

		if (!StaminaSystem.canUseSprintMovement(player)) {
			return
		}

		player.isSprinting = true
	}

	@Inject(method = ["eat"], at = [At("TAIL")])
	private fun foodRestoresHealth(
		level: Level,
		stack: ItemStack,
		foodProperties: FoodProperties,
		cir: CallbackInfoReturnable<ItemStack>
	) {
		if (!HyStaminaCompatibility.isActiveFor(this as Player)) {
			return
		}

		if (!level.isClientSide) {
			StaminaSystem.onPlayerEat(this as Player, foodProperties)
		}
	}
}