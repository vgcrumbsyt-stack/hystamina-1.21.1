package hystamina

import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.EntityArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID

object HyStaminaPartySystem {
	private val partiesById = mutableMapOf<UUID, Party>()
	private val partyIdByMember = mutableMapOf<UUID, UUID>()
	private val pendingInvitesByInvitee = mutableMapOf<UUID, MutableMap<UUID, PartyInvite>>()

	fun initialize() {
		CommandRegistrationCallback.EVENT.register(CommandRegistrationCallback { dispatcher, _, _ ->
			registerCommands(dispatcher)
		})

		ServerPlayConnectionEvents.DISCONNECT.register(ServerPlayConnectionEvents.Disconnect { handler, _ ->
			handleDisconnect(handler.player)
		})
	}

	fun getTrackedMembers(player: ServerPlayer): List<TrackedPartyMember> {
		val party = getParty(player) ?: return emptyList()
		return party.members.asSequence()
			.filter { it != player.uuid }
			.mapNotNull(player.server.playerList::getPlayer)
			.map { member ->
				TrackedPartyMember(
					uuid = member.uuid,
					name = member.gameProfile.name,
					dimensionId = member.level().dimension().location().toString(),
					x = member.blockX,
					y = member.blockY,
					z = member.blockZ
				)
			}
			.sortedWith(compareBy<TrackedPartyMember>({ it.dimensionId }, { it.name.lowercase(Locale.ROOT) }, { it.x }, { it.z }))
			.toList()
	}

	private fun registerCommands(dispatcher: CommandDispatcher<CommandSourceStack>) {
		dispatcher.register(
			Commands.literal("party")
				.executes { context ->
					showPartyStatus(context.source.playerOrException)
				}
				.then(
					Commands.literal("invite")
						.then(
							Commands.argument("player", EntityArgument.player())
								.executes { context ->
									invitePlayer(
										context.source.playerOrException,
										EntityArgument.getPlayer(context, "player")
									)
								}
						)
				)
				.then(
					Commands.literal("accept")
						.then(
							Commands.argument("player", EntityArgument.player())
								.executes { context ->
									acceptInvite(
										context.source.playerOrException,
										EntityArgument.getPlayer(context, "player")
									)
								}
						)
				)
				.then(
					Commands.literal("decline")
						.then(
							Commands.argument("player", EntityArgument.player())
								.executes { context ->
									declineInvite(
										context.source.playerOrException,
										EntityArgument.getPlayer(context, "player")
									)
								}
						)
				)
				.then(
					Commands.literal("kick")
						.then(
							Commands.argument("player", EntityArgument.player())
								.executes { context ->
									kickPlayer(
										context.source.playerOrException,
										EntityArgument.getPlayer(context, "player")
									)
								}
						)
				)
				.then(
					Commands.literal("leave")
						.executes { context ->
							leaveParty(context.source.playerOrException, disconnected = false)
						}
				)
		)
	}

	private fun showPartyStatus(player: ServerPlayer): Int {
		val party = getParty(player)
		if (party == null) {
			val pendingInvites = pendingInvitesByInvitee[player.uuid].orEmpty().keys
			if (pendingInvites.isEmpty()) {
				player.sendSystemMessage(Component.literal("You are not in a party. Use /party invite <player> to start one."))
			} else {
				val inviteSenders = pendingInvites.mapNotNull(player.server.playerList::getPlayer).joinToString(", ") { it.gameProfile.name }
				player.sendSystemMessage(Component.literal("Pending party invites from: $inviteSenders"))
			}
			return 1
		}

		val leaderName = getPlayerName(player.server, party.leaderUuid)
		val memberNames = party.members.joinToString(", ") { memberUuid ->
			val memberName = getPlayerName(player.server, memberUuid)
			if (memberUuid == party.leaderUuid) "$memberName (leader)" else memberName
		}
		player.sendSystemMessage(Component.literal("Party: $memberNames"))
		player.sendSystemMessage(Component.literal("Leader: $leaderName"))
		return 1
	}

