package hystamina

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.io.Reader
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path

object HyStaminaConfig {
	private const val DEFAULT_STAMINA_DRAIN_DURATION_SECONDS = 12.0
	private const val DEFAULT_STAMINA_RECHARGE_PER_SECOND = 8.0
	private const val DEFAULT_ATTACK_STAMINA_COST = 8
	private const val DEFAULT_FOOD_HEAL_PERCENT = 75.0

	private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
	private val configPath: Path = FabricLoader.getInstance().configDir.resolve("${HyStamina.MOD_ID}.json")

	@Volatile
	private var localConfig = ConfigData()

	@Volatile
	private var serverOverrideConfig: ConfigData? = null

	fun load() {
		if (Files.notExists(configPath)) {
			localConfig = ConfigData().sanitize()
			writeConfig(localConfig)
			applyRuntimeSettings(effectiveConfig())
			HyStamina.LOGGER.info("Created default HyStamina config at {}", configPath)
			return
		}

		localConfig = try {
			val loadedConfig = readConfig()
			val sanitizedConfig = loadedConfig.config.sanitize()
			if (loadedConfig.needsRewrite || sanitizedConfig != loadedConfig.config) {
				writeConfig(sanitizedConfig)
			}
			sanitizedConfig
		} catch (exception: Exception) {
			HyStamina.LOGGER.error("Failed to read HyStamina config at {}. Using defaults for this session.", configPath, exception)
			ConfigData()
		}

		applyRuntimeSettings(effectiveConfig())
	}

	fun snapshot(): ConfigData {
		return localSnapshot()
	}

	fun localSnapshot(): ConfigData {
		return copyConfig(localConfig)
	}

	fun effectiveSnapshot(): ConfigData {
		return copyConfig(effectiveConfig())
	}

	fun save(updatedConfig: ConfigData) {
		val sanitizedConfig = updatedConfig.sanitize()
		writeConfig(sanitizedConfig)
		localConfig = sanitizedConfig
		applyRuntimeSettings(effectiveConfig())
	}

	fun applyServerOverride(updatedConfig: ConfigData) {
		serverOverrideConfig = updatedConfig.sanitize()
		applyRuntimeSettings(effectiveConfig())
	}

	fun clearServerOverride() {
		serverOverrideConfig = null
		applyRuntimeSettings(effectiveConfig())
	}

	fun hasServerOverride(): Boolean {
		return serverOverrideConfig != null
	}

	fun sprintBlocksPerSecond(): Double {
		return effectiveConfig().movement.sprintBlocksPerSecond.coerceAtLeast(0.1)
	}

	fun sprintSpeedModifier(): Double {
		return sprintBlocksPerSecond() / StaminaSystem.MINECRAFT_DEFAULT_SPRINT_BLOCKS_PER_SECOND - 1.0
	}

	fun sprintSecondsUntilEmpty(): Double {
		return effectiveConfig().stamina.secondsUntilEmpty.coerceAtLeast(0.1)
	}

	fun secondsToFullRecharge(): Double {
		return effectiveConfig().stamina.secondsToFullRecharge.coerceAtLeast(0.1)
	}

	fun miningDepletesStamina(): Boolean {
		return effectiveConfig().stamina.miningDepletesStamina
	}

	fun attackingDepletesStamina(): Boolean {
		return effectiveConfig().stamina.attackingDepletesStamina
	}

	fun attackStaminaCost(): Int {
		return effectiveConfig().stamina.attackStaminaCost.coerceAtLeast(0)
	}

	fun sprintDrainPerSecond(): Double {
		return StaminaSystem.MAX_STAMINA / sprintSecondsUntilEmpty()
	}

	fun rechargePerSecond(): Double {
		return StaminaSystem.MAX_STAMINA / secondsToFullRecharge()
	}

	fun hyFoodEnabled(): Boolean {
		return effectiveConfig().food.hyFood
	}

	fun foodHealingMultiplier(): Double {
		return effectiveConfig().food.healingPercent.coerceAtLeast(0.0) / 100.0
	}

	fun foodHealSpeedMultiplier(): Double {
		return effectiveConfig().food.healSpeedPercent.coerceAtLeast(0.0) / 100.0
	}

	fun exhaustionPenaltyEnabled(): Boolean {
		return effectiveConfig().penalty.enabled
	}

