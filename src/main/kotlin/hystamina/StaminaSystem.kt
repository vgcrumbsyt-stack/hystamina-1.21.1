package hystamina

import com.mojang.serialization.Codec
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.player.Player
import net.minecraft.world.food.FoodData
import net.minecraft.world.food.FoodProperties
import kotlin.math.floor
import kotlin.math.sqrt

object StaminaSystem {
	const val MAX_STAMINA = 100
	const val HYTALE_TARGET_SPRINT_BLOCKS_PER_SECOND = 7.0
	const val MINECRAFT_DEFAULT_SPRINT_BLOCKS_PER_SECOND = 5.612
	private const val TICKS_PER_SECOND = 20.0
	private const val HY_FOOD_BASE_HEAL_DURATION_SECONDS = 12.0
	private const val HY_FOOD_DURATION_REDUCTION_PER_NUTRITION = 0.35
	private const val HY_FOOD_DURATION_REDUCTION_PER_SATURATION_ROOT = 0.8
	private const val HY_FOOD_MAX_DURATION_REDUCTION_SECONDS = 4.0
	private const val MIN_HY_FOOD_HEAL_DURATION_SECONDS = 8.0
	private const val MIN_HY_FOOD_HEAL_PER_SECOND = 0.2
	private const val MAX_HY_FOOD_HEAL_PER_SECOND = 1.0
	private const val HUNGER_DRAIN_RATE_MULTIPLIER = 0.5

	private const val BLOCK_BREAK_COST = 3
	private const val REGEN_DELAY_TICKS = 24

	@JvmField
	val CURRENT_STAMINA = AttachmentRegistry.create(
		id("current_stamina")
	) { builder ->
		builder
			.initializer { getConfiguredMaxStamina() }
			.persistent(Codec.INT)
			.copyOnDeath()
			.syncWith(ByteBufCodecs.INT, AttachmentSyncPredicate.targetOnly())
	}

	private val EXHAUSTED_LOCKOUT = AttachmentRegistry.create(
		id("exhausted_lockout")
	) { builder ->
		builder
			.initializer { false }
			.persistent(Codec.BOOL)
			.copyOnDeath()
			.syncWith(ByteBufCodecs.BOOL, AttachmentSyncPredicate.targetOnly())
	}

	private val REGEN_DELAY = AttachmentRegistry.createDefaulted(id("regen_delay")) { 0 }
	private val SPRINT_DRAIN_BUFFER = AttachmentRegistry.createDefaulted(id("sprint_drain_buffer")) { 0.0 }
	private val HUNGER_DRAIN_BUFFER = AttachmentRegistry.createDefaulted(id("hunger_drain_buffer")) { 0.0 }
	private val REGEN_BUFFER = AttachmentRegistry.createDefaulted(id("regen_buffer")) { 0.0 }
	private val PENDING_FOOD_HEAL = AttachmentRegistry.create(
		id("pending_food_heal")
	) { builder ->
		builder
			.initializer { 0.0 }
			.persistent(Codec.DOUBLE)
	}
	private val PENDING_FOOD_HEAL_RATE_WEIGHT = AttachmentRegistry.create(
		id("pending_food_heal_rate_weight")
	) { builder ->
		builder
			.initializer { 0.0 }
			.persistent(Codec.DOUBLE)
	}

