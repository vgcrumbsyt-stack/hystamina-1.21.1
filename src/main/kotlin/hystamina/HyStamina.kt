package hystamina

import org.slf4j.LoggerFactory

import net.fabricmc.api.ModInitializer

object HyStamina : ModInitializer {
	const val MOD_ID = "hystamina"

	@JvmField
	val LOGGER = LoggerFactory.getLogger(MOD_ID)

	override fun onInitialize() {
		HyStaminaConfig.load()
		HyStaminaDeathCommands.initialize()
		HyStaminaPartySystem.initialize()
		HyStaminaNetworking.initialize()
		StaminaSystem.initialize()
	}
}