	private fun readConfig(): LoadedConfig {
		Files.newBufferedReader(configPath).use { reader: Reader ->
			val parsedRoot = JsonParser.parseReader(reader)
			if (!parsedRoot.isJsonObject) {
				return LoadedConfig(ConfigData(), true)
			}

			return parseConfig(parsedRoot.asJsonObject)
		}
	}

	private fun parseConfig(root: JsonObject): LoadedConfig {
		val movement = asObject(root.get("movement"))?.let { gson.fromJson(it, MovementSettings::class.java) } ?: MovementSettings()
		val foodResult = parseFoodSettings(asObject(root.get("food")))
		val penalty = asObject(root.get("penalty"))?.let { gson.fromJson(it, PenaltySettings::class.java) } ?: PenaltySettings()
		val staminaResult = parseStaminaSettings(asObject(root.get("stamina")))

		return LoadedConfig(
			config = ConfigData(
				movement = movement,
				stamina = staminaResult.settings,
				food = foodResult.settings,
				penalty = penalty
			),
			needsRewrite = staminaResult.needsRewrite || foodResult.needsRewrite
		)
	}

	private fun parseStaminaSettings(staminaObject: JsonObject?): ParsedStaminaSettings {
		val defaults = StaminaSettings()
		if (staminaObject == null) {
			return ParsedStaminaSettings(defaults, true)
		}

		val legacyMaxStamina = readInt(staminaObject, "maxStamina") ?: StaminaSystem.MAX_STAMINA
		var needsRewrite = staminaObject.has("maxStamina") || staminaObject.has("sprintDrainPerSecond") || staminaObject.has("rechargePerSecond")

		val secondsUntilEmpty = readDouble(staminaObject, "secondsUntilEmpty")
			?: readDouble(staminaObject, "sprintDrainPerSecond")?.let { legacyRate ->
				convertLegacyRateToDuration(legacyRate, legacyMaxStamina, defaults.secondsUntilEmpty)
			}
			?: defaults.secondsUntilEmpty

		val secondsToFullRecharge = readDouble(staminaObject, "secondsToFullRecharge")
			?: readDouble(staminaObject, "rechargePerSecond")?.let { legacyRate ->
				convertLegacyRateToDuration(legacyRate, legacyMaxStamina, defaults.secondsToFullRecharge)
			}
			?: defaults.secondsToFullRecharge

		if (!staminaObject.has("secondsUntilEmpty") || !staminaObject.has("secondsToFullRecharge")) {
			needsRewrite = true
		}

		val miningDepletesStamina = if (staminaObject.has("miningDepletesStamina")) {
			staminaObject.get("miningDepletesStamina").asBoolean
		} else {
			needsRewrite = true
			defaults.miningDepletesStamina
		}

		val attackingDepletesStamina = if (staminaObject.has("attackingDepletesStamina")) {
			staminaObject.get("attackingDepletesStamina").asBoolean
		} else {
			needsRewrite = true
			defaults.attackingDepletesStamina
		}

		val attackStaminaCost = readInt(staminaObject, "attackStaminaCost") ?: run {
			needsRewrite = true
			defaults.attackStaminaCost
		}

		return ParsedStaminaSettings(
			settings = StaminaSettings(
				secondsUntilEmpty = secondsUntilEmpty,
				secondsToFullRecharge = secondsToFullRecharge,
				miningDepletesStamina = miningDepletesStamina,
				attackingDepletesStamina = attackingDepletesStamina,
				attackStaminaCost = attackStaminaCost
			),
			needsRewrite = needsRewrite
		)
	}

	private fun parseFoodSettings(foodObject: JsonObject?): ParsedFoodSettings {
		val defaults = FoodSettings()
		if (foodObject == null) {
			return ParsedFoodSettings(defaults, true)
		}

		var needsRewrite = false
		val hyFood = if (foodObject.has("hyFood")) {
			foodObject.get("hyFood").asBoolean
		} else {
			needsRewrite = true
			defaults.hyFood
		}
		val healingPercent = readDouble(foodObject, "healingPercent") ?: run {
			needsRewrite = true
			defaults.healingPercent
		}
		val healSpeedPercent = readDouble(foodObject, "healSpeedPercent") ?: run {
			needsRewrite = true
			defaults.healSpeedPercent
		}

		return ParsedFoodSettings(
			settings = FoodSettings(
				hyFood = hyFood,
				healingPercent = healingPercent,
				healSpeedPercent = healSpeedPercent
			),
			needsRewrite = needsRewrite
		)
	}

