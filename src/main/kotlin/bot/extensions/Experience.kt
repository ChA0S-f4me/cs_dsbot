package bot.extensions

import bot.database.user.User
import bot.lib.Database
import bot.lib.Utils
import bot.readConfig
import com.kotlindiscord.kord.extensions.checks.isNotBot
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.converters.impl.optionalMember
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.i18n.TranslationsProvider
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.selfMember
import dev.kord.common.Color
import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.behavior.GuildBehavior
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.entity.Member
import dev.kord.core.entity.VoiceState
import dev.kord.core.entity.channel.TextChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.MessageDeleteEvent
import dev.kord.core.event.message.MessageUpdateEvent
import dev.kord.core.event.user.VoiceStateUpdateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.koin.core.component.inject
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toDuration

class Experience : Extension() {
	override val name = "experience"

	private val translationsProvider: TranslationsProvider by inject()

	override suspend fun setup() {
		val kord = kord

		// Experience from message
		event<MessageCreateEvent> {
			check {
				isNotBot()

				val ignored = readConfig().experience.ignore.map(::Snowflake)

				failIf(
					ignored.contains(event.message.channelId) ||
						ignored.contains((event.message.getChannel() as TextChannel).categoryId)
				)

				failIf(event.message.content == "+rep")
				failIf(event.message.content == "-rep")
				readConfig().commandPrefixes.forEach {
					failIf(event.message.content.startsWith(it))
				}
			}

			action {
				if (event.member!! == event.getGuild()?.selfMember())
					return@action

				val userId = event.member?.asUser()!!.id.value.toLong()
				val content = event.message.content
					.replace(Regex("<a?:.+:\\d{18}>"), "E") // Emojis
					.replace(Regex("<(@[!|&]?|#).{18}>"), "M") // Mentions/Channels
					.replace(Regex("\\s+"), "") // Spaces
					.replace("???", "") // Emojis with spec symbol
					.replace(Regex(
						"((`){1,3}|(\\*){1,3}|(~){2}|(\\|){2}|^(>){1,3}|(_){1,2})+",
						RegexOption.MULTILINE
					), "") // Markdown

				val count = (content.length * readConfig().experience.perCharacter).roundToInt().toShort()

				val xpEvent = Database.addExperience(
					bot, userId, count, event.message.timestamp
				)

				if (xpEvent.needUpdate) {
					event.message.channel.createEmbed {
						title = translate("extensions.experience.newLevel")
						description = translate(
							"extensions.experience.reachedLevel",
							arrayOf(event.member!!.mention, xpEvent.newLevel)
						)
					}
				}
			}
		}

		suspend fun removeMember(member: Member) {
			val seconds = (Clock.System.now() - joinedMembers[member]!!)
				.toLong(DurationUnit.SECONDS)

			Database.addSeconds(member.id, seconds)
			joinedMembers.remove(member)
		}

		fun getValidStates(voiceStates: Flow<VoiceState>): Flow<VoiceState> {
			return voiceStates
				.filter { !it.getMember().isBot }
				.filterNot { it.isMuted || it.isSelfMuted }
				.filterNot { it.isDeafened || it.isSelfDeafened }
		}

		// Member entered channel
		event<VoiceStateUpdateEvent> {
			check {
				failIf(event.state.getMember().isBot)
				failIf(event.old?.channelId != null)
				failIf(joinedMembers.contains(event.state.getMember()))

				failIf(event.state.isMuted || event.state.isSelfMuted)
				failIf(event.state.isDeafened || event.state.isSelfDeafened)
			}

			action {
				val voiceStates = event.state.getChannelOrNull()?.voiceStates

				if (
					voiceStates != null && getValidStates(voiceStates).count() < 2
				) return@action

				joinedMembers[event.state.getMember()] = Clock.System.now()
			}
		}

		// Member left channel
		event<VoiceStateUpdateEvent> {
			check {
				failIf(event.state.getMember().isBot)
				failIf(event.state.channelId != null)
				failIfNot(joinedMembers.contains(event.state.getMember()))

				failIf(event.state.isMuted || event.state.isSelfMuted)
				failIf(event.state.isDeafened || event.state.isSelfDeafened)
			}

			action {
				removeMember(event.state.getMember())

				val states = event.old?.getChannelOrNull()?.voiceStates
					?: return@action

				if (
					getValidStates(states).count() == 1
				) {
					removeMember(event.old?.getChannelOrNull()?.voiceStates?.first()?.getMember() ?: return@action)
				}
			}
		}

		// Mute/deafen filter
		event<VoiceStateUpdateEvent> {
			check {
				failIf(event.state.getMember().isBot)
				failIf(event.state.channelId != event.old?.channelId)
			}

			action {
				val member = event.state.getMember()

				if (
					!event.state.isSelfMuted &&
					!event.state.isMuted &&
					!event.state.isDeafened &&
					!event.state.isSelfDeafened
				) {
					if (!joinedMembers.contains(member))
						joinedMembers[event.state.getMember()] = Clock.System.now()
				}
				else {
					if (joinedMembers.contains(member))
						removeMember(member)
				}
			}
		}

		publicSlashCommand(::RankArgs) {
			name = "extensions.experience.commandName"
			description = "extensions.experience.commandDescription"

			action {
				val target = arguments.target ?: member!!.asMember()
				val data = Database.getUser(target.id)
				val nextLevelXp = Utils.xpForLevel(data.level + 1)

				val expSymbol = xpSymbol(translationsProvider, data.useEmoji)
				val voiceSymbol = voiceSymbol(translationsProvider, data.useEmoji)
				val ratingSymbol = ratingSymbol(translationsProvider, data.useEmoji)

				respond {
					embed {
						title = translate("extensions.experience.stats", arrayOf(target.asUser().username))
						description = """
							**$expSymbol** ${data.experience}/$nextLevelXp (${data.level})
							**$ratingSymbol** ${data.socialRating}
						""".trimIndent()

						if (data.voiceTime > 0L) {
							val dura = data.voiceTime.toDuration(DurationUnit.SECONDS)
							description += "\n$voiceSymbol $dura"
						}

						color = if (Database.hasSpecialAccess(kord, event.interaction.user.id))
							Color(0x674EA7) else Color(0x3D85C6)

						thumbnail { url = (target.avatar ?: target.defaultAvatar).url }
					}
				}
			}
		}

		publicSlashCommand {
			name = "extensions.experience.top.commandName"
			description = "extensions.experience.top.commandDescription"

			action {
				val users = Database.getTopUsers(10)
				val useEmoji = Database.useEmoji(event.interaction.user.id)

				respond {
					embeds += topEmbed(kord, translationsProvider, users, guild!!, useEmoji)
				}
			}
		}
	}

