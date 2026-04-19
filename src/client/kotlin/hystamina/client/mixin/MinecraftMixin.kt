package hystamina.client.mixin

import net.minecraft.client.Minecraft
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Minecraft::class)
class MinecraftMixin {
	@Inject(method = ["createTitle"], at = [At("RETURN")], cancellable = true)
	private fun appendTestClientNameToWindowTitle(cir: CallbackInfoReturnable<String>) {
		val testClientName = System.getProperty("hystamina.testClientName")
			?.trim()
			?.takeIf { it.isNotEmpty() }
			?: return

		cir.returnValue = "$testClientName - ${cir.returnValue}"
	}
}