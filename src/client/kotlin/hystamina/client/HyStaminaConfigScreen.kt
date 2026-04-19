package hystamina.client

import hystamina.HyStaminaConfig
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

class HyStaminaConfigScreen(private val parent: Screen?) : Screen(TITLE) {
	private val titleColor = 0xFFF7E9C0.toInt()
	private val sectionTitleColor = 0xFFE7D7A6.toInt()
	private val textColor = 0xFFFFFFFF.toInt()
	private val mutedTextColor = 0xFFB4C0CC.toInt()
	private val panelFillColor = 0xE6141B23.toInt()
	private val panelHeaderColor = 0xF21D2833.toInt()
	private val panelBorderColor = 0xD17C6A46.toInt()
	private val dividerColor = 0x88606E7C.toInt()
	private val errorColor = -0x8283
	private val errorFillColor = 0xE03A1B1B.toInt()
	private val infoColor = 0xFFB8D8A3.toInt()
	private val infoFillColor = 0xDD18311C.toInt()
	private val minimumControlWidth = 96
	private val maximumControlWidth = 148
	private val buttonGap = 8
	private val scrollBarWidth = 4
	private val scrollBarGap = 6
	private val scrollTrackColor = 0x77303A45
	private val scrollThumbColor = 0xCCB5C4D1.toInt()

	private lateinit var sprintSpeedField: EditBox
	private lateinit var sprintDurationField: EditBox
	private lateinit var rechargeDurationField: EditBox
	private lateinit var miningDepletesStaminaToggle: CycleButton<Boolean>
	private lateinit var attackingDepletesStaminaToggle: CycleButton<Boolean>
	private lateinit var attackStaminaCostField: EditBox
	private lateinit var hyFoodToggle: CycleButton<Boolean>
	private lateinit var foodHealingPercentField: EditBox
	private lateinit var foodHealSpeedPercentField: EditBox
	private lateinit var penaltyToggle: CycleButton<Boolean>
	private var validationMessage: Component? = null
	private var serverOverrideActive = false
	private var movementPanel = PanelBounds()
	private var staminaPanel = PanelBounds()
	private var foodPanel = PanelBounds()
	private var penaltyPanel = PanelBounds()
	private var layout = LayoutMetrics.regular()
	private var scrollOffset = 0
	private val scrollableWidgets = mutableListOf<ScrollableWidget>()
	private val footerWidgets = mutableListOf<AbstractWidget>()

