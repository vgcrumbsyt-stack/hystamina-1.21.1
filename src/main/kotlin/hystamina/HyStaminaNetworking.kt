package hystamina

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.DyeColor
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.entity.BannerPatternLayers
import net.minecraft.world.item.MapItem
import java.util.Locale
import java.util.Optional
import java.util.UUID

object HyStaminaNetworking {
	private const val COMPASS_DEATH_AUTO_CLEAR_DISTANCE_SQUARED = 16.0

	private val lastSentCompassSpawnTargets = mutableMapOf<UUID, CompassSpawnData>()
	private val lastSentCompassDeathTargets = mutableMapOf<UUID, CompassDeathData?>()
	private val lastSentCompassMapWaypoints = mutableMapOf<UUID, List<CompassMapWaypointData>>()
	private val lastSentCompassPartyMembers = mutableMapOf<UUID, List<CompassPartyMemberData>>()

	fun initialize() {
		PayloadTypeRegistry.playS2C().register(HyStaminaConfigPayload.TYPE, HyStaminaConfigPayload.CODEC)
		PayloadTypeRegistry.playS2C().register(HyStaminaCompassSpawnPayload.TYPE, HyStaminaCompassSpawnPayload.CODEC)
		PayloadTypeRegistry.playS2C().register(HyStaminaCompassDeathPayload.TYPE, HyStaminaCompassDeathPayload.CODEC)
		PayloadTypeRegistry.playS2C().register(HyStaminaCompassMapPayload.TYPE, HyStaminaCompassMapPayload.CODEC)
		PayloadTypeRegistry.playS2C().register(HyStaminaCompassPartyPayload.TYPE, HyStaminaCompassPartyPayload.CODEC)

		ServerPlayConnectionEvents.JOIN.register(ServerPlayConnectionEvents.Join { handler, _, _ ->
			if (ServerPlayNetworking.canSend(handler.player, HyStaminaConfigPayload.TYPE)) {
				ServerPlayNetworking.send(handler.player, HyStaminaConfigPayload.fromConfig(HyStaminaConfig.effectiveSnapshot()))
			}

			syncCompassSpawnTarget(handler.player, force = true)
			syncCompassDeathTarget(handler.player, force = true)
			syncCompassMapWaypoints(handler.player, force = true)
			syncCompassPartyMembers(handler.player, force = true)
		})

		ServerPlayConnectionEvents.DISCONNECT.register(ServerPlayConnectionEvents.Disconnect { handler, _ ->
			lastSentCompassSpawnTargets.remove(handler.player.uuid)
			lastSentCompassDeathTargets.remove(handler.player.uuid)
			lastSentCompassMapWaypoints.remove(handler.player.uuid)
			lastSentCompassPartyMembers.remove(handler.player.uuid)
		})

		ServerTickEvents.END_SERVER_TICK.register(ServerTickEvents.EndTick { server ->
			for (player in server.playerList.players) {
				syncCompassSpawnTarget(player)
				clearNearbyDeathMarker(player)
				syncCompassDeathTarget(player)
				syncCompassMapWaypoints(player)
				syncCompassPartyMembers(player)
			}
		})
	}

	fun refreshCompassDeathTarget(player: net.minecraft.server.level.ServerPlayer) {
		syncCompassDeathTarget(player, force = true)
	}

	fun refreshCompassMapWaypoints(player: net.minecraft.server.level.ServerPlayer) {
		syncCompassMapWaypoints(player, force = true)
	}

	private fun syncCompassSpawnTarget(player: net.minecraft.server.level.ServerPlayer, force: Boolean = false) {
		if (!ServerPlayNetworking.canSend(player, HyStaminaCompassSpawnPayload.TYPE)) {
			return
		}

		val target = getCompassSpawnTarget(player)
		if (!force && lastSentCompassSpawnTargets[player.uuid] == target) {
			return
		}

		lastSentCompassSpawnTargets[player.uuid] = target
		ServerPlayNetworking.send(player, HyStaminaCompassSpawnPayload.fromData(target))
	}

	private fun syncCompassDeathTarget(player: net.minecraft.server.level.ServerPlayer, force: Boolean = false) {
		if (!ServerPlayNetworking.canSend(player, HyStaminaCompassDeathPayload.TYPE)) {
			return
		}

		val target = getCompassDeathTarget(player)
		if (!force && lastSentCompassDeathTargets[player.uuid] == target) {
			return
		}

		lastSentCompassDeathTargets[player.uuid] = target
		ServerPlayNetworking.send(player, HyStaminaCompassDeathPayload.fromData(target))
	}

