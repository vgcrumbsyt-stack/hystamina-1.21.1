
package hystamina.client

import com.mojang.blaze3d.systems.RenderSystem
import hystamina.HyStaminaCompatibility
import hystamina.HyStaminaConfig
import hystamina.HyStaminaNetworking
import hystamina.HyStamina
import hystamina.StaminaSystem
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.renderer.Sheets
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.Mth
import net.minecraft.world.item.MapItem
import org.lwjgl.glfw.GLFW
import java.util.Locale
import java.util.Optional
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

object HyStaminaClient : ClientModInitializer {
	private const val WARNING_STAMINA_THRESHOLD = 0.3f
	private const val WARNING_FLASH_INTERVAL = 6
	private var sprintReleaseRequired = false
	private var compassSpawnTarget: HyStaminaNetworking.CompassSpawnData? = null
	private var compassDeathTarget: HyStaminaNetworking.CompassDeathData? = null
	private var compassMapWaypoints: List<HyStaminaNetworking.CompassMapWaypointData>? = null
	private var compassPartyMembers: List<HyStaminaNetworking.CompassPartyMemberData>? = null
	private const val COMPASS_WIDTH = 208
	private const val COMPASS_HEIGHT = 16
	private const val COMPASS_TOP_Y = 5
	private const val COMPASS_VISIBLE_DEGREES = 120f
	private const val COMPASS_POINTER_WIDTH = 12
	private const val COMPASS_POINTER_HEIGHT = 8
	private const val COMPASS_ORNAMENT_SIZE = 12
	private const val COMPASS_TICK_STEP_DEGREES = 15
	private const val COMPASS_TICK_MAJOR_STEP_DEGREES = 45
	private const val COMPASS_TICK_MINOR_TOP = 8
	private const val COMPASS_TICK_MAJOR_TOP = 6
	private const val COMPASS_TICK_BOTTOM = 11
	private const val COMPASS_LABEL_Y_OFFSET = 3
	private const val COMPASS_LABEL_COLOR = 0xFFF4F8FF.toInt()
	private const val COMPASS_TICK_COLOR = 0xB5E5EEF8.toInt()
	private const val COMPASS_SPAWN_TEXTURE_WIDTH = 25
	private const val COMPASS_SPAWN_TEXTURE_HEIGHT = 20
	private const val COMPASS_SPAWN_RENDER_WIDTH = 13
	private const val COMPASS_SPAWN_RENDER_HEIGHT = 10
	private const val COMPASS_SPAWN_Y_OFFSET = -2
	private const val COMPASS_DEATH_TEXTURE_SIZE = 16
	private const val COMPASS_DEATH_RENDER_SIZE = 10
	private const val COMPASS_DEATH_Y_OFFSET = -2
	private const val COMPASS_DEATH_LABEL_TEXT = "Last Death"
	private const val COMPASS_DEATH_LABEL_Y_GAP = 1
	private const val COMPASS_DEATH_LABEL_COLOR = 0xFFFFD2D2.toInt()
	private const val COMPASS_DEATH_DISTANCE_Y_GAP = 1
	private const val COMPASS_DEATH_DISTANCE_SHOW_ANGLE = 8f
	private const val COMPASS_DEATH_DISTANCE_COLOR = 0xFFFF9B9B.toInt()
	private const val COMPASS_SPAWN_LABEL_TEXT = "Spawn"
	private const val COMPASS_SPAWN_LABEL_Y_GAP = 1
	private const val COMPASS_SPAWN_LABEL_COLOR = 0xFFF4F8FF.toInt()
	private const val COMPASS_SPAWN_DISTANCE_Y_GAP = 1
	private const val COMPASS_SPAWN_DISTANCE_SHOW_ANGLE = 8f
	private const val COMPASS_SPAWN_DISTANCE_COLOR = 0xFFF6E7B2.toInt()
	private const val COMPASS_MAP_MARKER_SIZE = 10
	private const val COMPASS_PATTERNED_BANNER_WIDTH = 6
	private const val COMPASS_PATTERNED_BANNER_HEIGHT = 12
	private const val BANNER_PATTERN_FACE_U = 1
	private const val BANNER_PATTERN_FACE_V = 1
	private const val BANNER_PATTERN_FACE_WIDTH = 20
	private const val BANNER_PATTERN_FACE_HEIGHT = 40
	private const val COMPASS_MAP_MARKER_Y_OFFSET = -2
	private const val COMPASS_MAP_INFO_SHOW_ANGLE = 10f
	private const val COMPASS_MAP_INFO_TOP_OFFSET = COMPASS_HEIGHT + 6
	private const val COMPASS_MAP_INFO_LINE_GAP = 2
	private const val COMPASS_MAP_INFO_MAX_LINES = 3
	private const val COMPASS_MAP_INFO_COLOR = 0xFFE9F3FF.toInt()
	private const val COMPASS_PARTY_HEAD_SIZE = 10
	private const val COMPASS_PARTY_HEAD_Y_OFFSET = -2
	private const val COMPASS_PARTY_NAME_Y_GAP = 1
	private const val COMPASS_PARTY_DISTANCE_Y_GAP = 1
	private const val COMPASS_PARTY_NAME_COLOR = 0xFFF4F8FF.toInt()
	private const val COMPASS_PARTY_DISTANCE_COLOR = 0xFFF6E7B2.toInt()
	private const val COMPASS_EDGE_FADE_START = 0.7f
	private const val BAR_OUTWARD_SHIFT = 3
	private const val BAR_WIDTH = 88
	private const val BAR_HEIGHT = 12
	private const val BAR_FRAME_WIDTH = 88
	private const val BAR_FRAME_HEIGHT = 12
	private const val EXPERIENCE_WIDGET_WIDTH = 24
	private const val EXPERIENCE_WIDGET_HEIGHT = 13
	private const val EXPERIENCE_TEXT_COLOR = 0x80FF20
	private const val VANILLA_HEALTH_ROW_TOP_OFFSET = 39
	private const val VANILLA_EXPERIENCE_BAR_TOP_OFFSET = 29
	private const val BAR_TO_EXPERIENCE_GAP = 1
	private const val HEALTH_BAR_START_X_OFFSET = -91
	private const val STAMINA_BAR_START_X_OFFSET = 3
	private const val EXPERIENCE_WIDGET_Y_OFFSET = 0
	private val HEALTH_BAR_FRAME_TEXTURE = texture("gui/health_bar_frame")
	private val HEALTH_BAR_FILL_TEXTURE = texture("gui/health_bar_fill")
	private val COMPASS_BAR_TEXTURE = texture("gui/compass_bar")
	private val COMPASS_DEATH_TEXTURE = texture("gui/compass_death")
	private val COMPASS_SPAWN_TEXTURE = texture("gui/compass_spawn")
	private val COMPASS_POINTER_TEXTURE = texture("gui/compass_pointer")
	private val COMPASS_ORNAMENT_TEXTURE = texture("gui/compass_ornament")
	private val EXPERIENCE_WIDGET_TEXTURE = texture("gui/exp_widget")
	private val STAMINA_BAR_FRAME_TEXTURE = texture("gui/stamina_bar_frame")
	private val STAMINA_BAR_FRAME_WARN_TEXTURE = texture("gui/stamina_bar_frame_warn")
	private val STAMINA_BAR_FILL_TEXTURE = texture("gui/stamina_bar_fill")
	private val STAMINA_BAR_FILL_HUNGER_TEXTURE = texture("gui/stamina_bar_fill_nausia")
	private val COMPASS_CARDINALS = listOf(
		CompassLabel(0, "N"),
		CompassLabel(90, "E"),
		CompassLabel(180, "S"),
		CompassLabel(270, "W")
	)