	override fun init() {
		clearWidgets()
		scrollableWidgets.clear()
		footerWidgets.clear()
		serverOverrideActive = HyStaminaConfig.hasServerOverride()
		layout = LayoutMetrics.forScreenHeight(height)
		val config = HyStaminaConfig.localSnapshot()
		layoutPanels()

		sprintSpeedField = addScrollableWidget(createDecimalField(rowLayout(movementPanel, 0), config.movement.sprintBlocksPerSecond.toString()), PanelSection.MOVEMENT, 0)
		sprintDurationField = addScrollableWidget(createDecimalField(rowLayout(staminaPanel, 0), config.stamina.secondsUntilEmpty.toString()), PanelSection.STAMINA, 0)
		rechargeDurationField = addScrollableWidget(createDecimalField(rowLayout(staminaPanel, 1), config.stamina.secondsToFullRecharge.toString()), PanelSection.STAMINA, 1)
		miningDepletesStaminaToggle = addScrollableWidget(createToggle(rowLayout(staminaPanel, 2), config.stamina.miningDepletesStamina, "Mining depletes stamina"), PanelSection.STAMINA, 2)
		attackingDepletesStaminaToggle = addScrollableWidget(createToggle(rowLayout(staminaPanel, 3), config.stamina.attackingDepletesStamina, "Attacking depletes stamina"), PanelSection.STAMINA, 3)
		attackStaminaCostField = addScrollableWidget(createIntegerField(rowLayout(staminaPanel, 4), config.stamina.attackStaminaCost.toString()), PanelSection.STAMINA, 4)
		hyFoodToggle = addScrollableWidget(createToggle(rowLayout(foodPanel, 0), config.food.hyFood, "HyFood heal-over-time mode"), PanelSection.FOOD, 0)
		foodHealingPercentField = addScrollableWidget(createDecimalField(rowLayout(foodPanel, 1), config.food.healingPercent.toString()), PanelSection.FOOD, 1)
		foodHealSpeedPercentField = addScrollableWidget(createDecimalField(rowLayout(foodPanel, 2), config.food.healSpeedPercent.toString()), PanelSection.FOOD, 2)
		penaltyToggle = addScrollableWidget(createToggle(rowLayout(penaltyPanel, 0), config.penalty.enabled, "Require full recharge after empty"), PanelSection.PENALTY, 0)

		val buttonWidth = ((width - layout.screenPadding * 2 - buttonGap) / 2).coerceAtLeast(100)
		val buttonY = height - layout.screenPadding - layout.buttonHeight

		addFooterWidget(
			Button.builder(Component.literal("Save")) {
				saveAndClose()
			}
				.bounds(layout.screenPadding, buttonY, buttonWidth, layout.buttonHeight)
				.build()
		)

		addFooterWidget(
			Button.builder(Component.literal("Cancel")) {
				onClose()
			}
				.bounds(width - layout.screenPadding - buttonWidth, buttonY, buttonWidth, layout.buttonHeight)
				.build()
		)

		clampScrollOffset()
		updateScrollableWidgetPositions()
	}

