package bot.extensions

import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.publicSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.extensions.publicSlashCommand
import com.kotlindiscord.kord.extensions.i18n.TranslationsProvider
import com.kotlindiscord.kord.extensions.modules.time.time4j.toHuman
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.selfMember
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.FunctionalResultHandler
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers
import com.sedmelluq.discord.lavaplayer.tools.Units
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.kord.common.annotation.KordVoice
import dev.kord.core.behavior.channel.BaseVoiceChannelBehavior
import dev.kord.core.behavior.channel.connect
import dev.kord.core.behavior.edit
import dev.kord.core.event.user.VoiceStateUpdateEvent
import dev.kord.core.kordLogger
import dev.kord.rest.builder.message.create.embed
import dev.kord.voice.AudioFrame
import kotlinx.coroutines.flow.count
import net.time4j.ClockUnit
import net.time4j.Duration
import net.time4j.IsoUnit
import org.koin.core.component.inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@KordVoice
class Music : Extension() {
	override val name = "music"
	private val translationsProvider: TranslationsProvider by inject()

	override suspend fun setup() {

		val manager = DefaultAudioPlayerManager()
		val player = manager.createPlayer()

		AudioSourceManagers.registerLocalSource(manager)
		AudioSourceManagers.registerRemoteSources(manager)

		suspend fun startPlaying(channel: BaseVoiceChannelBehavior, trackString: String): AudioTrack {
			val track = suspendCoroutine<AudioTrack> { track ->
				manager.loadItem(trackString, FunctionalResultHandler(
					{ track.resume(it) },
					{ track.resume(it.tracks.first()) },
					{ }, { }
				))
			}
			player.startTrack(track, false)

			if (channel.guild.selfMember().getVoiceStateOrNull()?.channelId == null) {
				channel.connect {
					audioProvider { AudioFrame.fromData(player.provide()?.data) }

					selfDeaf = true
				}
			}

			return track
		}

		publicSlashCommand {
			name = "music"
			description = "Manage music"

			publicSubCommand(::PlayArgs) {
				name = "play"
				description = "Start playin"

				action {
					val vc = member?.getVoiceStateOrNull()?.getChannelOrNull()
					if (vc == null) {
						respond { content = translate("extensions.music.errors.notInVC") }
						return@action
					}

					val track = startPlaying(vc, arguments.track)
					val len = if (track.info.length == Units.DURATION_MS_UNKNOWN)
						"*Stream*"
					else
							Duration.of<IsoUnit>(track.duration, ClockUnit.MILLIS).toHuman(this)

					respond {
						embed {
							title = translate("extensions.music.play.embed.title")
							description = translate(
								"extensions.music.play.embed.description",
								arrayOf(track.info.title, track.info.uri, track.info.author, len)
							)
						}
					}
				}
			}

			publicSubCommand {
				name = "chill"
				description = "Start chill radio"

				action {
					val vc = member?.getVoiceStateOrNull()?.getChannelOrNull()
					if (vc == null) {
						respond { content = translate("extensions.music.errors.notInVC") }
						return@action
					}

					startPlaying(vc, "https://www.youtube.com/watch?v=ceqgwo7U28Y")

					respond {
						embed {
							title = translate("extensions.music.play.embed.title")
							description = "*${translate("extensions.music.chill.embed.description")}*"
						}
					}
				}
			}

			publicSubCommand {
				name = "stop"
				description = "Stop playing"

				action {
					player.stopTrack()

					respond { content = translate("extensions.music.stop.text") }
				}
			}

			publicSubCommand {
				name = "disconnect"
				description = "Disconnect from VC"

				action {
					player.stopTrack()
					guild!!.selfMember().edit { voiceChannelId = null }

					respond { content = translate("extensions.music.disconnect.text") }
				}
			}

			publicSubCommand {
				name = "pause"
				description = "Pause/Resume playing"

				action {
					player.isPaused = !player.isPaused

					val text = if (player.isPaused)
						"extensions.music.pause.resumed"
					else
						"extensions.music.pause.paused"

					respond { content = translate(text) }
				}
			}
		}

		event<VoiceStateUpdateEvent> {
			action {
				val guild = event.old?.getGuild()
				val self = guild?.selfMember()
				val selfChannel = self?.getVoiceStateOrNull()?.getChannelOrNull()

				if (
					event.old?.channelId == selfChannel?.id &&
					selfChannel?.voiceStates?.count() == 1
				) {
					self.edit { voiceChannelId = null }
				}
			}
		}
	}

	inner class PlayArgs : Arguments() {
		val track by string {
			name = "track"
			description = "Track (or ytsearch:[Query])"
		}
	}
}