	override fun onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(HyStaminaNetworking.HyStaminaConfigPayload.TYPE) { payload, context ->
			context.client().execute {
				HyStaminaConfig.applyServerOverride(payload.toConfigData())
				HyStaminaCompatibility.setClientServerSupported(true)
			}
		}

		ClientPlayNetworking.registerGlobalReceiver(HyStaminaNetworking.HyStaminaCompassSpawnPayload.TYPE) { payload, context ->
			context.client().execute {
				compassSpawnTarget = payload.toData()
			}
		}

		ClientPlayNetworking.registerGlobalReceiver(HyStaminaNetworking.HyStaminaCompassDeathPayload.TYPE) { payload, context ->
			context.client().execute {
				compassDeathTarget = payload.toData()
			}
		}

		ClientPlayNetworking.registerGlobalReceiver(HyStaminaNetworking.HyStaminaCompassMapPayload.TYPE) { payload, context ->
			context.client().execute {
				compassMapWaypoints = payload.toData()
			}
		}

		ClientPlayNetworking.registerGlobalReceiver(HyStaminaNetworking.HyStaminaCompassPartyPayload.TYPE) { payload, context ->
			context.client().execute {
				compassPartyMembers = payload.toData()
			}
		}

		ClientPlayConnectionEvents.JOIN.register(ClientPlayConnectionEvents.Join { _, _, client ->
			HyStaminaConfig.clearServerOverride()
			HyStaminaCompatibility.setClientServerSupported(client.isLocalServer || client.hasSingleplayerServer())
			compassSpawnTarget = null
			compassDeathTarget = null
			compassMapWaypoints = null
			compassPartyMembers = null
			sprintReleaseRequired = false
		})

		ClientPlayConnectionEvents.DISCONNECT.register(ClientPlayConnectionEvents.Disconnect { _, client ->
			HyStaminaConfig.clearServerOverride()
			HyStaminaCompatibility.setClientServerSupported(true)
			compassSpawnTarget = null
			compassDeathTarget = null
			compassMapWaypoints = null
			compassPartyMembers = null
			sprintReleaseRequired = false
		})

		HudRenderCallback.EVENT.register(HudRenderCallback { drawContext, _ ->
			renderCompassHud(drawContext)
			renderHealthHud(drawContext)
			renderStaminaHud(drawContext)
			renderExperienceWidget(drawContext)
		})