	private fun invitePlayer(inviter: ServerPlayer, target: ServerPlayer): Int {
		if (inviter.uuid == target.uuid) {
			inviter.sendSystemMessage(Component.literal("You cannot invite yourself to a party."))
			return 0
		}

		val inviterParty = getOrCreateParty(inviter)
		val targetParty = getParty(target)
		if (targetParty?.id == inviterParty.id) {
			inviter.sendSystemMessage(Component.literal("${target.gameProfile.name} is already in your party."))
			return 0
		}

		if (targetParty != null) {
			inviter.sendSystemMessage(Component.literal("${target.gameProfile.name} is already in another party."))
			return 0
		}

		val inviteMap = pendingInvitesByInvitee.getOrPut(target.uuid) { mutableMapOf() }
		val hadInvite = inviteMap.containsKey(inviter.uuid)
		inviteMap[inviter.uuid] = PartyInvite(inviter.uuid, inviterParty.id)

		inviter.sendSystemMessage(
			Component.literal(
				if (hadInvite) {
					"Resent a party invite to ${target.gameProfile.name}."
				} else {
					"Invited ${target.gameProfile.name} to your party."
				}
			)
		)
		sendInviteMessage(inviter, target)
		return 1
	}

	private fun acceptInvite(invitee: ServerPlayer, inviter: ServerPlayer): Int {
		val invite = pendingInvitesByInvitee[invitee.uuid]?.get(inviter.uuid)
		if (invite == null) {
			invitee.sendSystemMessage(Component.literal("You do not have a party invite from ${inviter.gameProfile.name}."))
			return 0
		}

		val currentParty = getParty(invitee)
		if (currentParty != null) {
			if (currentParty.id == invite.partyId) {
				removeInvite(invitee.uuid, inviter.uuid)
				invitee.sendSystemMessage(Component.literal("You are already in that party."))
				return 0
			}

			invitee.sendSystemMessage(Component.literal("Leave your current party before accepting another invite."))
			return 0
		}

		val party = partiesById[invite.partyId]
		if (party == null || inviter.uuid !in party.members) {
			removeInvite(invitee.uuid, inviter.uuid)
			invitee.sendSystemMessage(Component.literal("That party invite is no longer valid."))
			return 0
		}

		party.members.add(invitee.uuid)
		partyIdByMember[invitee.uuid] = party.id
		pendingInvitesByInvitee.remove(invitee.uuid)

		broadcastToParty(
			invitee.server,
			party,
			Component.literal("${invitee.gameProfile.name} joined the party.")
		)
		return 1
	}

	private fun declineInvite(invitee: ServerPlayer, inviter: ServerPlayer): Int {
		if (!removeInvite(invitee.uuid, inviter.uuid)) {
			invitee.sendSystemMessage(Component.literal("You do not have a party invite from ${inviter.gameProfile.name}."))
			return 0
		}

		invitee.sendSystemMessage(Component.literal("Declined ${inviter.gameProfile.name}'s party invite."))
		inviter.sendSystemMessage(Component.literal("${invitee.gameProfile.name} declined your party invite."))
		return 1
	}

	private fun kickPlayer(actor: ServerPlayer, target: ServerPlayer): Int {
		val party = getParty(actor)
		if (party == null) {
			actor.sendSystemMessage(Component.literal("You are not in a party."))
			return 0
		}

		if (party.leaderUuid != actor.uuid) {
			actor.sendSystemMessage(Component.literal("Only the party leader can kick members."))
			return 0
		}

		if (target.uuid == actor.uuid) {
			actor.sendSystemMessage(Component.literal("Use /party leave if you want to leave the party."))
			return 0
		}

		if (partyIdByMember[target.uuid] != party.id) {
			actor.sendSystemMessage(Component.literal("${target.gameProfile.name} is not in your party."))
			return 0
		}

		removeMemberFromParty(actor.server, party, target.uuid)
		target.sendSystemMessage(Component.literal("You were kicked from the party by ${actor.gameProfile.name}."))
		broadcastToParty(actor.server, party, Component.literal("${target.gameProfile.name} was kicked from the party by ${actor.gameProfile.name}."))
		return 1
	}