	private fun syncCompassMapWaypoints(player: net.minecraft.server.level.ServerPlayer, force: Boolean = false) {
		if (!ServerPlayNetworking.canSend(player, HyStaminaCompassMapPayload.TYPE)) {
			return
		}

		val waypoints = getCompassMapWaypoints(player)
		if (!force && lastSentCompassMapWaypoints[player.uuid] == waypoints) {
			return
		}

		lastSentCompassMapWaypoints[player.uuid] = waypoints
		ServerPlayNetworking.send(player, HyStaminaCompassMapPayload.fromData(waypoints))
	}

	private fun syncCompassPartyMembers(player: net.minecraft.server.level.ServerPlayer, force: Boolean = false) {
		if (!ServerPlayNetworking.canSend(player, HyStaminaCompassPartyPayload.TYPE)) {
			return
		}

		val members = HyStaminaPartySystem.getTrackedMembers(player).map { trackedMember ->
			CompassPartyMemberData(
				uuid = trackedMember.uuid,
				name = trackedMember.name,
				dimensionId = trackedMember.dimensionId,
				x = trackedMember.x,
				y = trackedMember.y,
				z = trackedMember.z
			)
		}
		if (!force && lastSentCompassPartyMembers[player.uuid] == members) {
			return
		}

		lastSentCompassPartyMembers[player.uuid] = members
		ServerPlayNetworking.send(player, HyStaminaCompassPartyPayload.fromData(members))
	}

	private fun getCompassSpawnTarget(player: net.minecraft.server.level.ServerPlayer): CompassSpawnData {
		val respawnPosition = player.respawnPosition
		if (respawnPosition != null) {
			return CompassSpawnData(player.respawnDimension, respawnPosition)
		}

		val overworld = player.server.overworld()
		return CompassSpawnData(overworld.dimension(), overworld.sharedSpawnPos)
	}

	private fun getCompassDeathTarget(player: net.minecraft.server.level.ServerPlayer): CompassDeathData? {
		val deathLocation = player.lastDeathLocation.orElse(null) ?: return null
		return CompassDeathData(deathLocation.dimension(), deathLocation.pos())
	}

	private fun clearNearbyDeathMarker(player: net.minecraft.server.level.ServerPlayer) {
		val deathLocation = player.lastDeathLocation.orElse(null) ?: return
		if (deathLocation.dimension() != player.level().dimension()) {
			return
		}

		val deathPos = deathLocation.pos()
		val distanceSquared = player.distanceToSqr(
			deathPos.x + 0.5,
			deathPos.y + 0.5,
			deathPos.z + 0.5
		)
		if (distanceSquared > COMPASS_DEATH_AUTO_CLEAR_DISTANCE_SQUARED) {
			return
		}

		player.setLastDeathLocation(Optional.empty())
	}

	private fun getCompassMapWaypoints(player: net.minecraft.server.level.ServerPlayer): List<CompassMapWaypointData> {
		val waypoints = linkedMapOf<String, CompassMapWaypointData>()
		for (waypoint in HyStaminaWaypointSystem.getCompassBannerWaypoints(player)) {
			putCompassWaypoint(waypoints, waypoint)
		}
		collectHeldMapWaypoints(player, player.mainHandItem, waypoints)
		collectHeldMapWaypoints(player, player.offhandItem, waypoints)
		return waypoints.values.sortedWith(
			compareBy<CompassMapWaypointData>({ it.dimensionId }, { it.x }, { it.z }, { it.decorationTypeId }, { it.name })
		)
	}

	private fun collectHeldMapWaypoints(
		player: net.minecraft.server.level.ServerPlayer,
		stack: net.minecraft.world.item.ItemStack,
		waypoints: MutableMap<String, CompassMapWaypointData>
	) {
		if (stack.item !is MapItem) {
			return
		}

		val mapData = MapItem.getSavedData(stack, player.serverLevel()) ?: return
		if (mapData.dimension != player.level().dimension()) {
			return
		}

		for (banner in mapData.getBanners()) {
			val name = banner.name().orElse(null)?.string ?: continue
			val decorationTypeId = BuiltInRegistries.MAP_DECORATION_TYPE.getKey(banner.getDecoration().value()).toString()
			val data = CompassMapWaypointData(
				dimensionId = mapData.dimension.location().toString(),
				x = banner.pos().x,
				z = banner.pos().z,
				decorationTypeId = decorationTypeId,
				name = name,
				bannerAppearance = HyStaminaWaypointSystem.getBannerAppearance(player.serverLevel(), banner.pos())
			)
			putCompassWaypoint(waypoints, data)
		}
	}