		ItemTooltipCallback.EVENT.register(ItemTooltipCallback { stack, _, _, lines ->
			appendFoodRecoveryTooltip(stack, lines)
		})

		ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client ->
			if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
				sprintReleaseRequired = false
				return@EndTick
			}

			val player = client.player ?: run {
				sprintReleaseRequired = false
				return@EndTick
			}
			val sprintKeyDown = client.options.keySprint.isDown
			if (!sprintKeyDown) {
				sprintReleaseRequired = false
			}

			if (sprintKeyDown && !StaminaSystem.canStartSprinting(player)) {
				sprintReleaseRequired = true
			}

			if ((sprintReleaseRequired || !StaminaSystem.canStartSprinting(player)) && player.isSprinting) {
				player.isSprinting = false
			}
		})
	}

	@JvmStatic
	fun canClientStartSprinting(player: net.minecraft.world.entity.player.Player): Boolean {
		return StaminaSystem.canStartSprinting(player) && !sprintReleaseRequired
	}

	private fun appendFoodRecoveryTooltip(stack: net.minecraft.world.item.ItemStack, lines: MutableList<Component>) {
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return
		}

		val client = Minecraft.getInstance()
		if (client.screen !is AbstractContainerScreen<*>) {
			return
		}

		if (GLFW.glfwGetKey(client.window.window, GLFW.GLFW_KEY_LEFT_SHIFT) != GLFW.GLFW_PRESS) {
			return
		}

		val foodProperties = stack.get(DataComponents.FOOD) ?: return
		val preview = StaminaSystem.getFoodRecoveryPreview(foodProperties) ?: return
		lines.add(
			Component.literal("${formatTooltipValue(preview.healthPoints)}HP over ${formatTooltipValue(preview.durationSeconds)}s")
				.withStyle(ChatFormatting.GREEN)
		)
	}

	private fun formatTooltipValue(value: Double): String {
		return String.format(Locale.ROOT, "%.2f", value)
			.trimEnd('0')
			.trimEnd('.')
	}

	private fun renderCompassHud(drawContext: net.minecraft.client.gui.GuiGraphics) {
		val client = Minecraft.getInstance()
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return
		}

		val player = client.player ?: return
		if (!shouldRenderCompassHud(client, player)) {
			return
		}

		val centerX = client.window.guiScaledWidth / 2
		val startX = centerX - COMPASS_WIDTH / 2
		val startY = COMPASS_TOP_Y
		val playerYaw = client.gameRenderer.mainCamera.yRot

		drawContext.flush()
		RenderSystem.enableBlend()
		RenderSystem.defaultBlendFunc()

		try {
			drawContext.blit(
				COMPASS_BAR_TEXTURE,
				startX,
				startY,
				0f,
				0f,
				COMPASS_WIDTH,
				COMPASS_HEIGHT,
				COMPASS_WIDTH,
				COMPASS_HEIGHT
			)

			renderCompassTicks(drawContext, centerX, startY, playerYaw)
			renderCompassLabels(drawContext, client, centerX, startY, playerYaw)
			drawContext.flush()
			drawContext.pose().pushPose()
			drawContext.pose().translate(0f, 0f, 100f)
			renderCompassMapMarkers(drawContext, client, centerX, startY, player, playerYaw)
			renderCompassSpawnMarker(drawContext, client, centerX, startY, player, playerYaw)
			renderCompassDeathMarker(drawContext, client, centerX, startY, player, playerYaw)
			renderCompassPartyMarkers(drawContext, client, centerX, startY, player, playerYaw)
			drawContext.pose().popPose()
			drawContext.flush()

			drawContext.blit(
				COMPASS_POINTER_TEXTURE,
				centerX - COMPASS_POINTER_WIDTH / 2,
				startY + COMPASS_HEIGHT - COMPASS_POINTER_HEIGHT + 1,
				0f,
				0f,
				COMPASS_POINTER_WIDTH,
				COMPASS_POINTER_HEIGHT,
				COMPASS_POINTER_WIDTH,
				COMPASS_POINTER_HEIGHT
			)

			drawContext.setColor(1f, 1f, 1f, compassAlphaForX(centerX, startX + COMPASS_WIDTH - COMPASS_ORNAMENT_SIZE / 2))
			drawContext.blit(
				COMPASS_ORNAMENT_TEXTURE,
				startX + COMPASS_WIDTH - COMPASS_ORNAMENT_SIZE + 2,
				startY + 1,
				0f,
				0f,
				COMPASS_ORNAMENT_SIZE,
				COMPASS_ORNAMENT_SIZE,
				COMPASS_ORNAMENT_SIZE,
				COMPASS_ORNAMENT_SIZE
			)
		} finally {
			drawContext.setColor(1f, 1f, 1f, 1f)
			drawContext.flush()
			RenderSystem.disableBlend()
		}
	}

	private fun renderCompassPartyMarkers(
		drawContext: net.minecraft.client.gui.GuiGraphics,
		client: Minecraft,
		centerX: Int,
		startY: Int,
		player: net.minecraft.world.entity.player.Player,
		playerYaw: Float
	) {
		val partyMembers = getCompassPartyMembers(player, centerX, playerYaw)
		if (partyMembers.isEmpty()) {
			return
		}

		val iconY = startY + (COMPASS_HEIGHT - COMPASS_PARTY_HEAD_SIZE) / 2 + COMPASS_PARTY_HEAD_Y_OFFSET
		for (member in partyMembers.sortedByDescending { abs(it.delta) }) {
			val markerAlpha = compassAlphaForX(centerX, member.x)
			drawCompassPartyHead(drawContext, client, member, iconY, markerAlpha)

			val nameY = iconY + COMPASS_PARTY_HEAD_SIZE + COMPASS_PARTY_NAME_Y_GAP
			drawContext.drawString(
				client.font,
				member.name,
				member.x - client.font.width(member.name) / 2,
				nameY,
				withAlpha(COMPASS_PARTY_NAME_COLOR, markerAlpha),
				true
			)

			val distanceText = formatCompassDistance(member.distanceBlocks)
			drawContext.drawString(
				client.font,
				distanceText,
				member.x - client.font.width(distanceText) / 2,
				nameY + client.font.lineHeight + COMPASS_PARTY_DISTANCE_Y_GAP,
				withAlpha(COMPASS_PARTY_DISTANCE_COLOR, markerAlpha),
				true
			)
		}
	}

	private fun renderCompassTicks(drawContext: net.minecraft.client.gui.GuiGraphics, centerX: Int, startY: Int, playerYaw: Float) {
		for (bearing in 0 until 360 step COMPASS_TICK_STEP_DEGREES) {
			val x = compassXForBearing(centerX, playerYaw, bearing.toFloat()) ?: continue
			val top = if (bearing % COMPASS_TICK_MAJOR_STEP_DEGREES == 0) COMPASS_TICK_MAJOR_TOP else COMPASS_TICK_MINOR_TOP
			drawContext.fill(x, startY + top, x + 1, startY + COMPASS_TICK_BOTTOM, withAlpha(COMPASS_TICK_COLOR, compassAlphaForX(centerX, x)))
		}
	}

	private fun renderCompassLabels(drawContext: net.minecraft.client.gui.GuiGraphics, client: Minecraft, centerX: Int, startY: Int, playerYaw: Float) {
		for (label in COMPASS_CARDINALS) {
			val x = compassXForBearing(centerX, playerYaw, label.bearing.toFloat()) ?: continue
			drawContext.drawString(
				client.font,
				label.text,
				x - client.font.width(label.text) / 2 + 1,
				startY + COMPASS_LABEL_Y_OFFSET,
				withAlpha(COMPASS_LABEL_COLOR, compassAlphaForX(centerX, x)),
				true
			)
		}
	}

	private fun renderCompassSpawnMarker(
		drawContext: net.minecraft.client.gui.GuiGraphics,
		client: Minecraft,
		centerX: Int,
		startY: Int,
		player: net.minecraft.world.entity.player.Player,
		playerYaw: Float
	) {
		val marker = getCompassSpawnMarker(centerX, player, playerYaw) ?: return
		val markerAlpha = compassAlphaForX(centerX, marker.x)
		val markerY = startY + (COMPASS_HEIGHT - COMPASS_SPAWN_RENDER_HEIGHT) / 2 + COMPASS_SPAWN_Y_OFFSET
		drawCompassSpawnIcon(drawContext, marker.x, markerY, markerAlpha)

		if (abs(marker.delta) > COMPASS_SPAWN_DISTANCE_SHOW_ANGLE) {
			return
		}

		val spawnLabelY = markerY + COMPASS_SPAWN_RENDER_HEIGHT + COMPASS_SPAWN_LABEL_Y_GAP
		drawContext.drawString(
			client.font,
			COMPASS_SPAWN_LABEL_TEXT,
			marker.x - client.font.width(COMPASS_SPAWN_LABEL_TEXT) / 2,
			spawnLabelY,
			withAlpha(COMPASS_SPAWN_LABEL_COLOR, markerAlpha),
			true
		)

		val distanceText = formatCompassDistance(marker.distanceBlocks)
		drawContext.drawString(
			client.font,
			distanceText,
			marker.x - client.font.width(distanceText) / 2,
			spawnLabelY + client.font.lineHeight + COMPASS_SPAWN_DISTANCE_Y_GAP,
			withAlpha(COMPASS_SPAWN_DISTANCE_COLOR, markerAlpha),
			true
		)
	}

	private fun renderCompassDeathMarker(
		drawContext: net.minecraft.client.gui.GuiGraphics,
		client: Minecraft,
		centerX: Int,
		startY: Int,
		player: net.minecraft.world.entity.player.Player,
		playerYaw: Float
	) {
		val marker = getCompassDeathMarker(centerX, player, playerYaw) ?: return
		val markerAlpha = compassAlphaForX(centerX, marker.x)
		val markerY = startY + (COMPASS_HEIGHT - COMPASS_DEATH_RENDER_SIZE) / 2 + COMPASS_DEATH_Y_OFFSET
		drawCompassDeathIcon(drawContext, marker.x, markerY, markerAlpha)

		val deathLabelY = markerY + COMPASS_DEATH_RENDER_SIZE + COMPASS_DEATH_LABEL_Y_GAP
		drawContext.drawString(
			client.font,
			COMPASS_DEATH_LABEL_TEXT,
			marker.x - client.font.width(COMPASS_DEATH_LABEL_TEXT) / 2,
			deathLabelY,
			withAlpha(COMPASS_DEATH_LABEL_COLOR, markerAlpha),
			true
		)

		if (abs(marker.delta) > COMPASS_DEATH_DISTANCE_SHOW_ANGLE) {
			return
		}

		val distanceText = formatCompassDistance(marker.distanceBlocks)
		drawContext.drawString(
			client.font,
			distanceText,
			marker.x - client.font.width(distanceText) / 2,
			deathLabelY + client.font.lineHeight + COMPASS_DEATH_DISTANCE_Y_GAP,
			withAlpha(COMPASS_DEATH_DISTANCE_COLOR, markerAlpha),
			true
		)
	}

	private fun renderCompassMapMarkers(
		drawContext: net.minecraft.client.gui.GuiGraphics,
		client: Minecraft,
		centerX: Int,
		startY: Int,
		player: net.minecraft.world.entity.player.Player,
		playerYaw: Float
	) {
		val waypoints = getHeldMapWaypoints(client, player, centerX, playerYaw)
		if (waypoints.isEmpty()) {
			return
		}

		val iconY = startY + (COMPASS_HEIGHT - COMPASS_MAP_MARKER_SIZE) / 2 + COMPASS_MAP_MARKER_Y_OFFSET
		for (waypoint in waypoints) {
			val markerAlpha = compassAlphaForX(centerX, waypoint.x)
			drawCompassMapMarkerIcon(drawContext, client, waypoint, iconY, markerAlpha)
		}

		val focusedWaypoints = waypoints
			.filter { abs(it.delta) <= COMPASS_MAP_INFO_SHOW_ANGLE }
			.sortedBy { abs(it.delta) }
			.take(COMPASS_MAP_INFO_MAX_LINES)
		if (focusedWaypoints.isEmpty()) {
			return
		}

		var textY = startY + COMPASS_MAP_INFO_TOP_OFFSET
		for (waypoint in focusedWaypoints) {
			val label = "${waypoint.name.string}: ${formatCompassDistance(waypoint.distanceBlocks)}"
			val labelAlpha = compassAlphaForX(centerX, waypoint.x)
			drawContext.drawString(
				client.font,
				label,
				centerX - client.font.width(label) / 2,
				textY,
				withAlpha(COMPASS_MAP_INFO_COLOR, labelAlpha),
				true
			)
			textY += client.font.lineHeight + COMPASS_MAP_INFO_LINE_GAP
		}
	}

	private fun drawCompassMapMarkerIcon(
		drawContext: net.minecraft.client.gui.GuiGraphics,
		client: Minecraft,
		waypoint: CompassMapWaypoint,
		startY: Int,
		alpha: Float
	) {
		val bannerAppearance = waypoint.bannerAppearance
		if (bannerAppearance != null) {
			renderCompassBannerWaypointIcon(drawContext, bannerAppearance, waypoint.x, startY, alpha)
			return
		}

		val sprite = client.mapDecorationTextures.get(waypoint.decoration)
		drawContext.blit(
			waypoint.x - COMPASS_MAP_MARKER_SIZE / 2,
			startY,
			0,
			COMPASS_MAP_MARKER_SIZE,
			COMPASS_MAP_MARKER_SIZE,
			sprite,
			1f,
			1f,
			1f,
			alpha
		)
	}

	private fun getHeldMapWaypoints(
		client: Minecraft,
		player: net.minecraft.world.entity.player.Player,
		centerX: Int,
		playerYaw: Float
	): List<CompassMapWaypoint> {
		val syncedWaypoints = getSyncedCompassMapWaypoints(player, centerX, playerYaw)
		if (syncedWaypoints != null) {
			return syncedWaypoints
		}

		val waypoints = linkedMapOf<String, CompassMapWaypoint>()
		collectHeldMapWaypoints(player, player.mainHandItem, centerX, playerYaw, waypoints)
		collectHeldMapWaypoints(player, player.offhandItem, centerX, playerYaw, waypoints)
		return waypoints.values.toList()
	}

	private fun getSyncedCompassMapWaypoints(
		player: net.minecraft.world.entity.player.Player,
		centerX: Int,
		playerYaw: Float
	): List<CompassMapWaypoint>? {
		val syncedWaypoints = compassMapWaypoints ?: return null
		val playerDimensionId = player.level().dimension().location().toString()
		val waypoints = ArrayList<CompassMapWaypoint>(syncedWaypoints.size)

		for (waypoint in syncedWaypoints) {
			if (waypoint.dimensionId != playerDimensionId) {
				continue
			}

			val decorationTypeId = ResourceLocation.parse(waypoint.decorationTypeId)
			val decorationType = BuiltInRegistries.MAP_DECORATION_TYPE.get(decorationTypeId) ?: continue
			val deltaX = waypoint.x + 0.5 - player.x
			val deltaZ = waypoint.z + 0.5 - player.z
			val distanceBlocks = hypot(deltaX, deltaZ)
			val bearing = ((Math.toDegrees(atan2(deltaX, -deltaZ)) + 360.0) % 360.0).toFloat()
			val delta = compassDeltaForBearing(playerYaw, bearing)
			val x = compassXForBearing(centerX, playerYaw, bearing) ?: continue
			waypoints.add(
				CompassMapWaypoint(
					decoration = net.minecraft.world.level.saveddata.maps.MapDecoration(
						BuiltInRegistries.MAP_DECORATION_TYPE.wrapAsHolder(decorationType),
						0,
						0,
						0,
						Optional.of(Component.literal(waypoint.name))
					),
					name = Component.literal(waypoint.name),
					x = x,
					delta = delta,
					distanceBlocks = distanceBlocks,
					bannerAppearance = waypoint.bannerAppearance
				)
			)
		}

		return waypoints
	}

	private fun getCompassPartyMembers(
		player: net.minecraft.world.entity.player.Player,
		centerX: Int,
		playerYaw: Float
	): List<CompassPartyMember> {
		val syncedMembers = compassPartyMembers ?: return emptyList()
		val playerDimensionId = player.level().dimension().location().toString()
		val partyMembers = ArrayList<CompassPartyMember>(syncedMembers.size)

		for (member in syncedMembers) {
			if (member.dimensionId != playerDimensionId) {
				continue
			}

			val deltaX = member.x + 0.5 - player.x
			val deltaZ = member.z + 0.5 - player.z
			val distanceBlocks = hypot(deltaX, deltaZ)
			if (distanceBlocks < 1.0) {
				continue
			}

			val bearing = ((Math.toDegrees(atan2(deltaX, -deltaZ)) + 360.0) % 360.0).toFloat()
			val delta = compassDeltaForBearing(playerYaw, bearing)
			val x = compassXForBearing(centerX, playerYaw, bearing) ?: continue
			partyMembers.add(
				CompassPartyMember(
					uuid = member.uuid,
					name = member.name,
					x = x,
					delta = delta,
					distanceBlocks = distanceBlocks
				)
			)
		}

		return partyMembers
	}

	private fun collectHeldMapWaypoints(
		player: net.minecraft.world.entity.player.Player,
		stack: net.minecraft.world.item.ItemStack,
		centerX: Int,
		playerYaw: Float,
		waypoints: MutableMap<String, CompassMapWaypoint>
	) {
		if (stack.item !is MapItem) {
			return
		}

		val mapData = MapItem.getSavedData(stack, player.level()) ?: return
		if (mapData.dimension != player.level().dimension()) {
			return
		}

		val playerDecoration = getLocalPlayerMapDecoration(mapData, playerYaw)
		val mapId = stack.get(DataComponents.MAP_ID)?.toString() ?: "unknown"

		for (decoration in mapData.getDecorations()) {
			if (!isCompassBannerDecoration(decoration)) {
				continue
			}

			val name = decoration.name().orElse(null) ?: continue
			val waypointDelta = getMapDecorationDelta(mapData, decoration, playerDecoration, player) ?: continue
			val deltaX = waypointDelta.first
			val deltaZ = waypointDelta.second
			val distanceBlocks = hypot(deltaX, deltaZ)
			val bearing = ((Math.toDegrees(atan2(deltaX, -deltaZ)) + 360.0) % 360.0).toFloat()
			val delta = compassDeltaForBearing(playerYaw, bearing)
			val x = compassXForBearing(centerX, playerYaw, bearing) ?: continue
			val key = "${mapData.dimension.location()}|$mapId|${decoration.getSpriteLocation()}|${decoration.x()}|${decoration.y()}|${name.string}"
			waypoints.putIfAbsent(
				key,
				CompassMapWaypoint(
					decoration = decoration,
					name = name,
					x = x,
					delta = delta,
					distanceBlocks = distanceBlocks
				)
			)
		}
	}

	private fun getMapDecorationDelta(
		mapData: net.minecraft.world.level.saveddata.maps.MapItemSavedData,
		decoration: net.minecraft.world.level.saveddata.maps.MapDecoration,
		playerDecoration: net.minecraft.world.level.saveddata.maps.MapDecoration?,
		player: net.minecraft.world.entity.player.Player
	): Pair<Double, Double>? {
		val scaleFactor = (1 shl mapData.scale.toInt()).toDouble()
		if (playerDecoration != null) {
			val deltaX = (decoration.x().toInt() - playerDecoration.x().toInt()) * scaleFactor / 2.0
			val deltaZ = (decoration.y().toInt() - playerDecoration.y().toInt()) * scaleFactor / 2.0
			return deltaX to deltaZ
		}

		if (mapData.centerX == 0 && mapData.centerZ == 0) {
			return null
		}

		val deltaX = mapData.centerX + decoration.x().toInt() * scaleFactor / 2.0 - player.x
		val deltaZ = mapData.centerZ + decoration.y().toInt() * scaleFactor / 2.0 - player.z
		return deltaX to deltaZ
	}

	private fun getLocalPlayerMapDecoration(
		mapData: net.minecraft.world.level.saveddata.maps.MapItemSavedData,
		playerYaw: Float
	): net.minecraft.world.level.saveddata.maps.MapDecoration? {
		val expectedRotationBucket = mapRotationBucketForYaw(playerYaw)
		var bestDecoration: net.minecraft.world.level.saveddata.maps.MapDecoration? = null
		var bestDistance = Int.MAX_VALUE

		for (decoration in mapData.getDecorations()) {
			if (!isPlayerMapDecoration(decoration)) {
				continue
			}

			val rotationDistance = mapRotationBucketDistance(decoration.rot().toInt() and 15, expectedRotationBucket)
			if (rotationDistance < bestDistance) {
				bestDistance = rotationDistance
				bestDecoration = decoration
			}
		}

		return bestDecoration
	}

	private fun isCompassBannerDecoration(decoration: net.minecraft.world.level.saveddata.maps.MapDecoration): Boolean {
		return decoration.getSpriteLocation().path.endsWith("_banner")
	}

	private fun isPlayerMapDecoration(decoration: net.minecraft.world.level.saveddata.maps.MapDecoration): Boolean {
		return when (decoration.getSpriteLocation().path) {
			"player", "player_off_map", "player_off_limits" -> true
			else -> false
		}
	}

	private fun mapRotationBucketForYaw(playerYaw: Float): Int {
		val adjustedYaw = if (playerYaw < 0f) playerYaw - 8f else playerYaw + 8f
		return (adjustedYaw * 16f / 360f).toInt() and 15
	}

	private fun mapRotationBucketDistance(first: Int, second: Int): Int {
		val clockwise = Math.floorMod(first - second, 16)
		val counterClockwise = Math.floorMod(second - first, 16)
		return minOf(clockwise, counterClockwise)
	}

	private fun drawCompassSpawnIcon(drawContext: net.minecraft.client.gui.GuiGraphics, centerX: Int, startY: Int, alpha: Float) {
		drawContext.setColor(1f, 1f, 1f, alpha)
		drawContext.blit(
			COMPASS_SPAWN_TEXTURE,
			centerX - COMPASS_SPAWN_RENDER_WIDTH / 2,
			startY,
			COMPASS_SPAWN_RENDER_WIDTH,
			COMPASS_SPAWN_RENDER_HEIGHT,
			0f,
			0f,
			COMPASS_SPAWN_TEXTURE_WIDTH,
			COMPASS_SPAWN_TEXTURE_HEIGHT,
			COMPASS_SPAWN_TEXTURE_WIDTH,
			COMPASS_SPAWN_TEXTURE_HEIGHT
		)
		drawContext.setColor(1f, 1f, 1f, 1f)
	}

	private fun drawCompassDeathIcon(drawContext: net.minecraft.client.gui.GuiGraphics, centerX: Int, startY: Int, alpha: Float) {
		drawContext.setColor(1f, 1f, 1f, alpha)
		drawContext.blit(
			COMPASS_DEATH_TEXTURE,
			centerX - COMPASS_DEATH_RENDER_SIZE / 2,
			startY,
			COMPASS_DEATH_RENDER_SIZE,
			COMPASS_DEATH_RENDER_SIZE,
			0f,
			0f,
			COMPASS_DEATH_TEXTURE_SIZE,
			COMPASS_DEATH_TEXTURE_SIZE,
			COMPASS_DEATH_TEXTURE_SIZE,
			COMPASS_DEATH_TEXTURE_SIZE
		)
		drawContext.setColor(1f, 1f, 1f, 1f)
	}

	private fun drawCompassPartyHead(
		drawContext: net.minecraft.client.gui.GuiGraphics,
		client: Minecraft,
		member: CompassPartyMember,
		startY: Int,
		alpha: Float
	) {
		drawContext.setColor(1f, 1f, 1f, alpha)
		PlayerFaceRenderer.draw(
			drawContext,
			getPartyMemberSkin(client, member.uuid),
			member.x - COMPASS_PARTY_HEAD_SIZE / 2,
			startY,
			COMPASS_PARTY_HEAD_SIZE
		)
		drawContext.setColor(1f, 1f, 1f, 1f)
	}

	private fun getPartyMemberSkin(client: Minecraft, playerUuid: UUID): ResourceLocation {
		return client.connection?.getPlayerInfo(playerUuid)?.skin?.texture() ?: DefaultPlayerSkin.get(playerUuid).texture()
	}

	private fun getCompassSpawnMarker(centerX: Int, player: net.minecraft.world.entity.player.Player, playerYaw: Float): CompassSpawnMarker? {
		val spawnTarget = compassSpawnTarget ?: return null
		if (spawnTarget.dimension != player.level().dimension()) {
			return null
		}

		val spawnPos = spawnTarget.position
		val deltaX = spawnPos.x + 0.5 - player.x
		val deltaZ = spawnPos.z + 0.5 - player.z
		val distanceBlocks = hypot(deltaX, deltaZ)
		if (distanceBlocks < 1.0) {
			return null
		}

		val bearing = ((Math.toDegrees(atan2(deltaX, -deltaZ)) + 360.0) % 360.0).toFloat()
		val delta = compassDeltaForBearing(playerYaw, bearing)
		val x = compassXForBearing(centerX, playerYaw, bearing) ?: return null
		return CompassSpawnMarker(x, delta, distanceBlocks)
	}

	private fun getCompassDeathMarker(centerX: Int, player: net.minecraft.world.entity.player.Player, playerYaw: Float): CompassDeathMarker? {
		val deathTarget = compassDeathTarget ?: return null
		if (deathTarget.dimension != player.level().dimension()) {
			return null
		}

		val deathPos = deathTarget.position
		val deltaX = deathPos.x + 0.5 - player.x
		val deltaZ = deathPos.z + 0.5 - player.z
		val distanceBlocks = hypot(deltaX, deltaZ)
		if (distanceBlocks < 1.0) {
			return null
		}

		val bearing = ((Math.toDegrees(atan2(deltaX, -deltaZ)) + 360.0) % 360.0).toFloat()
		val delta = compassDeltaForBearing(playerYaw, bearing)
		val x = compassXForBearing(centerX, playerYaw, bearing) ?: return null
		return CompassDeathMarker(x, delta, distanceBlocks)
	}

	private fun compassXForBearing(centerX: Int, playerYaw: Float, bearing: Float): Int? {
		val delta = compassDeltaForBearing(playerYaw, bearing)
		if (abs(delta) > COMPASS_VISIBLE_DEGREES / 2f) {
			return null
		}

		return (centerX + (delta / COMPASS_VISIBLE_DEGREES) * COMPASS_WIDTH).roundToInt()
	}

	private fun compassDeltaForBearing(playerYaw: Float, bearing: Float): Float {
		return Mth.wrapDegrees(compassBearingToMinecraftYaw(bearing) - playerYaw)
	}

	private fun compassBearingToMinecraftYaw(bearing: Float): Float {
		return Mth.wrapDegrees(bearing + 180f)
	}

	private fun formatCompassDistance(distanceBlocks: Double): String {
		val roundedDistance = distanceBlocks.roundToInt().coerceAtLeast(0)
		if (roundedDistance >= 1000) {
			val kilometers = String.format(Locale.ROOT, "%.1f", roundedDistance / 1000.0)
				.trimEnd('0')
				.trimEnd('.')
			return "${kilometers}km"
		}

		return "${roundedDistance}m"
	}

	private fun compassAlphaForX(centerX: Int, x: Int): Float {
		val normalizedDistance = abs(x - centerX) / (COMPASS_WIDTH / 2f)
		if (normalizedDistance <= COMPASS_EDGE_FADE_START) {
			return 1f
		}

		return (1f - (normalizedDistance - COMPASS_EDGE_FADE_START) / (1f - COMPASS_EDGE_FADE_START)).coerceIn(0f, 1f)
	}

	private fun withAlpha(color: Int, alpha: Float): Int {
		val baseAlpha = color ushr 24 and 0xFF
		val scaledAlpha = (baseAlpha * alpha).roundToInt().coerceIn(0, 255)
		return (scaledAlpha shl 24) or (color and 0x00FFFFFF)
	}

	private fun renderStaminaHud(drawContext: net.minecraft.client.gui.GuiGraphics) {
		val client = Minecraft.getInstance()
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return
		}

		val player = client.player ?: return
		if (!shouldRenderGameplayHud(client, player)) {
			return
		}

		val maxStamina = StaminaSystem.getMaxStamina(player)
		val currentStamina = StaminaSystem.getCurrentStamina(player)
		val fillRatio = currentStamina.toFloat() / maxStamina.toFloat()
		val fillWidth = (BAR_WIDTH * fillRatio).toInt().coerceIn(0, BAR_WIDTH)
		val penaltyEnabled = StaminaSystem.isExhaustionPenaltyEnabled()
		val isExhaustedLockout = StaminaSystem.isExhaustedLockout(player)
		val fillTexture = if (StaminaSystem.isHungerDraining(player)) STAMINA_BAR_FILL_HUNGER_TEXTURE else STAMINA_BAR_FILL_TEXTURE
		val showWarning = fillRatio <= WARNING_STAMINA_THRESHOLD || (penaltyEnabled && isExhaustedLockout)
		val showWarningFrame = showWarning && (player.tickCount / WARNING_FLASH_INTERVAL) % 2 == 0
		val startX = client.window.guiScaledWidth / 2 + STAMINA_BAR_START_X_OFFSET + BAR_OUTWARD_SHIFT
		val startY = getHudBarStartY(client)

		drawContext.blit(
			STAMINA_BAR_FRAME_TEXTURE,
			startX,
			startY,
			0f,
			0f,
			BAR_FRAME_WIDTH,
			BAR_FRAME_HEIGHT,
			BAR_FRAME_WIDTH,
			BAR_FRAME_HEIGHT
		)

		if (showWarningFrame) {
			drawContext.blit(
				STAMINA_BAR_FRAME_WARN_TEXTURE,
				startX,
				startY,
				0f,
				0f,
				BAR_FRAME_WIDTH,
				BAR_FRAME_HEIGHT,
				BAR_FRAME_WIDTH,
				BAR_FRAME_HEIGHT
			)
		}

		if (fillWidth > 0) {
			drawContext.blit(
				fillTexture,
				startX,
				startY,
				0f,
				0f,
				fillWidth,
				BAR_HEIGHT,
				BAR_WIDTH,
				BAR_HEIGHT
			)
		}
	}

	private fun renderHealthHud(drawContext: net.minecraft.client.gui.GuiGraphics) {
		val client = Minecraft.getInstance()
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return
		}

		val player = client.player ?: return
		if (!shouldRenderGameplayHud(client, player)) {
			return
		}

		val maxHealth = player.maxHealth.coerceAtLeast(1.0f)
		val currentHealth = player.health.coerceIn(0.0f, maxHealth)
		val fillRatio = currentHealth / maxHealth
		val fillWidth = (BAR_WIDTH * fillRatio).toInt().coerceIn(0, BAR_WIDTH)
		val startX = client.window.guiScaledWidth / 2 + HEALTH_BAR_START_X_OFFSET - BAR_OUTWARD_SHIFT
		val startY = getHudBarStartY(client)

		drawContext.blit(
			HEALTH_BAR_FRAME_TEXTURE,
			startX,
			startY,
			0f,
			0f,
			BAR_FRAME_WIDTH,
			BAR_FRAME_HEIGHT,
			BAR_FRAME_WIDTH,
			BAR_FRAME_HEIGHT
		)

		if (fillWidth > 0) {
			drawContext.blit(
				HEALTH_BAR_FILL_TEXTURE,
				startX,
				startY,
				0f,
				0f,
				fillWidth,
				BAR_HEIGHT,
				BAR_WIDTH,
				BAR_HEIGHT
			)
		}
	}

	private fun renderExperienceWidget(drawContext: net.minecraft.client.gui.GuiGraphics) {
		val client = Minecraft.getInstance()
		if (!HyStaminaCompatibility.isClientGameplayEnabled()) {
			return
		}

		val player = client.player ?: return
		if (!shouldRenderGameplayHud(client, player)) {
			return
		}

		val centerX = client.window.guiScaledWidth / 2
		val startY = getHudBarStartY(client) + EXPERIENCE_WIDGET_Y_OFFSET
		val widgetX = centerX - EXPERIENCE_WIDGET_WIDTH / 2
		val widgetY = startY
		val levelText = player.experienceLevel.toString()
		val textX = centerX - client.font.width(levelText) / 2
		val textY = widgetY + (EXPERIENCE_WIDGET_HEIGHT - client.font.lineHeight) / 2 + 1

		drawContext.blit(
			EXPERIENCE_WIDGET_TEXTURE,
			widgetX,
			widgetY,
			0f,
			0f,
			EXPERIENCE_WIDGET_WIDTH,
			EXPERIENCE_WIDGET_HEIGHT,
			EXPERIENCE_WIDGET_WIDTH,
			EXPERIENCE_WIDGET_HEIGHT
		)

		drawContext.drawString(client.font, levelText, textX, textY, EXPERIENCE_TEXT_COLOR, true)
	}

	private fun getHudBarStartY(client: Minecraft): Int {
		val healthRowTop = client.window.guiScaledHeight - VANILLA_HEALTH_ROW_TOP_OFFSET
		val experienceBarTop = client.window.guiScaledHeight - VANILLA_EXPERIENCE_BAR_TOP_OFFSET
		return maxOf(healthRowTop, experienceBarTop - BAR_HEIGHT - BAR_TO_EXPERIENCE_GAP) - 2
	}

	private fun shouldRenderCompassHud(client: Minecraft, player: net.minecraft.world.entity.player.Player): Boolean {
		return !client.options.hideGui && !player.isSpectator
	}

	private fun shouldRenderGameplayHud(client: Minecraft, player: net.minecraft.world.entity.player.Player): Boolean {
		return shouldRenderCompassHud(client, player) && client.gameMode?.canHurtPlayer() == true
	}

	private fun texture(path: String): ResourceLocation {
		return ResourceLocation.fromNamespaceAndPath(HyStamina.MOD_ID, "textures/$path.png")
	}

	private fun renderCompassBannerWaypointIcon(
		drawContext: net.minecraft.client.gui.GuiGraphics,
		appearance: HyStaminaNetworking.BannerAppearanceData,
		centerX: Int,
		startY: Int,
		alpha: Float
	) {
		val bannerX = centerX - COMPASS_PATTERNED_BANNER_WIDTH / 2
		val bannerY = startY - (COMPASS_PATTERNED_BANNER_HEIGHT - COMPASS_MAP_MARKER_SIZE) / 2

		drawBannerFaceLayer(
			drawContext,
			Sheets.BANNER_BASE.sprite(),
			bannerX,
			bannerY,
			COMPASS_PATTERNED_BANNER_WIDTH,
			COMPASS_PATTERNED_BANNER_HEIGHT,
			appearance.baseColor.getTextureDiffuseColor(),
			alpha
		)

		for (layer in appearance.patternLayers.layers()) {
			drawBannerFaceLayer(
				drawContext,
				Sheets.getBannerMaterial(layer.pattern()).sprite(),
				bannerX,
				bannerY,
				COMPASS_PATTERNED_BANNER_WIDTH,
				COMPASS_PATTERNED_BANNER_HEIGHT,
				layer.color().getTextureDiffuseColor(),
				alpha
			)
		}
	}

	private fun drawBannerFaceLayer(
		drawContext: net.minecraft.client.gui.GuiGraphics,
		sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite,
		x: Int,
		y: Int,
		width: Int,
		height: Int,
		rgbColor: Int,
		alpha: Float
	) {
		val atlasWidth = (sprite.contents().width() / (sprite.getU1() - sprite.getU0())).roundToInt()
		val atlasHeight = (sprite.contents().height() / (sprite.getV1() - sprite.getV0())).roundToInt()
		val atlasU = sprite.getX() + BANNER_PATTERN_FACE_U
		val atlasV = sprite.getY() + BANNER_PATTERN_FACE_V
		val red = (rgbColor shr 16 and 0xFF) / 255f
		val green = (rgbColor shr 8 and 0xFF) / 255f
		val blue = (rgbColor and 0xFF) / 255f

		drawContext.setColor(red, green, blue, alpha)
		drawContext.blit(
			sprite.atlasLocation(),
			x,
			y,
			width,
			height,
			atlasU.toFloat(),
			atlasV.toFloat(),
			BANNER_PATTERN_FACE_WIDTH,
			BANNER_PATTERN_FACE_HEIGHT,
			atlasWidth,
			atlasHeight
		)
		drawContext.setColor(1f, 1f, 1f, 1f)
	}

	private data class CompassLabel(val bearing: Int, val text: String)
	private data class CompassMapWaypoint(
		val decoration: net.minecraft.world.level.saveddata.maps.MapDecoration,
		val name: Component,
		val x: Int,
		val delta: Float,
		val distanceBlocks: Double,
		val bannerAppearance: HyStaminaNetworking.BannerAppearanceData? = null
	)
	private data class CompassPartyMember(
		val uuid: UUID,
		val name: String,
		val x: Int,
		val delta: Float,
		val distanceBlocks: Double
	)
	private data class CompassDeathMarker(val x: Int, val delta: Float, val distanceBlocks: Double)
	private data class CompassSpawnMarker(val x: Int, val delta: Float, val distanceBlocks: Double)
}