	private fun leaveParty(player: ServerPlayer, disconnected: Boolean): Int {
		val party = getParty(player)
		if (party == null) {
			if (!disconnected) {
				player.sendSystemMessage(Component.literal("You are not in a party."))
			}
			clearInvitesFor(player.uuid)
			return 0
		}

		val oldLeader = party.leaderUuid
		removeMemberFromParty(player.server, party, player.uuid)
		clearInvitesFor(player.uuid)

		if (!disconnected) {
			player.sendSystemMessage(Component.literal("You left the party."))
		}

		if (party.members.isEmpty()) {
			partiesById.remove(party.id)
			return 1
		}

		if (oldLeader == player.uuid) {
			val newLeaderName = getPlayerName(player.server, party.leaderUuid)
			broadcastToParty(
				player.server,
				party,
				Component.literal("${player.gameProfile.name} left the party. $newLeaderName is now the leader.")
			)
		} else {
			broadcastToParty(player.server, party, Component.literal("${player.gameProfile.name} left the party."))
		}

		return 1
	}

	private fun handleDisconnect(player: ServerPlayer) {
		leaveParty(player, disconnected = true)
	}

	private fun getOrCreateParty(player: ServerPlayer): Party {
		val existingParty = getParty(player)
		if (existingParty != null) {
			return existingParty
		}

		val members = LinkedHashSet<UUID>()
		members.add(player.uuid)
		val party = Party(
			id = UUID.randomUUID(),
			leaderUuid = player.uuid,
			members = members
		)
		partiesById[party.id] = party
		partyIdByMember[player.uuid] = party.id
		return party
	}

	private fun getParty(player: ServerPlayer): Party? {
		val partyId = partyIdByMember[player.uuid] ?: return null
		return partiesById[partyId]
	}

	private fun removeMemberFromParty(server: net.minecraft.server.MinecraftServer, party: Party, memberUuid: UUID) {
		party.members.remove(memberUuid)
		partyIdByMember.remove(memberUuid)
		if (party.members.isEmpty()) {
			partiesById.remove(party.id)
			return
		}

		if (party.leaderUuid == memberUuid) {
			party.leaderUuid = party.members.first()
		}
	}

	private fun sendInviteMessage(inviter: ServerPlayer, target: ServerPlayer) {
		val acceptCommand = "/party accept ${inviter.gameProfile.name}"
		val declineCommand = "/party decline ${inviter.gameProfile.name}"
		val message = Component.literal("${inviter.gameProfile.name} invited you to a party. ")
			.append(actionButton("[Accept]", ChatFormatting.GREEN, acceptCommand))
			.append(Component.literal(" "))
			.append(actionButton("[Decline]", ChatFormatting.RED, declineCommand))
		target.sendSystemMessage(message)
	}

	private fun actionButton(label: String, color: ChatFormatting, command: String): Component {
		return Component.literal(label).withStyle { style ->
			style
				.withColor(color)
				.withBold(true)
				.withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
		}
	}

	private fun broadcastToParty(server: net.minecraft.server.MinecraftServer, party: Party, message: Component) {
		for (memberUuid in party.members) {
			server.playerList.getPlayer(memberUuid)?.sendSystemMessage(message)
		}
	}

	private fun removeInvite(inviteeUuid: UUID, inviterUuid: UUID): Boolean {
		val invites = pendingInvitesByInvitee[inviteeUuid] ?: return false
		val removed = invites.remove(inviterUuid) != null
		if (invites.isEmpty()) {
			pendingInvitesByInvitee.remove(inviteeUuid)
		}
		return removed
	}

	private fun clearInvitesFor(playerUuid: UUID) {
		pendingInvitesByInvitee.remove(playerUuid)
		val emptyInvitees = ArrayList<UUID>()
		for ((inviteeUuid, invites) in pendingInvitesByInvitee) {
			invites.remove(playerUuid)
			if (invites.isEmpty()) {
				emptyInvitees.add(inviteeUuid)
			}
		}

		for (inviteeUuid in emptyInvitees) {
			pendingInvitesByInvitee.remove(inviteeUuid)
		}
	}

	private fun getPlayerName(server: net.minecraft.server.MinecraftServer, playerUuid: UUID): String {
		return server.playerList.getPlayer(playerUuid)?.gameProfile?.name ?: playerUuid.toString().take(8)
	}

	private data class Party(
		val id: UUID,
		var leaderUuid: UUID,
		val members: LinkedHashSet<UUID>
	)

	private data class PartyInvite(
		val inviterUuid: UUID,
		val partyId: UUID
	)

	data class TrackedPartyMember(
		val uuid: UUID,
		val name: String,
		val dimensionId: String,
		val x: Int,
		val y: Int,
		val z: Int
	)
}