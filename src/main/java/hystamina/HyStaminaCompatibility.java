package hystamina;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;

public final class HyStaminaCompatibility {
	private static volatile boolean clientServerSupportsHyStamina = true;

	private HyStaminaCompatibility() {
	}

	public static boolean isClientGameplayEnabled() {
		if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
			return clientServerSupportsHyStamina;
		}

		return true;
	}

	public static boolean isActiveFor(Player player) {
		if (player.level().isClientSide) {
			return isClientGameplayEnabled();
		}

		return true;
	}

	public static void setClientServerSupported(boolean supported) {
		clientServerSupportsHyStamina = supported;
	}
}