package hystamina.mixin;

import hystamina.StaminaSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CakeBlock.class)
public class CakeBlockMixin {
	@Inject(method = "eat", at = @At("RETURN"))
	private static void routeCakeHealing(LevelAccessor level, BlockPos pos, BlockState state, Player player, CallbackInfoReturnable<InteractionResult> cir) {
		if (!(level instanceof Level actualLevel) || actualLevel.isClientSide()) {
			return;
		}

		if (!cir.getReturnValue().consumesAction()) {
			return;
		}

		StaminaSystem.onPlayerEat(player, 2, FoodConstants.saturationByModifier(2, 0.1F));
	}
}