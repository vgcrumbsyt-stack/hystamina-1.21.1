package hystamina.mixin;

import hystamina.HyStaminaCompatibility;
import hystamina.HyStaminaRuntimeSettings;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Shadow @Final private static AttributeModifier SPEED_MODIFIER_SPRINTING;

	@ModifyArg(
		method = "setSprinting",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/entity/ai/attributes/AttributeInstance;addTransientModifier(Lnet/minecraft/world/entity/ai/attributes/AttributeModifier;)V"
		),
		index = 0
	)
	private AttributeModifier useHytaleLikeSprintSpeed(AttributeModifier original) {
		LivingEntity entity = (LivingEntity) (Object) this;

		if (entity.level().isClientSide && !HyStaminaCompatibility.isClientGameplayEnabled()) {
			return original;
		}

		ResourceLocation modifierId = SPEED_MODIFIER_SPRINTING.id();
		return new AttributeModifier(
			modifierId,
			HyStaminaRuntimeSettings.getSprintSpeedModifier(),
			AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL
		);
	}
}