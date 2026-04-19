package hystamina

import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.util.Optional

object HyStaminaDeathCommands {
	fun initialize() {
		CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
			registerCommands(dispatcher)
		})
	}

	private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			Commands.literal("death")
				.then(
					Commands.literal("clear")
						.executes { context ->
							clearDeathMarker(context.source.playerOrException)
						}
				)
		)
	}

	private fun clearDeathMarker(player: net.minecraft.server.level.ServerPlayer): Int {
		if (player.lastDeathLocation.isEmpty) {
			player.sendSystemMessage(Component.literal("You do not have a death marker to clear."))
			return 0
		}

		player.setLastDeathLocation(Optional.empty())
		HyStaminaNetworking.refreshCompassDeathTarget(player)
		player.sendSystemMessage(Component.literal("Cleared your death marker."))
		return 1
	}
}