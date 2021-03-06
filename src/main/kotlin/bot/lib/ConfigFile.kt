package bot.lib

import kotlinx.serialization.Serializable

@Serializable
data class ConfigFile(
	val token: String = "",
	val guildId: String = "",
	val commandPrefixes: List<String> = listOf(),
	val statsReportChannelId: String = "",
	val memesChannelId: String = "",
	val memeScore: Int = 10,
	val experience: Experience = Experience(),
	val database: ConfigDatabase = ConfigDatabase()
)

@Serializable
data class Experience(
	val perCharacter: Float = .5f,
	val perSecond: Float = .05f,
	val ignore: List<Long> = listOf()
)

@Serializable
data class ConfigDatabase(
	val url: String = "localhost:5432",
	val username: String = "postgres",
	val password: String = "",
	val database: String = "coders_squad"
)