	private fun putCompassWaypoint(
		waypoints: MutableMap<String, CompassMapWaypointData>,
		waypoint: CompassMapWaypointData
	) {
		val key = compassWaypointKey(waypoint)
		val existingWaypoint = waypoints[key]
		if (existingWaypoint == null || (existingWaypoint.bannerAppearance == null && waypoint.bannerAppearance != null)) {
			waypoints[key] = waypoint
		}
	}

	private fun compassWaypointKey(waypoint: CompassMapWaypointData): String {
		return "${waypoint.dimensionId}|${waypoint.x}|${waypoint.z}|${waypoint.decorationTypeId}|${waypoint.name.lowercase(Locale.ROOT)}"
	}

	data class CompassSpawnData(
		val dimension: ResourceKey<Level>,
		val position: BlockPos
	)

	data class CompassDeathData(
		val dimension: ResourceKey<Level>,
		val position: BlockPos
	)

	data class CompassMapWaypointData(
		val dimensionId: String,
		val x: Int,
		val z: Int,
		val decorationTypeId: String,
		val name: String,
		val bannerAppearance: BannerAppearanceData? = null
	)

	data class BannerAppearanceData(
		val baseColor: DyeColor,
		val patternLayers: BannerPatternLayers
	)

	data class CompassPartyMemberData(
		val uuid: UUID,
		val name: String,
		val dimensionId: String,
		val x: Int,
		val y: Int,
		val z: Int
	)