	override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		renderBackground(guiGraphics, mouseX, mouseY, partialTick)
		guiGraphics.drawCenteredString(font, title, width / 2, layout.titleY, titleColor)
		updateScrollableWidgetPositions()
		renderScrollableContent(guiGraphics, mouseX, mouseY, partialTick)
		drawStatusBanner(guiGraphics)
		for (widget in footerWidgets) {
			widget.render(guiGraphics, mouseX, mouseY, partialTick)
		}
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		if (maxScrollOffset() <= 0) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
		}

		if (mouseY < contentViewportTop() || mouseY > contentViewportBottom()) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
		}

		val nextOffset = (scrollOffset - scrollY * (layout.rowHeight + layout.rowGap)).roundToInt()
		if (nextOffset == scrollOffset) {
			return false
		}

		scrollOffset = nextOffset.coerceIn(0, maxScrollOffset())
		updateScrollableWidgetPositions()
		return true
	}

	override fun onClose() {
		minecraft?.setScreen(parent)
	}

	private fun layoutPanels() {
		val availableWidth = width - layout.screenPadding * 2
		val movementHeight = panelHeight(1)
		val staminaHeight = panelHeight(5)
		val foodHeight = panelHeight(3)
		val penaltyHeight = panelHeight(1)
		val contentTop = layout.contentTop

		if (availableWidth >= 520) {
			val columnWidth = (availableWidth - layout.panelGap) / 2
			val leftX = layout.screenPadding
			val rightX = leftX + columnWidth + layout.panelGap

			movementPanel = PanelBounds(leftX, contentTop, columnWidth, movementHeight)
			staminaPanel = PanelBounds(leftX, movementPanel.bottom + layout.panelGap, columnWidth, staminaHeight)
			foodPanel = PanelBounds(rightX, contentTop, columnWidth, foodHeight)
			penaltyPanel = PanelBounds(rightX, foodPanel.bottom + layout.panelGap, columnWidth, penaltyHeight)
		} else {
			val panelWidth = availableWidth
			val x = layout.screenPadding

			movementPanel = PanelBounds(x, contentTop, panelWidth, movementHeight)
			staminaPanel = PanelBounds(x, movementPanel.bottom + layout.panelGap, panelWidth, staminaHeight)
			foodPanel = PanelBounds(x, staminaPanel.bottom + layout.panelGap, panelWidth, foodHeight)
			penaltyPanel = PanelBounds(x, foodPanel.bottom + layout.panelGap, panelWidth, penaltyHeight)
		}

		clampScrollOffset()
	}

	private fun panelHeight(rowCount: Int): Int {
		val totalRowGap = (rowCount - 1).coerceAtLeast(0) * layout.rowGap
		return layout.panelHeaderHeight + layout.panelInnerPadding + rowCount * layout.rowHeight + totalRowGap + layout.panelInnerPadding
	}

	private fun rowLayout(panel: PanelBounds, rowIndex: Int): RowLayout {
		val controlWidth = ((panel.width * 0.42).toInt()).coerceIn(minimumControlWidth, maximumControlWidth)
		val controlX = panel.right - layout.panelInnerPadding - controlWidth
		val controlY = panel.y + layout.panelHeaderHeight + layout.panelInnerPadding + rowIndex * (layout.rowHeight + layout.rowGap)
		val labelX = panel.x + layout.panelInnerPadding
		val labelMaxWidth = (controlX - labelX - 12).coerceAtLeast(40)
		return RowLayout(
			labelX = labelX,
			labelY = controlY + 6,
			labelMaxWidth = labelMaxWidth,
			controlX = controlX,
			controlY = controlY,
			controlWidth = controlWidth
		)
	}

	private fun createDecimalField(row: RowLayout, value: String): EditBox {
		return EditBox(font, row.controlX, row.controlY, row.controlWidth, layout.rowHeight, Component.empty()).apply {
			setMaxLength(12)
			setValue(value)
			setFilter { text -> text.isEmpty() || text.matches(Regex("\\d*(\\.\\d*)?")) }
		}
	}

	private fun createIntegerField(row: RowLayout, value: String): EditBox {
		return EditBox(font, row.controlX, row.controlY, row.controlWidth, layout.rowHeight, Component.empty()).apply {
			setMaxLength(5)
			setValue(value)
			setFilter { text -> text.isEmpty() || text.matches(Regex("\\d*")) }
		}
	}

	private fun createToggle(row: RowLayout, value: Boolean, narration: String): CycleButton<Boolean> {
		return CycleButton.onOffBuilder(value)
			.displayOnlyValue()
			.create(row.controlX, row.controlY, row.controlWidth, layout.rowHeight, Component.literal(narration))
	}

	private fun <T : AbstractWidget> addScrollableWidget(widget: T, panel: PanelSection, rowIndex: Int): T {
		scrollableWidgets.add(ScrollableWidget(widget, panel, rowIndex))
		return addRenderableWidget(widget)
	}

	private fun <T : AbstractWidget> addFooterWidget(widget: T): T {
		footerWidgets.add(widget)
		return addRenderableWidget(widget)
	}

	private fun renderScrollableContent(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
		val top = contentViewportTop()
		val bottom = contentViewportBottom().coerceAtLeast(top + 1)
		guiGraphics.enableScissor(0, top, width, bottom)
		drawPanel(guiGraphics, movementPanel, Component.literal("Movement"), scrollOffset)
		drawPanel(guiGraphics, staminaPanel, Component.literal("Stamina"), scrollOffset)
		drawPanel(guiGraphics, foodPanel, Component.literal("Food"), scrollOffset)
		drawPanel(guiGraphics, penaltyPanel, Component.literal("Penalty"), scrollOffset)
		drawRowLabel(guiGraphics, movementPanel, 0, Component.literal("Sprint speed (blocks/s)"), scrollOffset)
		drawRowLabel(guiGraphics, staminaPanel, 0, Component.literal("Sprint duration (sec)"), scrollOffset)
		drawRowLabel(guiGraphics, staminaPanel, 1, Component.literal("Recharge time (sec)"), scrollOffset)
		drawRowLabel(guiGraphics, staminaPanel, 2, Component.literal("Mining drain"), scrollOffset)
		drawRowLabel(guiGraphics, staminaPanel, 3, Component.literal("Attack drain"), scrollOffset)
		drawRowLabel(guiGraphics, staminaPanel, 4, Component.literal("Attack cost"), scrollOffset)
		drawRowLabel(guiGraphics, foodPanel, 0, Component.literal("HyFood mode"), scrollOffset)
		drawRowLabel(guiGraphics, foodPanel, 1, Component.literal("Healing %"), scrollOffset)
		drawRowLabel(guiGraphics, foodPanel, 2, Component.literal("Heal speed %"), scrollOffset)
		drawRowLabel(guiGraphics, penaltyPanel, 0, Component.literal("Full recharge after empty"), scrollOffset)
		for (entry in scrollableWidgets) {
			if (entry.widget.visible) {
				entry.widget.render(guiGraphics, mouseX, mouseY, partialTick)
			}
		}
		guiGraphics.disableScissor()
		drawScrollBar(guiGraphics)
	}

	private fun drawPanel(guiGraphics: GuiGraphics, panel: PanelBounds, title: Component, offsetY: Int) {
		val translatedPanel = panel.translate(-offsetY)
		guiGraphics.fill(translatedPanel.x, translatedPanel.y, translatedPanel.right, translatedPanel.bottom, panelFillColor)
		guiGraphics.fill(translatedPanel.x, translatedPanel.y, translatedPanel.right, translatedPanel.y + layout.panelHeaderHeight, panelHeaderColor)
		drawOutline(guiGraphics, translatedPanel.x, translatedPanel.y, translatedPanel.right, translatedPanel.bottom, panelBorderColor)
		guiGraphics.fill(
			translatedPanel.x + layout.panelInnerPadding,
			translatedPanel.y + layout.panelHeaderHeight,
			translatedPanel.right - layout.panelInnerPadding,
			translatedPanel.y + layout.panelHeaderHeight + 1,
			dividerColor
		)
		guiGraphics.drawString(font, title, translatedPanel.x + layout.panelInnerPadding, translatedPanel.y + 5, sectionTitleColor)
	}

	private fun drawRowLabel(guiGraphics: GuiGraphics, panel: PanelBounds, rowIndex: Int, text: Component, offsetY: Int) {
		val row = rowLayout(panel, rowIndex)
		guiGraphics.drawString(font, fitLabelToWidth(text, row.labelMaxWidth), row.labelX, row.labelY - offsetY, textColor)
	}

	private fun drawScrollBar(guiGraphics: GuiGraphics) {
		val maxScroll = maxScrollOffset()
		if (maxScroll <= 0) {
			return
		}

		val top = contentViewportTop()
		val bottom = contentViewportBottom()
		val viewportHeight = (bottom - top).coerceAtLeast(1)
		val contentHeight = (contentBottom() - layout.contentTop).coerceAtLeast(viewportHeight)
		val trackLeft = width - layout.screenPadding - scrollBarWidth
		val trackRight = trackLeft + scrollBarWidth
		val thumbHeight = ((viewportHeight.toDouble() / contentHeight.toDouble()) * viewportHeight).roundToInt().coerceAtLeast(24)
		val travel = (viewportHeight - thumbHeight).coerceAtLeast(0)
		val thumbTop = top + ((travel.toDouble() * scrollOffset.toDouble()) / maxScroll.toDouble()).roundToInt()

		guiGraphics.fill(trackLeft, top, trackRight, bottom, scrollTrackColor)
		guiGraphics.fill(trackLeft, thumbTop, trackRight, thumbTop + thumbHeight, scrollThumbColor)
	}

	private fun updateScrollableWidgetPositions() {
		val viewportTop = contentViewportTop()
		val viewportBottom = contentViewportBottom()
		for (entry in scrollableWidgets) {
			val row = rowLayout(panelFor(entry.panel), entry.rowIndex)
			val widgetY = row.controlY - scrollOffset
			entry.widget.setX(row.controlX)
			entry.widget.setY(widgetY)
			val isVisible = widgetY + layout.rowHeight > viewportTop && widgetY < viewportBottom
			entry.widget.visible = isVisible
			entry.widget.active = isVisible
			if (!isVisible && entry.widget.isFocused) {
				entry.widget.setFocused(false)
			}
		}
	}

	private fun panelFor(panelSection: PanelSection): PanelBounds {
		return when (panelSection) {
			PanelSection.MOVEMENT -> movementPanel
			PanelSection.STAMINA -> staminaPanel
			PanelSection.FOOD -> foodPanel
			PanelSection.PENALTY -> penaltyPanel
		}
	}

	private fun clampScrollOffset() {
		scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset())
	}

	private fun contentViewportTop(): Int {
		return layout.contentTop
	}

	private fun contentViewportBottom(): Int {
		val buttonTop = height - layout.screenPadding - layout.buttonHeight
		val statusTop = buttonTop - layout.footerGap - layout.statusHeight
		return (statusTop - layout.footerGap).coerceAtLeast(contentViewportTop() + 1)
	}

	private fun contentBottom(): Int {
		return maxOf(movementPanel.bottom, staminaPanel.bottom, foodPanel.bottom, penaltyPanel.bottom)
	}

	private fun maxScrollOffset(): Int {
		return (contentBottom() - contentViewportBottom()).coerceAtLeast(0)
	}

	private fun drawStatusBanner(guiGraphics: GuiGraphics) {
		val message: Component = validationMessage ?: if (serverOverrideActive) SERVER_OVERRIDE_MESSAGE else return
		val bannerColor = if (validationMessage != null) errorColor else infoColor
		val bannerFill = if (validationMessage != null) errorFillColor else infoFillColor
		val top = height - layout.screenPadding - layout.buttonHeight - layout.footerGap - layout.statusHeight
		val left = layout.screenPadding
		val right = width - layout.screenPadding

		guiGraphics.fill(left, top, right, top + layout.statusHeight, bannerFill)
		drawOutline(guiGraphics, left, top, right, top + layout.statusHeight, bannerColor)
		guiGraphics.drawCenteredString(font, message, width / 2, top + 5, if (validationMessage != null) errorColor else mutedTextColor)
	}

	private fun drawOutline(guiGraphics: GuiGraphics, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
		guiGraphics.fill(left, top, right, top + 1, color)
		guiGraphics.fill(left, bottom - 1, right, bottom, color)
		guiGraphics.fill(left, top, left + 1, bottom, color)
		guiGraphics.fill(right - 1, top, right, bottom, color)
	}

	private fun fitLabelToWidth(text: Component, maxWidth: Int): Component {
		if (font.width(text) <= maxWidth) {
			return text
		}

		val ellipsis = "..."
		val ellipsisWidth = font.width(ellipsis)
		val clippedText = font.plainSubstrByWidth(text.string, (maxWidth - ellipsisWidth).coerceAtLeast(0))
		return Component.literal(clippedText + ellipsis)
	}

	private fun saveAndClose() {
		val sprintSpeed = sprintSpeedField.getValue().toDoubleOrNull()
		val sprintDuration = sprintDurationField.getValue().toDoubleOrNull()
		val rechargeDuration = rechargeDurationField.getValue().toDoubleOrNull()
		val attackStaminaCost = attackStaminaCostField.getValue().toIntOrNull()
		val foodHealingPercent = foodHealingPercentField.getValue().toDoubleOrNull()
		val foodHealSpeedPercent = foodHealSpeedPercentField.getValue().toDoubleOrNull()

		if (sprintSpeed == null || sprintSpeed <= 0.0) {
			validationMessage = Component.literal("Sprint speed must be a number above 0.")
			return
		}

		if (sprintDuration == null || sprintDuration <= 0.0) {
			validationMessage = Component.literal("Sprint duration must be a number above 0.")
			return
		}

		if (rechargeDuration == null || rechargeDuration <= 0.0) {
			validationMessage = Component.literal("Recharge duration must be a number above 0.")
			return
		}

		if (attackStaminaCost == null || attackStaminaCost < 0) {
			validationMessage = Component.literal("Attack stamina cost must be a whole number at or above 0.")
			return
		}

		if (foodHealingPercent == null || foodHealingPercent < 0.0) {
			validationMessage = Component.literal("Healing % must be a number at or above 0.")
			return
		}

		if (foodHealSpeedPercent == null || foodHealSpeedPercent < 0.0) {
			validationMessage = Component.literal("Heal speed % must be a number at or above 0.")
			return
		}

		validationMessage = null
		val updatedConfig = HyStaminaConfig.ConfigData(
			movement = HyStaminaConfig.MovementSettings(sprintBlocksPerSecond = sprintSpeed),
			stamina = HyStaminaConfig.StaminaSettings(
				secondsUntilEmpty = sprintDuration,
				secondsToFullRecharge = rechargeDuration,
				miningDepletesStamina = miningDepletesStaminaToggle.value,
				attackingDepletesStamina = attackingDepletesStaminaToggle.value,
				attackStaminaCost = attackStaminaCost
			),
			food = HyStaminaConfig.FoodSettings(
				hyFood = hyFoodToggle.value,
				healingPercent = foodHealingPercent,
				healSpeedPercent = foodHealSpeedPercent
			),
			penalty = HyStaminaConfig.PenaltySettings(enabled = penaltyToggle.value)
		)

		HyStaminaConfig.save(updatedConfig)

		if (minecraft?.hasSingleplayerServer() == true) {
			HyStaminaConfig.applyServerOverride(updatedConfig)
		}

		onClose()
	}

	companion object {
		private val TITLE: Component = Component.literal("HyStamina Config")
		private val SERVER_OVERRIDE_MESSAGE: Component = Component.literal("Server config overrides these values while connected.")
	}

	private data class PanelBounds(
		val x: Int = 0,
		val y: Int = 0,
		val width: Int = 0,
		val height: Int = 0
	) {
		val right: Int
			get() = x + width

		val bottom: Int
			get() = y + height

		fun translate(deltaY: Int): PanelBounds {
			return PanelBounds(x = x, y = y + deltaY, width = width, height = height)
		}
	}

	private data class RowLayout(
		val labelX: Int,
		val labelY: Int,
		val labelMaxWidth: Int,
		val controlX: Int,
		val controlY: Int,
		val controlWidth: Int
	)

	private data class ScrollableWidget(
		val widget: AbstractWidget,
		val panel: PanelSection,
		val rowIndex: Int
	)

	private enum class PanelSection {
		MOVEMENT,
		STAMINA,
		FOOD,
		PENALTY
	}

	private data class LayoutMetrics(
		val screenPadding: Int,
		val panelGap: Int,
		val panelInnerPadding: Int,
		val panelHeaderHeight: Int,
		val rowHeight: Int,
		val rowGap: Int,
		val buttonHeight: Int,
		val footerGap: Int,
		val statusHeight: Int,
		val contentTop: Int,
		val titleY: Int
	) {
		companion object {
			fun regular(): LayoutMetrics {
				return LayoutMetrics(
					screenPadding = 20,
					panelGap = 10,
					panelInnerPadding = 10,
					panelHeaderHeight = 18,
					rowHeight = 20,
					rowGap = 6,
					buttonHeight = 20,
					footerGap = 10,
					statusHeight = 18,
					contentTop = 36,
					titleY = 20
				)
			}

			fun compact(): LayoutMetrics {
				return LayoutMetrics(
					screenPadding = 12,
					panelGap = 6,
					panelInnerPadding = 6,
					panelHeaderHeight = 16,
					rowHeight = 18,
					rowGap = 2,
					buttonHeight = 20,
					footerGap = 6,
					statusHeight = 14,
					contentTop = 32,
					titleY = 16
				)
			}

			fun forScreenHeight(height: Int): LayoutMetrics {
				return if (height < 350) compact() else regular()
			}
		}
	}
}