	fun initialize() {
		ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
			for (player in server.playerList.players) {
				tickPlayer(player)
			}
		})

		ServerPlayerEvents.COPY_FROM.register(ServerPlayerEvents.CopyFrom { _, newPlayer, alive ->
			if (!alive) {
				setCurrentStamina(newPlayer, getConfiguredMaxStamina())
				newPlayer.setAttached(REGEN_DELAY, 0)
				newPlayer.setAttached(SPRINT_DRAIN_BUFFER, 0.0)
				newPlayer.setAttached(HUNGER_DRAIN_BUFFER, 0.0)
				newPlayer.setAttached(REGEN_BUFFER, 0.0)
				clearPendingFoodHealing(newPlayer)
				newPlayer.setAttached(EXHAUSTED_LOCKOUT, false)
			}
		})

		AttackEntityCallback.EVENT.register(AttackEntityCallback { player, level, _, _, _ ->
			if (level.isClientSide) {
				return@AttackEntityCallback InteractionResult.PASS
			}

			if (!HyStaminaConfig.attackingDepletesStamina()) {
				return@AttackEntityCallback InteractionResult.PASS
			}

			if (trySpend(player, HyStaminaConfig.attackStaminaCost())) {
				InteractionResult.PASS
			} else {
				InteractionResult.FAIL
			}
		})

		PlayerBlockBreakEvents.BEFORE.register(PlayerBlockBreakEvents.Before { level, player, _, _, _ ->
			if (level.isClientSide) {
				return@Before true
			}

			if (!HyStaminaConfig.miningDepletesStamina()) {
				return@Before true
			}

			trySpend(player, BLOCK_BREAK_COST)
		})
	}

	@JvmStatic
	fun getCurrentStamina(player: Player): Int {
		return player.getAttachedOrCreate(CURRENT_STAMINA).coerceIn(0, getMaxStamina(player))
	}

	@JvmStatic
	fun getMaxStamina(player: Player): Int {
		return MAX_STAMINA
	}

	@JvmStatic
	fun getConfiguredMaxStamina(): Int {
		return MAX_STAMINA
	}

	@JvmStatic
	fun getConfiguredSprintSpeedModifier(): Double {
		return HyStaminaConfig.sprintSpeedModifier()
	}

	@JvmStatic
	fun isExhaustionPenaltyEnabled(): Boolean {
		return HyStaminaConfig.exhaustionPenaltyEnabled()
	}

	@JvmStatic
	fun canEat(player: Player, canAlwaysEat: Boolean): Boolean {
		return !player.isSpectator
	}

	@JvmStatic
	fun canStartSprinting(player: Player): Boolean {
		if (player.isCreative || player.isSpectator || player.abilities.mayfly) {
			return true
		}

		if (isExhaustionPenaltyEnabled() && isExhaustedLockout(player)) {
			return false
		}

		return getCurrentStamina(player) > 0
	}

	@JvmStatic
	fun canUseSprintMovement(player: Player): Boolean {
		if (player.abilities.flying || player.isPassenger || player.isFallFlying || player.isUsingItem) {
			return false
		}

		return !player.isInWaterOrBubble || player.isSwimming
	}

	@JvmStatic
	fun isExhaustedLockout(player: Player): Boolean {
		return player.getAttachedOrCreate(EXHAUSTED_LOCKOUT)
	}

	@JvmStatic
	fun isHungerDraining(player: Player): Boolean {
		return player.hasEffect(MobEffects.HUNGER)
	}

	@JvmStatic
	fun onFoodTick(foodData: FoodData, player: Player) {
		normalizeFoodData(foodData)

		if (player.isSprinting && !canStartSprinting(player)) {
			player.isSprinting = false
		}
	}

	@JvmStatic
	fun onPlayerEat(player: Player, foodProperties: FoodProperties) {
		onPlayerEat(player, foodProperties.nutrition(), foodProperties.saturation())
	}

	@JvmStatic
	fun getFoodRecoveryPreview(foodProperties: FoodProperties): FoodRecoveryPreview? {
		return getFoodRecoveryPreview(foodProperties.nutrition(), foodProperties.saturation().toDouble())
	}

	@JvmStatic
	fun getFoodRecoveryPreview(nutrition: Int, saturation: Double): FoodRecoveryPreview? {
		val totalHealing = calculateFoodHealing(nutrition)
		if (totalHealing <= 0.0) {
			return null
		}

		val durationSeconds = if (HyStaminaConfig.hyFoodEnabled()) {
			totalHealing / calculateHyFoodHealPerSecond(totalHealing, saturation)
		} else {
			0.0
		}

		return FoodRecoveryPreview(totalHealing, durationSeconds)
	}

	@JvmStatic
	fun onPlayerEat(player: Player, nutrition: Int, saturation: Float) {
		if (player.level().isClientSide || player.isSpectator) {
			return
		}

		val totalHealing = calculateFoodHealing(nutrition)

		if (HyStaminaConfig.hyFoodEnabled()) {
			queueFoodHealing(player, totalHealing, saturation.toDouble())
		} else {
			clearPendingFoodHealing(player)
			healFromFood(player, totalHealing.toFloat())
		}

		normalizeFoodData(player.foodData)
	}

	@JvmStatic
	fun normalizeFoodData(foodData: FoodData) {
		foodData.setFoodLevel(20)
		foodData.setSaturation(5.0f)
		foodData.setExhaustion(0.0f)
	}

	private fun tickPlayer(player: ServerPlayer) {
		if (player.isCreative || player.isSpectator) {
			setCurrentStamina(player, getConfiguredMaxStamina())
			player.setAttached(REGEN_DELAY, 0)
			player.setAttached(SPRINT_DRAIN_BUFFER, 0.0)
			player.setAttached(HUNGER_DRAIN_BUFFER, 0.0)
			player.setAttached(REGEN_BUFFER, 0.0)
			clearPendingFoodHealing(player)
			player.setAttached(EXHAUSTED_LOCKOUT, false)
			normalizeFoodData(player.foodData)
			return
		}

		if (!isExhaustionPenaltyEnabled() && player.getAttachedOrCreate(EXHAUSTED_LOCKOUT)) {
			player.setAttached(EXHAUSTED_LOCKOUT, false)
		}

		if (player.getAttachedOrCreate(CURRENT_STAMINA) != getCurrentStamina(player)) {
			setCurrentStamina(player, getCurrentStamina(player))
		}

		normalizeFoodData(player.foodData)
		handleSprintDrain(player)
		handleHungerDrain(player)
		handleRegeneration(player)
		handleHyFoodHealing(player)

		if (!canStartSprinting(player) && player.isSprinting) {
			player.isSprinting = false
		}
	}

	private fun handleSprintDrain(player: ServerPlayer) {
		if (!canStartSprinting(player)) {
			if (player.isSprinting) {
				player.isSprinting = false
			}
			return
		}

		if (!player.isSprinting) {
			return
		}

		if (!canUseSprintMovement(player)) {
			return
		}

		if (!consumeStaminaRate(player, HyStaminaConfig.sprintDrainPerSecond(), SPRINT_DRAIN_BUFFER)) {
			player.isSprinting = false
		}
	}

	private fun handleHungerDrain(player: ServerPlayer) {
		if (!isHungerDraining(player)) {
			player.setAttached(HUNGER_DRAIN_BUFFER, 0.0)
			return
		}

		consumeStaminaRate(
			player,
			HyStaminaConfig.sprintDrainPerSecond() * HUNGER_DRAIN_RATE_MULTIPLIER,
			HUNGER_DRAIN_BUFFER
		)
	}

	private fun handleRegeneration(player: ServerPlayer) {
		val current = getCurrentStamina(player)
		if (current >= getMaxStamina(player)) {
			player.setAttached(REGEN_DELAY, 0)
			player.setAttached(REGEN_BUFFER, 0.0)
			player.setAttached(EXHAUSTED_LOCKOUT, false)
			return
		}

		val regenDelay = player.getAttachedOrCreate(REGEN_DELAY)
		if (regenDelay > 0) {
			player.setAttached(REGEN_DELAY, regenDelay - 1)
			player.setAttached(REGEN_BUFFER, 0.0)
			return
		}

		if (player.isSprinting) {
			player.setAttached(REGEN_BUFFER, 0.0)
			return
		}

		restoreStaminaRate(player, HyStaminaConfig.rechargePerSecond(), REGEN_BUFFER)
	}

	private fun handleHyFoodHealing(player: ServerPlayer) {
		val pendingHealing = player.getAttachedOrCreate(PENDING_FOOD_HEAL)
		if (pendingHealing <= 0.0) {
			clearPendingFoodHealing(player)
			return
		}

		if (!HyStaminaConfig.hyFoodEnabled() || player.health >= player.maxHealth) {
			clearPendingFoodHealing(player)
			return
		}

		val rateWeight = player.getAttachedOrCreate(PENDING_FOOD_HEAL_RATE_WEIGHT)
		val healPerSecond = maxOf(MIN_HY_FOOD_HEAL_PER_SECOND, if (rateWeight > 0.0) rateWeight / pendingHealing else MIN_HY_FOOD_HEAL_PER_SECOND)
		val missingHealth = (player.maxHealth - player.health).toDouble()
		val healThisTick = minOf(pendingHealing, healPerSecond / TICKS_PER_SECOND, missingHealth)
		if (healThisTick <= 0.0) {
			return
		}

		player.heal(healThisTick.toFloat())

		val remainingHealing = (pendingHealing - healThisTick).coerceAtLeast(0.0)
		if (remainingHealing <= 0.0) {
			clearPendingFoodHealing(player)
			return
		}

		player.setAttached(PENDING_FOOD_HEAL, remainingHealing)
		player.setAttached(
			PENDING_FOOD_HEAL_RATE_WEIGHT,
			(rateWeight - healThisTick * healPerSecond).coerceAtLeast(0.0)
		)
	}

	private fun healFromFood(player: Player, amount: Float) {
		if (amount <= 0.0f) {
			return
		}

		player.heal(amount)
	}

	private fun queueFoodHealing(player: Player, totalHealing: Double, saturationValue: Double) {
		if (totalHealing <= 0.0) {
			return
		}

		val healPerSecond = calculateHyFoodHealPerSecond(totalHealing, saturationValue)
		player.setAttached(PENDING_FOOD_HEAL, player.getAttachedOrCreate(PENDING_FOOD_HEAL) + totalHealing)
		player.setAttached(
			PENDING_FOOD_HEAL_RATE_WEIGHT,
			player.getAttachedOrCreate(PENDING_FOOD_HEAL_RATE_WEIGHT) + totalHealing * healPerSecond
		)
	}

	private fun calculateFoodHealing(nutrition: Int): Double {
		return nutrition.coerceAtLeast(0).toDouble() * HyStaminaConfig.foodHealingMultiplier()
	}

	private fun calculateHyFoodHealPerSecond(totalHealing: Double, saturationValue: Double): Double {
		val effectiveHealing = totalHealing.coerceAtLeast(MIN_HY_FOOD_HEAL_PER_SECOND)
		val saturation = saturationValue.coerceAtLeast(0.0)
		val durationReduction = minOf(
			HY_FOOD_MAX_DURATION_REDUCTION_SECONDS,
			effectiveHealing * HY_FOOD_DURATION_REDUCTION_PER_NUTRITION +
				sqrt(saturation) * HY_FOOD_DURATION_REDUCTION_PER_SATURATION_ROOT * HyStaminaConfig.foodHealSpeedMultiplier()
		)
		val durationSeconds = maxOf(MIN_HY_FOOD_HEAL_DURATION_SECONDS, HY_FOOD_BASE_HEAL_DURATION_SECONDS - durationReduction)

		// Keep food recovery gradual: better food shortens the recovery window, but no food should approach potion-like burst healing.
		return (effectiveHealing / durationSeconds).coerceIn(MIN_HY_FOOD_HEAL_PER_SECOND, MAX_HY_FOOD_HEAL_PER_SECOND)
	}

	private fun clearPendingFoodHealing(player: Player) {
		player.setAttached(PENDING_FOOD_HEAL, 0.0)
		player.setAttached(PENDING_FOOD_HEAL_RATE_WEIGHT, 0.0)
	}

	private fun trySpend(player: Player, amount: Int): Boolean {
		if (amount <= 0 || player.isCreative || player.isSpectator) {
			return true
		}

		val current = getCurrentStamina(player)
		if (current < amount) {
			player.setAttached(REGEN_DELAY, REGEN_DELAY_TICKS)
			if (isExhaustionPenaltyEnabled() && current <= 0) {
				player.setAttached(EXHAUSTED_LOCKOUT, true)
			}
			return false
		}

		setCurrentStamina(player, current - amount)
		player.setAttached(REGEN_DELAY, REGEN_DELAY_TICKS)
		return true
	}

	private fun setCurrentStamina(player: Player, value: Int) {
		val clampedValue = value.coerceIn(0, getMaxStamina(player))
		player.setAttached(CURRENT_STAMINA, clampedValue)

		if (!isExhaustionPenaltyEnabled()) {
			player.setAttached(EXHAUSTED_LOCKOUT, false)
			return
		}

		if (clampedValue <= 0) {
			player.setAttached(EXHAUSTED_LOCKOUT, true)
		} else if (clampedValue >= getMaxStamina(player)) {
			player.setAttached(EXHAUSTED_LOCKOUT, false)
		}
	}

	private fun consumeStaminaRate(player: Player, staminaPerSecond: Double, bufferAttachment: net.fabricmc.fabric.api.attachment.v1.AttachmentType<Double>): Boolean {
		if (staminaPerSecond <= 0.0) {
			player.setAttached(bufferAttachment, 0.0)
			return true
		}

		var buffer = player.getAttachedOrCreate(bufferAttachment) + staminaPerSecond / TICKS_PER_SECOND
		val wholeStamina = floor(buffer).toInt()
		buffer -= wholeStamina.toDouble()
		player.setAttached(bufferAttachment, buffer)

		if (wholeStamina <= 0) {
			return true
		}

		if (!trySpend(player, wholeStamina)) {
			player.setAttached(bufferAttachment, 0.0)
			return false
		}

		return true
	}

	private fun restoreStaminaRate(player: Player, staminaPerSecond: Double, bufferAttachment: net.fabricmc.fabric.api.attachment.v1.AttachmentType<Double>) {
		if (staminaPerSecond <= 0.0) {
			player.setAttached(bufferAttachment, 0.0)
			return
		}

		var buffer = player.getAttachedOrCreate(bufferAttachment) + staminaPerSecond / TICKS_PER_SECOND
		val wholeStamina = floor(buffer).toInt()
		buffer -= wholeStamina.toDouble()
		player.setAttached(bufferAttachment, buffer)

		if (wholeStamina > 0) {
			setCurrentStamina(player, getCurrentStamina(player) + wholeStamina)
		}
	}

	private fun id(path: String): ResourceLocation {
		return ResourceLocation.fromNamespaceAndPath(HyStamina.MOD_ID, path)
	}
}