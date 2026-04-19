package hystamina

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BannerBlockEntity
import net.minecraft.world.level.saveddata.maps.MapBanner
import net.minecraft.world.phys.BlockHitResult
import java.util.Locale
import java.util.UUID

object HyStaminaWaypointSystem {
	private val STORED_WAYPOINT_COMPARATOR = compareBy<BannerWaypoint>(
		{ it.name.lowercase(Locale.ROOT) },
		{ it.dimensionId },
		{ it.x },
		{ it.y },
		{ it.z }
	)

	@JvmField
	val BANNER_WAYPOINTS = AttachmentRegistry.create(
		id("banner_waypoints")
	) { builder ->
		builder
			.initializer { emptyList<BannerWaypoint>() }
			.persistent(BannerWaypoint.CODEC.listOf())
			.copyOnDeath()
	}

	private val pendingActions = mutableMapOf<UUID, PendingWaypointAction>()

	fun initialize() {
		CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
			registerCommands(dispatcher)
		})

		UseBlockCallback.EVENT.register(UseBlockCallback { player, level, hand, hitResult ->
			handleBlockUse(player, level, hand, hitResult)
		})

		ServerPlayConnectionEvents.DISCONNECT.register(ServerPlayConnectionEvents.Disconnect { handler, _ ->
			pendingActions.remove(handler.player.uuid)
		})
	}

	fun getCompassBannerWaypoints(player: ServerPlayer): List<HyStaminaNetworking.CompassMapWaypointData> {
		return getValidatedWaypoints(player)
			.map { validatedWaypoint -> validatedWaypoint.resolved.toCompassWaypointData() }
			.sortedWith(
				compareBy<HyStaminaNetworking.CompassMapWaypointData>(
					{ it.dimensionId },
					{ it.x },
					{ it.z },
					{ it.decorationTypeId },
					{ it.name }
				)
			)
	}

	fun getBannerAppearance(level: Level, position: BlockPos): HyStaminaNetworking.BannerAppearanceData? {
		val bannerEntity = level.getBlockEntity(position) as? BannerBlockEntity ?: return null
		val patternLayers = bannerEntity.patterns
		if (patternLayers.layers().isEmpty()) {
			return null
		}

		return HyStaminaNetworking.BannerAppearanceData(
			baseColor = bannerEntity.baseColor,
			patternLayers = patternLayers
		)
	}

	private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			Commands.literal("waypoint")
				.executes { context ->
					showWaypointUsage(context.source.playerOrException)
				}
				.then(
					Commands.literal("remove")
						.then(
							Commands.argument("bannername", StringArgumentType.greedyString())
								.suggests { context, builder ->
									SharedSuggestionProvider.suggest(getStoredWaypointNames(context.source.playerOrException), builder)
								}
								.executes { context ->
									promptRemoveByName(
										context.source.playerOrException,
										StringArgumentType.getString(context, "bannername")
									)
								}
						)
				)
				.then(
					Commands.literal("confirm")
						.executes { context ->
							confirmPendingAction(context.source.playerOrException)
						}
				)
				.then(
					Commands.literal("cancel")
						.executes { context ->
							cancelPendingAction(context.source.playerOrException)
						}
				)
		)
	}

	private fun showWaypointUsage(player: ServerPlayer): Int {
		val waypoints = getStoredWaypoints(player)
		if (waypoints.isEmpty()) {
			player.sendSystemMessage(Component.literal("You do not have any saved banner waypoints."))
		} else {
			val names = waypoints.joinToString(", ") { waypoint -> waypoint.name }
			player.sendSystemMessage(Component.literal("Banner waypoints: $names"))
		}

		player.sendSystemMessage(Component.literal("Use /waypoint remove <banner name> to remove a saved waypoint."))
		return 1
	}

	private fun handleBlockUse(
		player: Player,
		level: Level,
		hand: InteractionHand,
		hitResult: BlockHitResult
	): InteractionResult {
		if (hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown || level.isClientSide) {
			return InteractionResult.PASS
		}

		val serverPlayer = player as? ServerPlayer ?: return InteractionResult.PASS
		val bannerEntity = level.getBlockEntity(hitResult.blockPos) as? BannerBlockEntity ?: return InteractionResult.PASS
		val resolvedBanner = resolveNamedBanner(level, hitResult.blockPos)

		if (resolvedBanner == null) {
			if (bannerEntity.customName == null) {
				serverPlayer.sendSystemMessage(Component.literal("Only named banners can be saved as compass waypoints."))
				return InteractionResult.SUCCESS
			}

			serverPlayer.sendSystemMessage(Component.literal("That banner cannot be used as a waypoint right now."))
			return InteractionResult.SUCCESS
		}

		val existingWaypoint = findWaypointByPosition(serverPlayer, resolvedBanner.dimensionId, resolvedBanner.position())
		if (existingWaypoint != null) {
			pendingActions[serverPlayer.uuid] = PendingWaypointAction(PendingWaypointActionType.REMOVE, existingWaypoint)
			sendConfirmationPrompt(
				serverPlayer,
				Component.literal("Remove waypoint \"${existingWaypoint.name}\"?"),
				"[Remove]",
				ChatFormatting.RED
			)
			return InteractionResult.SUCCESS
		}

		val conflictingWaypoint = findWaypointByName(serverPlayer, resolvedBanner.name)
		if (conflictingWaypoint != null) {
			serverPlayer.sendSystemMessage(
				Component.literal("You already have a saved waypoint named \"${conflictingWaypoint.name}\". Remove it first or rename the banner.")
			)
			return InteractionResult.SUCCESS
		}

		pendingActions[serverPlayer.uuid] = PendingWaypointAction(
			PendingWaypointActionType.ADD,
			BannerWaypoint(
				dimensionId = resolvedBanner.dimensionId,
				x = resolvedBanner.x,
				y = resolvedBanner.y,
				z = resolvedBanner.z,
				name = resolvedBanner.name
			)
		)
		sendConfirmationPrompt(
			serverPlayer,
			Component.literal("Add waypoint for \"${resolvedBanner.name}\"?"),
			"[Add]",
			ChatFormatting.GREEN
		)
		return InteractionResult.SUCCESS
	}

	private fun promptRemoveByName(player: ServerPlayer, rawName: String): Int {
		val bannerName = rawName.trim()
		if (bannerName.isEmpty()) {
			player.sendSystemMessage(Component.literal("Provide the banner waypoint name to remove."))
			return 0
		}

		val waypoint = findWaypointByName(player, bannerName)
		if (waypoint == null) {
			player.sendSystemMessage(Component.literal("No saved banner waypoint named \"$bannerName\" was found."))
			return 0
		}

		pendingActions[player.uuid] = PendingWaypointAction(PendingWaypointActionType.REMOVE, waypoint)
		sendConfirmationPrompt(
			player,
			Component.literal("Remove waypoint \"${waypoint.name}\"?"),
			"[Remove]",
			ChatFormatting.RED
		)
		return 1
	}

	private fun confirmPendingAction(player: ServerPlayer): Int {
		val pendingAction = pendingActions.remove(player.uuid)
		if (pendingAction == null) {
			player.sendSystemMessage(Component.literal("You do not have a pending waypoint action to confirm."))
			return 0
		}

		return when (pendingAction.type) {
			PendingWaypointActionType.ADD -> confirmAddWaypoint(player, pendingAction.waypoint)
			PendingWaypointActionType.REMOVE -> confirmRemoveWaypoint(player, pendingAction.waypoint)
		}
	}

	private fun cancelPendingAction(player: ServerPlayer): Int {
		if (pendingActions.remove(player.uuid) == null) {
			player.sendSystemMessage(Component.literal("You do not have a pending waypoint action to cancel."))
			return 0
		}

		player.sendSystemMessage(Component.literal("Canceled the pending waypoint action."))
		return 1
	}

	private fun confirmAddWaypoint(player: ServerPlayer, waypoint: BannerWaypoint): Int {
		val resolvedBanner = resolveNamedBanner(player.server, waypoint.dimensionId, waypoint.position())
		if (resolvedBanner == null || resolvedBanner.name != waypoint.name) {
			player.sendSystemMessage(Component.literal("That banner is no longer available as a named waypoint."))
			return 0
		}

		val existingWaypoint = findWaypointByPosition(player, waypoint.dimensionId, waypoint.position())
		if (existingWaypoint != null) {
			player.sendSystemMessage(Component.literal("That banner is already saved as a waypoint."))
			return 0
		}

		val conflictingWaypoint = findWaypointByName(player, waypoint.name)
		if (conflictingWaypoint != null) {
			player.sendSystemMessage(
				Component.literal("You already have a saved waypoint named \"${conflictingWaypoint.name}\". Remove it first or rename the banner.")
			)
			return 0
		}

		val updatedWaypoints = (getStoredWaypoints(player) + waypoint).sortedWith(STORED_WAYPOINT_COMPARATOR)
		player.setAttached(BANNER_WAYPOINTS, updatedWaypoints)
		HyStaminaNetworking.refreshCompassMapWaypoints(player)
		player.sendSystemMessage(Component.literal("Saved waypoint \"${waypoint.name}\"."))
		return 1
	}

	private fun confirmRemoveWaypoint(player: ServerPlayer, waypoint: BannerWaypoint): Int {
		val storedWaypoints = getStoredWaypoints(player)
		val updatedWaypoints = storedWaypoints.filterNot { storedWaypoint -> storedWaypoint.matches(waypoint) }
		if (updatedWaypoints.size == storedWaypoints.size) {
			player.sendSystemMessage(Component.literal("That banner waypoint is already gone."))
			return 0
		}

		player.setAttached(BANNER_WAYPOINTS, updatedWaypoints)
		HyStaminaNetworking.refreshCompassMapWaypoints(player)
		player.sendSystemMessage(Component.literal("Removed waypoint \"${waypoint.name}\"."))
		return 1
	}

	private fun sendConfirmationPrompt(
		player: ServerPlayer,
		prompt: Component,
		confirmLabel: String,
		confirmColor: ChatFormatting
	) {
		val message = Component.empty()
			.append(prompt)
			.append(Component.literal(" "))
			.append(actionButton(confirmLabel, confirmColor, "/waypoint confirm"))
			.append(Component.literal(" "))
			.append(actionButton("[Cancel]", ChatFormatting.GRAY, "/waypoint cancel"))
		player.sendSystemMessage(message)
	}

	private fun actionButton(label: String, color: ChatFormatting, command: String): Component {
		return Component.literal(label).withStyle { style ->
			style
				.withColor(color)
				.withBold(true)
				.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
		}
	}

	private fun getStoredWaypointNames(player: ServerPlayer): List<String> {
		return getStoredWaypoints(player).map(BannerWaypoint::name)
	}

	private fun getStoredWaypoints(player: ServerPlayer): List<BannerWaypoint> {
		return getValidatedWaypoints(player).map(ValidatedWaypoint::stored)
	}

	private fun findWaypointByName(player: ServerPlayer, name: String): BannerWaypoint? {
		return getStoredWaypoints(player).firstOrNull { waypoint ->
			waypoint.name.equals(name, ignoreCase = true)
		}
	}

	private fun findWaypointByPosition(player: ServerPlayer, dimensionId: String, position: BlockPos): BannerWaypoint? {
		return getStoredWaypoints(player).firstOrNull { waypoint ->
			waypoint.dimensionId == dimensionId && waypoint.position() == position
		}
	}

	private fun getValidatedWaypoints(player: ServerPlayer): List<ValidatedWaypoint> {
		val storedWaypoints = player.getAttachedOrCreate(BANNER_WAYPOINTS)
		if (storedWaypoints.isEmpty()) {
			return emptyList()
		}

		val validatedWaypoints = ArrayList<ValidatedWaypoint>(storedWaypoints.size)
		val cleanedWaypoints = ArrayList<BannerWaypoint>(storedWaypoints.size)
		for (waypoint in storedWaypoints) {
			val resolvedBanner = resolveNamedBanner(player.server, waypoint.dimensionId, waypoint.position()) ?: continue
			if (resolvedBanner.name != waypoint.name) {
				continue
			}

			validatedWaypoints.add(ValidatedWaypoint(waypoint, resolvedBanner))
			cleanedWaypoints.add(waypoint)
		}

		val normalizedWaypoints = cleanedWaypoints.sortedWith(STORED_WAYPOINT_COMPARATOR)
		if (normalizedWaypoints != storedWaypoints) {
			player.setAttached(BANNER_WAYPOINTS, normalizedWaypoints)
		}

		return validatedWaypoints.sortedWith(compareBy<ValidatedWaypoint>({ it.stored.name.lowercase(Locale.ROOT) }, { it.stored.dimensionId }, { it.stored.x }, { it.stored.y }, { it.stored.z }))
	}

	private fun resolveNamedBanner(server: MinecraftServer, dimensionId: String, position: BlockPos): ResolvedBannerWaypoint? {
		val levelKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId))
		val level = server.getLevel(levelKey) ?: return null
		return resolveNamedBanner(level, position)
	}

	private fun resolveNamedBanner(level: Level, position: BlockPos): ResolvedBannerWaypoint? {
		val mapBanner = MapBanner.fromWorld(level, position) ?: return null
		val name = mapBanner.name().orElse(null)?.string?.trim().orEmpty()
		if (name.isEmpty()) {
			return null
		}

		val decorationTypeId = BuiltInRegistries.MAP_DECORATION_TYPE.getKey(mapBanner.getDecoration().value()).toString()
		return ResolvedBannerWaypoint(
			dimensionId = level.dimension().location().toString(),
			x = position.x,
			y = position.y,
			z = position.z,
			name = name,
			decorationTypeId = decorationTypeId,
			bannerAppearance = getBannerAppearance(level, position)
		)
	}

	private fun id(path: String): ResourceLocation {
		return ResourceLocation.fromNamespaceAndPath(HyStamina.MOD_ID, path)
	}

	data class BannerWaypoint(
		val dimensionId: String,
		val x: Int,
		val y: Int,
		val z: Int,
		val name: String
	) {
		fun position(): BlockPos {
			return BlockPos(x, y, z)
		}

		fun matches(other: BannerWaypoint): Boolean {
			return dimensionId == other.dimensionId && x == other.x && y == other.y && z == other.z && name == other.name
		}

		companion object {
			val CODEC: Codec<BannerWaypoint> = RecordCodecBuilder.create { instance ->
				instance.group(
					Codec.STRING.fieldOf("dimensionId").forGetter(BannerWaypoint::dimensionId),
					Codec.INT.fieldOf("x").forGetter(BannerWaypoint::x),
					Codec.INT.fieldOf("y").forGetter(BannerWaypoint::y),
					Codec.INT.fieldOf("z").forGetter(BannerWaypoint::z),
					Codec.STRING.fieldOf("name").forGetter(BannerWaypoint::name)
				).apply(instance, ::BannerWaypoint)
			}
		}
	}

	private data class ValidatedWaypoint(
		val stored: BannerWaypoint,
		val resolved: ResolvedBannerWaypoint
	)

	private data class ResolvedBannerWaypoint(
		val dimensionId: String,
		val x: Int,
		val y: Int,
		val z: Int,
		val name: String,
		val decorationTypeId: String,
		val bannerAppearance: HyStaminaNetworking.BannerAppearanceData?
	) {
		fun position(): BlockPos {
			return BlockPos(x, y, z)
		}

		fun toCompassWaypointData(): HyStaminaNetworking.CompassMapWaypointData {
			return HyStaminaNetworking.CompassMapWaypointData(
				dimensionId = dimensionId,
				x = x,
				z = z,
				decorationTypeId = decorationTypeId,
				name = name,
				bannerAppearance = bannerAppearance
			)
		}
	}

	private data class PendingWaypointAction(
		val type: PendingWaypointActionType,
		val waypoint: BannerWaypoint
	)

	private enum class PendingWaypointActionType {
		ADD,
		REMOVE
	}
}