	inner class RankArgs : Arguments() {
		val target by optionalMember {
			name = "target"
			description = translationsProvider.translate("extensions.experience.arguments.target")
		}
	}

	companion object {
		val joinedMembers = mutableMapOf<Member, Instant>()

		suspend fun topEmbed(
			kord: Kord,
			translationsProvider: TranslationsProvider,
			users: List<User>,
			guild: GuildBehavior,
			useEmoji: Boolean
		): EmbedBuilder {
			val expSymb = xpSymbol(translationsProvider, useEmoji)
			val voiceSymb = voiceSymbol(translationsProvider, useEmoji)
			val ratingSymbol = ratingSymbol(translationsProvider, useEmoji)

			return EmbedBuilder().apply {
				title = translationsProvider.translate("extensions.experience.top.embed.title")

				users
					.filter { it.experience > 0 }
					.forEach { user ->
						val index = users.indexOf(user) + 1

						field {
							name = when (index) {
								1 -> "???? "
								2 -> "???? "
								3 -> "???? "
								else -> ""
							}

							val member = guild.getMemberOrNull(Snowflake(user.id.value))
								?: kord.getUser(Snowflake(user.id.value))

							val nick = (member as? Member)?.nickname ?: member?.username ?: return@forEach

							name += "$index. $nick"
							value = "**$expSymb** ${user.experience} (${user.level}) | **$ratingSymbol** ${user.socialRating}"

							if (user.voiceTime != 0L) {
								val time = user.voiceTime.seconds

								value += " | **$voiceSymb** $time"
							}

							inline = false
						}
				}
			}
		}

		private fun xpSymbol(translationsProvider: TranslationsProvider, useEmoji: Boolean) = if (useEmoji)
			"????" else translationsProvider.translate("extensions.experience.top.embed.symbols.experience")

		private fun voiceSymbol(translationsProvider: TranslationsProvider, useEmoji: Boolean) = if (useEmoji)
			"????" else translationsProvider.translate("extensions.experience.top.embed.symbols.voice")

		private fun ratingSymbol(translationsProvider: TranslationsProvider, useEmoji: Boolean) = if (useEmoji)
			"????" else translationsProvider.translate("extensions.experience.top.embed.symbols.rating")
	}
}