	data class HyStaminaConfigPayload(
		val sprintBlocksPerSecond: Double,
		val secondsUntilEmpty: Double,
		val secondsToFullRecharge: Double,
		val miningDepletesStamina: Boolean,
		val attackingDepletesStamina: Boolean,
		val attackStaminaCost: Int,
		val hyFood: Boolean,
		val healingPercent: Double,
		val healSpeedPercent: Double,
		val exhaustionPenaltyEnabled: Boolean
	) : CustomPacketPayload {
		override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> {
			return TYPE
		}

		fun toConfigData(): HyStaminaConfig.ConfigData {
			return HyStaminaConfig.ConfigData(
				movement = HyStaminaConfig.MovementSettings(sprintBlocksPerSecond = sprintBlocksPerSecond),
				stamina = HyStaminaConfig.StaminaSettings(
					secondsUntilEmpty = secondsUntilEmpty,
					secondsToFullRecharge = secondsToFullRecharge,
					miningDepletesStamina = miningDepletesStamina,
					attackingDepletesStamina = attackingDepletesStamina,
					attackStaminaCost = attackStaminaCost
				),
				food = HyStaminaConfig.FoodSettings(
					hyFood = hyFood,
					healingPercent = healingPercent,
					healSpeedPercent = healSpeedPercent
				),
				penalty = HyStaminaConfig.PenaltySettings(enabled = exhaustionPenaltyEnabled)
			)
		}

		companion object {
			@JvmStatic
			val TYPE: CustomPacketPayload.Type<HyStaminaConfigPayload> =
				CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(HyStamina.MOD_ID, "config"))

			@JvmStatic
			val CODEC = object : net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, HyStaminaConfigPayload> {
				override fun encode(buffer: RegistryFriendlyByteBuf, value: HyStaminaConfigPayload) {
					buffer.writeDouble(value.sprintBlocksPerSecond)
					buffer.writeDouble(value.secondsUntilEmpty)
					buffer.writeDouble(value.secondsToFullRecharge)
					buffer.writeBoolean(value.miningDepletesStamina)
					buffer.writeBoolean(value.attackingDepletesStamina)
					buffer.writeInt(value.attackStaminaCost)
					buffer.writeBoolean(value.hyFood)
					buffer.writeDouble(value.healingPercent)
					buffer.writeDouble(value.healSpeedPercent)
					buffer.writeBoolean(value.exhaustionPenaltyEnabled)
				}

				override fun decode(buffer: RegistryFriendlyByteBuf): HyStaminaConfigPayload {
					return HyStaminaConfigPayload(
						sprintBlocksPerSecond = buffer.readDouble(),
						secondsUntilEmpty = buffer.readDouble(),
						secondsToFullRecharge = buffer.readDouble(),
						miningDepletesStamina = buffer.readBoolean(),
						attackingDepletesStamina = buffer.readBoolean(),
						attackStaminaCost = buffer.readInt(),
						hyFood = buffer.readBoolean(),
						healingPercent = buffer.readDouble(),
						healSpeedPercent = buffer.readDouble(),
						exhaustionPenaltyEnabled = buffer.readBoolean()
					)
				}
			}

			fun fromConfig(config: HyStaminaConfig.ConfigData): HyStaminaConfigPayload {
				return HyStaminaConfigPayload(
					sprintBlocksPerSecond = config.movement.sprintBlocksPerSecond,
					secondsUntilEmpty = config.stamina.secondsUntilEmpty,
					secondsToFullRecharge = config.stamina.secondsToFullRecharge,
					miningDepletesStamina = config.stamina.miningDepletesStamina,
					attackingDepletesStamina = config.stamina.attackingDepletesStamina,
					attackStaminaCost = config.stamina.attackStaminaCost,
					hyFood = config.food.hyFood,
					healingPercent = config.food.healingPercent,
					healSpeedPercent = config.food.healSpeedPercent,
					exhaustionPenaltyEnabled = config.penalty.enabled
				)
			}
		}
	}

	data class HyStaminaCompassSpawnPayload(
		val dimensionId: String,
		val x: Int,
		val y: Int,
		val z: Int
	) : CustomPacketPayload {
		override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> {
			return TYPE
		}

		fun toData(): CompassSpawnData {
			return CompassSpawnData(
				dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId)),
				position = BlockPos(x, y, z)
			)
		}

		companion object {
			@JvmStatic
			val TYPE: CustomPacketPayload.Type<HyStaminaCompassSpawnPayload> =
				CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(HyStamina.MOD_ID, "compass_spawn"))

			@JvmStatic
			val CODEC = object : net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, HyStaminaCompassSpawnPayload> {
				override fun encode(buffer: RegistryFriendlyByteBuf, value: HyStaminaCompassSpawnPayload) {
					buffer.writeUtf(value.dimensionId)
					buffer.writeInt(value.x)
					buffer.writeInt(value.y)
					buffer.writeInt(value.z)
				}

				override fun decode(buffer: RegistryFriendlyByteBuf): HyStaminaCompassSpawnPayload {
					return HyStaminaCompassSpawnPayload(
						dimensionId = buffer.readUtf(),
						x = buffer.readInt(),
						y = buffer.readInt(),
						z = buffer.readInt()
					)
				}
			}

			fun fromData(data: CompassSpawnData): HyStaminaCompassSpawnPayload {
				return HyStaminaCompassSpawnPayload(
					dimensionId = data.dimension.location().toString(),
					x = data.position.x,
					y = data.position.y,
					z = data.position.z
				)
			}
		}
	}

	data class HyStaminaCompassDeathPayload(
		val hasTarget: Boolean,
		val dimensionId: String,
		val x: Int,
		val y: Int,
		val z: Int
	) : CustomPacketPayload {
		override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> {
			return TYPE
		}

		fun toData(): CompassDeathData? {
			if (!hasTarget) {
				return null
			}

			return CompassDeathData(
				dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimensionId)),
				position = BlockPos(x, y, z)
			)
		}

		companion object {
			@JvmStatic
			val TYPE: CustomPacketPayload.Type<HyStaminaCompassDeathPayload> =
				CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(HyStamina.MOD_ID, "compass_death"))

			@JvmStatic
			val CODEC = object : net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, HyStaminaCompassDeathPayload> {
				override fun encode(buffer: RegistryFriendlyByteBuf, value: HyStaminaCompassDeathPayload) {
					buffer.writeBoolean(value.hasTarget)
					buffer.writeUtf(value.dimensionId)
					buffer.writeInt(value.x)
					buffer.writeInt(value.y)
					buffer.writeInt(value.z)
				}

				override fun decode(buffer: RegistryFriendlyByteBuf): HyStaminaCompassDeathPayload {
					return HyStaminaCompassDeathPayload(
						hasTarget = buffer.readBoolean(),
						dimensionId = buffer.readUtf(),
						x = buffer.readInt(),
						y = buffer.readInt(),
						z = buffer.readInt()
					)
				}
			}

			fun fromData(data: CompassDeathData?): HyStaminaCompassDeathPayload {
				return if (data == null) {
					HyStaminaCompassDeathPayload(
						hasTarget = false,
						dimensionId = Level.OVERWORLD.location().toString(),
						x = 0,
						y = 0,
						z = 0
					)
				} else {
					HyStaminaCompassDeathPayload(
						hasTarget = true,
						dimensionId = data.dimension.location().toString(),
						x = data.position.x,
						y = data.position.y,
						z = data.position.z
					)
				}
			}
		}
	}

	data class HyStaminaCompassMapPayload(
		val waypoints: List<CompassMapWaypointData>
	) : CustomPacketPayload {
		override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> {
			return TYPE
		}

		fun toData(): List<CompassMapWaypointData> {
			return waypoints
		}

		companion object {
			@JvmStatic
			val TYPE: CustomPacketPayload.Type<HyStaminaCompassMapPayload> =
				CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(HyStamina.MOD_ID, "compass_map"))

			@JvmStatic
			val CODEC = object : net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, HyStaminaCompassMapPayload> {
				override fun encode(buffer: RegistryFriendlyByteBuf, value: HyStaminaCompassMapPayload) {
					buffer.writeInt(value.waypoints.size)
					for (waypoint in value.waypoints) {
						buffer.writeUtf(waypoint.dimensionId)
						buffer.writeInt(waypoint.x)
						buffer.writeInt(waypoint.z)
						buffer.writeUtf(waypoint.decorationTypeId)
						buffer.writeUtf(waypoint.name)
						buffer.writeBoolean(waypoint.bannerAppearance != null)
						if (waypoint.bannerAppearance != null) {
							DyeColor.STREAM_CODEC.encode(buffer, waypoint.bannerAppearance.baseColor)
							BannerPatternLayers.STREAM_CODEC.encode(buffer, waypoint.bannerAppearance.patternLayers)
						}
					}
				}

				override fun decode(buffer: RegistryFriendlyByteBuf): HyStaminaCompassMapPayload {
					val waypointCount = buffer.readInt()
					val waypoints = ArrayList<CompassMapWaypointData>(waypointCount)
					repeat(waypointCount) {
						val dimensionId = buffer.readUtf()
						val x = buffer.readInt()
						val z = buffer.readInt()
						val decorationTypeId = buffer.readUtf()
						val name = buffer.readUtf()
						val bannerAppearance = if (buffer.readBoolean()) {
							BannerAppearanceData(
								baseColor = DyeColor.STREAM_CODEC.decode(buffer),
								patternLayers = BannerPatternLayers.STREAM_CODEC.decode(buffer)
							)
						} else {
							null
						}
						waypoints.add(
							CompassMapWaypointData(
								dimensionId = dimensionId,
								x = x,
								z = z,
								decorationTypeId = decorationTypeId,
								name = name,
								bannerAppearance = bannerAppearance
							)
						)
					}
					return HyStaminaCompassMapPayload(waypoints)
				}
			}

			fun fromData(data: List<CompassMapWaypointData>): HyStaminaCompassMapPayload {
				return HyStaminaCompassMapPayload(data)
			}
		}
	}

	data class HyStaminaCompassPartyPayload(
		val members: List<CompassPartyMemberData>
	) : CustomPacketPayload {
		override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> {
			return TYPE
		}

		fun toData(): List<CompassPartyMemberData> {
			return members
		}

		companion object {
			@JvmStatic
			val TYPE: CustomPacketPayload.Type<HyStaminaCompassPartyPayload> =
				CustomPacketPayload.Type(ResourceLocation.fromNamespaceAndPath(HyStamina.MOD_ID, "compass_party"))

			@JvmStatic
			val CODEC = object : net.minecraft.network.codec.StreamCodec<RegistryFriendlyByteBuf, HyStaminaCompassPartyPayload> {
				override fun encode(buffer: RegistryFriendlyByteBuf, value: HyStaminaCompassPartyPayload) {
					buffer.writeInt(value.members.size)
					for (member in value.members) {
						buffer.writeUtf(member.uuid.toString())
						buffer.writeUtf(member.name)
						buffer.writeUtf(member.dimensionId)
						buffer.writeInt(member.x)
						buffer.writeInt(member.y)
						buffer.writeInt(member.z)
					}
				}

				override fun decode(buffer: RegistryFriendlyByteBuf): HyStaminaCompassPartyPayload {
					val memberCount = buffer.readInt()
					val members = ArrayList<CompassPartyMemberData>(memberCount)
					repeat(memberCount) {
						members.add(
							CompassPartyMemberData(
								uuid = UUID.fromString(buffer.readUtf()),
								name = buffer.readUtf(),
								dimensionId = buffer.readUtf(),
								x = buffer.readInt(),
								y = buffer.readInt(),
								z = buffer.readInt()
							)
						)
					}
					return HyStaminaCompassPartyPayload(members)
				}
			}

			fun fromData(data: List<CompassPartyMemberData>): HyStaminaCompassPartyPayload {
				return HyStaminaCompassPartyPayload(data)
			}
		}
	}
}