	private fun writeConfig(config: ConfigData) {
		Files.createDirectories(configPath.parent)
		Files.newBufferedWriter(configPath).use { writer: Writer ->
			gson.toJson(config, writer)
		}
	}

	private fun applyRuntimeSettings(config: ConfigData) {
		HyStaminaRuntimeSettings.setSprintSpeedModifier(
			config.movement.sprintBlocksPerSecond.coerceAtLeast(0.1) / StaminaSystem.MINECRAFT_DEFAULT_SPRINT_BLOCKS_PER_SECOND - 1.0
		)
	}

	private fun effectiveConfig(): ConfigData {
		return serverOverrideConfig ?: localConfig
	}

	private fun copyConfig(config: ConfigData): ConfigData {
		return config.copy(
			movement = config.movement.copy(),
			stamina = config.stamina.copy(),
			food = config.food.copy(),
			penalty = config.penalty.copy()
		)
	}

	private fun convertLegacyRateToDuration(legacyRate: Double, legacyMaxStamina: Int, fallback: Double): Double {
		if (legacyRate <= 0.0 || legacyMaxStamina <= 0) {
			return fallback
		}

		return legacyMaxStamina.toDouble() / legacyRate
	}

	private fun readDouble(jsonObject: JsonObject, key: String): Double? {
		return jsonObject.get(key)?.takeUnless { it.isJsonNull }?.asDouble
	}

	private fun readInt(jsonObject: JsonObject, key: String): Int? {
		return jsonObject.get(key)?.takeUnless { it.isJsonNull }?.asInt
	}

	private fun asObject(element: JsonElement?): JsonObject? {
		return if (element != null && element.isJsonObject) element.asJsonObject else null
	}

	data class ConfigData(
		var movement: MovementSettings = MovementSettings(),
		var stamina: StaminaSettings = StaminaSettings(),
		var food: FoodSettings = FoodSettings(),
		var penalty: PenaltySettings = PenaltySettings()
	) {
		fun sanitize(): ConfigData {
			val safeMovement = movement.sanitize()
			val safeStamina = stamina.sanitize()
			val safeFood = food.sanitize()
			val safePenalty = penalty.sanitize()
			return ConfigData(safeMovement, safeStamina, safeFood, safePenalty)
		}
	}

	data class MovementSettings(
		var sprintBlocksPerSecond: Double = StaminaSystem.HYTALE_TARGET_SPRINT_BLOCKS_PER_SECOND
	) {
		fun sanitize(): MovementSettings {
			return MovementSettings(sprintBlocksPerSecond.coerceAtLeast(0.1))
		}
	}

	data class StaminaSettings(
		var secondsUntilEmpty: Double = DEFAULT_STAMINA_DRAIN_DURATION_SECONDS,
		var secondsToFullRecharge: Double = StaminaSystem.MAX_STAMINA / DEFAULT_STAMINA_RECHARGE_PER_SECOND,
		var miningDepletesStamina: Boolean = false,
		var attackingDepletesStamina: Boolean = true,
		var attackStaminaCost: Int = DEFAULT_ATTACK_STAMINA_COST
	) {
		fun sanitize(): StaminaSettings {
			return StaminaSettings(
				secondsUntilEmpty.coerceAtLeast(0.1),
				secondsToFullRecharge.coerceAtLeast(0.1),
				miningDepletesStamina,
				attackingDepletesStamina,
				attackStaminaCost.coerceAtLeast(0)
			)
		}
	}

	data class FoodSettings(
		var hyFood: Boolean = true,
		var healingPercent: Double = DEFAULT_FOOD_HEAL_PERCENT,
		var healSpeedPercent: Double = DEFAULT_FOOD_HEAL_PERCENT
	) {
		fun sanitize(): FoodSettings {
			return FoodSettings(
				hyFood,
				healingPercent.coerceAtLeast(0.0),
				healSpeedPercent.coerceAtLeast(0.0)
			)
		}
	}

	data class PenaltySettings(
		var enabled: Boolean = false
	) {
		fun sanitize(): PenaltySettings {
			return PenaltySettings(enabled)
		}
	}

	private data class LoadedConfig(
		val config: ConfigData,
		val needsRewrite: Boolean
	)

	private data class ParsedStaminaSettings(
		val settings: StaminaSettings,
		val needsRewrite: Boolean
	)

	private data class ParsedFoodSettings(
		val settings: FoodSettings,
		val needsRewrite: Boolean
	)
}