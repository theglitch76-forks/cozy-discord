package org.quiltmc.community.modes.quilt.extensions.filtering

import com.github.curiousoddman.rgxgen.RgxGen
import com.github.curiousoddman.rgxgen.config.RgxGenOption
import com.github.curiousoddman.rgxgen.config.RgxGenProperties
import com.kotlindiscord.kord.extensions.*
import com.kotlindiscord.kord.extensions.checks.isNotBot
import com.kotlindiscord.kord.extensions.commands.Arguments
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.enumChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.converters.impl.optionalEnumChoice
import com.kotlindiscord.kord.extensions.commands.application.slash.ephemeralSubCommand
import com.kotlindiscord.kord.extensions.commands.converters.impl.defaultingBoolean
import com.kotlindiscord.kord.extensions.commands.converters.impl.string
import com.kotlindiscord.kord.extensions.extensions.Extension
import com.kotlindiscord.kord.extensions.extensions.ephemeralSlashCommand
import com.kotlindiscord.kord.extensions.extensions.event
import com.kotlindiscord.kord.extensions.types.editingPaginator
import com.kotlindiscord.kord.extensions.types.respond
import com.kotlindiscord.kord.extensions.utils.deleteIgnoringNotFound
import com.kotlindiscord.kord.extensions.utils.dm
import com.kotlindiscord.kord.extensions.utils.getJumpUrl
import dev.kord.core.behavior.ban
import dev.kord.core.behavior.channel.createEmbed
import dev.kord.core.behavior.channel.createMessage
import dev.kord.core.entity.Guild
import dev.kord.core.entity.Message
import dev.kord.core.entity.channel.GuildMessageChannel
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.event.message.MessageUpdateEvent
import dev.kord.rest.builder.message.EmbedBuilder
import dev.kord.rest.builder.message.create.embed
import kotlinx.coroutines.flow.firstOrNull
import mu.KotlinLogging
import org.koin.core.component.inject
import org.quiltmc.community.*
import org.quiltmc.community.database.collections.FilterCollection
import org.quiltmc.community.database.collections.FilterEventCollection
import org.quiltmc.community.database.entities.FilterEntry
import java.util.*

const val APPEALS_INVITE_CODE = "H32HVWw9Nu"
const val FILTERS_PER_PAGE = 2

class FilterExtension : Extension() {
    override val name: String = "filter"
    private val logger = KotlinLogging.logger { }

    private val rgxProperties = RgxGenProperties()

    init {
        RgxGenOption.INFINITE_PATTERN_REPETITION.setInProperties(rgxProperties, 2)
        RgxGen.setDefaultProperties(rgxProperties)
    }

    val filters: FilterCollection by inject()
    val filterCache: MutableMap<UUID, FilterEntry> = mutableMapOf()
    val filterEvents: FilterEventCollection by inject()

    override suspend fun setup() {
        reloadFilters()

        event<MessageCreateEvent> {
            check { event.message.author != null }
            check { isNotBot() }
            check { inQuiltGuild() }
            check { notHasBaseModeratorRole() }

            action {
                handleMessage(event.message)
            }
        }

        event<MessageUpdateEvent> {
            check { event.message.asMessageOrNull()?.author != null }
            check { isNotBot() }
            check { inQuiltGuild() }
            check { notHasBaseModeratorRole() }

            action {
                handleMessage(event.message.asMessage())
            }
        }

        GUILDS.forEach { guildId ->
            ephemeralSlashCommand {
                name = "filters"
                description = "Filter management commands"

                check { hasBaseModeratorRole() }

                guild(guildId)

                ephemeralSubCommand(::FilterCreateArgs) {
                    name = "create"
                    description = "Create a new filter"

                    action {
                        val filter = FilterEntry(
                            _id = UUID.randomUUID(),

                            action = arguments.action,
                            pingStaff = arguments.ping,

                            match = arguments.match,
                            matchType = arguments.matchType
                        )

                        filters.set(filter)
                        filterCache[filter._id] = filter

                        this@FilterExtension.kord.getGuild(COMMUNITY_GUILD)
                            ?.getCozyLogChannel()
                            ?.createEmbed {
                                color = DISCORD_GREEN
                                title = "Filter created"

                                formatFilter(filter)

                                field {
                                    name = "Moderator"
                                    value = "${user.mention} (`${user.id.value}` / `${user.asUser().tag}`)"
                                }
                            }

                        respond {
                            embed {
                                color = DISCORD_GREEN
                                title = "Filter created"

                                formatFilter(filter)
                            }
                        }
                    }
                }

                ephemeralSubCommand(::FilterIDArgs) {
                    name = "delete"
                    description = "Delete an existing filter"

                    action {
                        val filter = filters.get(UUID.fromString(arguments.uuid))

                        if (filter == null) {
                            respond {
                                content = "No such filter: `${arguments.uuid}`"
                            }

                            return@action
                        }

                        filters.remove(filter)
                        filterCache.remove(filter._id)

                        this@FilterExtension.kord.getGuild(COMMUNITY_GUILD)
                            ?.getCozyLogChannel()
                            ?.createEmbed {
                                title = "Filter deleted"
                                color = DISCORD_RED

                                formatFilter(filter)

                                field {
                                    name = "Moderator"
                                    value = "${user.mention} (`${user.id.value}` / `${user.asUser().tag}`)"
                                }
                            }

                        respond {
                            embed {
                                title = "Filter deleted"
                                color = DISCORD_RED

                                formatFilter(filter)
                            }
                        }
                    }
                }

                ephemeralSubCommand(::FilterIDArgs) {
                    name = "get"
                    description = "Get information about a specific filter"

                    action {
                        val filter = filters.get(UUID.fromString(arguments.uuid))

                        if (filter == null) {
                            respond {
                                content = "No such filter: `${arguments.uuid}`"
                            }

                            return@action
                        }

                        respond {
                            embed {
                                color = DISCORD_BLURPLE
                                title = "Filter info"

                                formatFilter(filter)
                            }
                        }
                    }
                }

                ephemeralSubCommand {
                    name = "list"
                    description = "List all filters"

                    action {
                        val filters = filterCache.values

                        if (filters.isEmpty()) {
                            respond {
                                content = "No filters have been created."
                            }

                            return@action
                        }

                        editingPaginator(locale = getLocale()) {
                            filters
                                .sortedByDescending { it.action?.severity ?: -1 }
                                .chunked(FILTERS_PER_PAGE)
                                .forEach { filters ->
                                    page {
                                        color = DISCORD_BLURPLE
                                        title = "Filters"

                                        filters.forEach { formatFilter(it) }
                                    }
                                }
                        }.send()
                    }
                }

                ephemeralSubCommand {
                    name = "reload"
                    description = "Reload filters from the database"

                    action {
                        reloadFilters()

                        respond {
                            content = "Reloaded ${filterCache.size} filters."
                        }
                    }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun handleMessage(message: Message) {
        val matched = filterCache.values
            .filter { it.matches(message.content) }
            .sortedByDescending { it.action?.severity ?: -1 }

        for (filter in matched) {
            try {
                if (filter.matches(message.content)) {
                    filter.action(message)

                    return
                }
            } catch (t: Throwable) {
                logger.error(t) { "Failed to check filter ${filter._id}" }
            }
        }
    }

    suspend fun FilterEntry.action(message: Message) {
        val guild = message.getGuild()

        when (action) {
            FilterAction.DELETE -> {
                message.author!!.dm {
                    content = "The message you just sent on **${guild.name}** has been automatically removed."

                    embed {
                        description = message.content

                        field {
                            name = "Channel"
                            value = "${message.channel.mention} (`${message.channel.id.value}`)"
                        }

                        field {
                            name = "Message ID"
                            value = "`${message.id.asString}`"
                        }
                    }
                }

                message.deleteIgnoringNotFound()
            }

            FilterAction.KICK -> {
                message.deleteIgnoringNotFound()

                message.author!!.dm {
                    content = "You have been kicked from **${guild.name}** for sending the below message."

                    embed {
                        description = message.content

                        field {
                            name = "Channel"
                            value = "${message.channel.mention} (`${message.channel.id.value}`)"
                        }

                        field {
                            name = "Message ID"
                            value = "`${message.id.asString}`"
                        }
                    }
                }

                message.author!!.asMember(message.getGuild().id).kick("Kicked by filter: $_id")
            }

            FilterAction.BAN -> {
                message.deleteIgnoringNotFound()

                message.author!!.dm {
                    content = "You have been banned from **${guild.name}** for sending the below message.\n\n" +

                            "If you'd like to appeal your ban: https://discord.gg/$APPEALS_INVITE_CODE"

                    embed {
                        description = message.content

                        field {
                            name = "Channel"
                            value = "${message.channel.mention} (`${message.channel.id.value}`)"
                        }

                        field {
                            name = "Message ID"
                            value = "`${message.id.asString}`"
                        }
                    }
                }

                message.author!!.asMember(message.getGuild().id).ban {
                    reason = "Banned by filter: $_id"
                }
            }
        }

        filterEvents.add(
            this, message.getGuild(), message.author!!, message.channel, message
        )

        guild.getCozyLogChannel()?.createMessage {
            if (pingStaff) {
                val modRole = when (guild.id) {
                    COMMUNITY_GUILD -> guild.getRole(COMMUNITY_MODERATOR_ROLE)
                    TOOLCHAIN_GUILD -> guild.getRole(TOOLCHAIN_MODERATOR_ROLE)

                    else -> null
                }

                content = modRole?.mention
                    ?: "**Warning:** This filter shouldn't have triggered on this server! This is a bug!"
            }

            embed {
                color = DISCORD_YELLOW
                title = "Filter triggered!"
                description = message.content

                field {
                    inline = true
                    name = "Author"
                    value = "${message.author!!.mention} (`${message.author!!.id.value}` / `${message.author!!.tag}`)"
                }

                field {
                    inline = true
                    name = "Channel"
                    value = message.channel.mention
                }

                field {
                    inline = true
                    name = "Message"
                    value = "[`${message.id.value}`](${message.getJumpUrl()})"
                }

                field {
                    inline = false
                    name = "Filter ID"
                    value = "`$_id`"
                }

                field {
                    inline = true
                    name = "Action"
                    value = action?.readableName ?: "Log only"
                }

                field {
                    inline = true
                    name = "Match Type"
                    value = matchType.readableName
                }

                field {
                    name = "Match String"
                    value = "```\n" +
                            "$match\n" +
                            "```"
                }
            }
        }
    }

    fun FilterEntry.matches(content: String): Boolean = when (matchType) {
        MatchType.CONTAINS -> content.contains(match, ignoreCase = true)
        MatchType.EXACT -> content.equals(match, ignoreCase = true)
        MatchType.REGEX -> match.toRegex(RegexOption.IGNORE_CASE).matches(content)
        MatchType.REGEX_CONTAINS -> content.contains(match.toRegex(RegexOption.IGNORE_CASE))
    }

    suspend fun Guild.getCozyLogChannel() =
        channels.firstOrNull { it.name == "cozy-logs" }
            ?.asChannelOrNull() as? GuildMessageChannel

    suspend fun reloadFilters() {
        filterCache.clear()

        filters.getAll().forEach {
            filterCache[it._id] = it
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun EmbedBuilder.formatFilter(filter: FilterEntry) {
        if (description == null) {
            description = ""
        }

        description += "__**${filter._id}**__\n\n" +
                "**Action:** ${filter.action?.readableName ?: "Log only"}\n" +
                "**Match type:** ${filter.matchType.readableName}\n" +
                "**Ping staff:** ${if (filter.pingStaff) "Yes" else "No"}\n\n" +

                "__**Match**__\n\n" +
                "```\n" +
                "${filter.match}\n" +
                "```\n"

        if (filter.matchType == MatchType.REGEX || filter.matchType == MatchType.REGEX_CONTAINS) {
            try {
                val generator = RgxGen(filter.match)
                val examples = mutableSetOf<String>()

                repeat(FILTERS_PER_PAGE * 2) {
                    examples.add(generator.generate())
                }

                if (examples.isNotEmpty()) {
                    description += "__**Examples**__\n\n" +

                            "```\n" +
                            examples.joinToString("\n") +
                            "```\n"
                }
            } catch (t: Throwable) {
                logger.error(t) { "Failed to generate examples for regular expression: ${filter.match}" }

                description += "__**Examples**__\n\n" +

                        "**Failed to generate examples: `${t.message}`\n"
            }

            description += "\n"
        }
    }

    @Suppress("TooGenericExceptionCaught")
    inner class FilterIDArgs : Arguments() {
        val uuid by string("uuid", "Filter ID") { _, value ->
            try {
                UUID.fromString(value)
            } catch (t: Throwable) {
                throw DiscordRelayedException("Please provide a valid UUID.")
            }
        }
    }

    inner class FilterCreateArgs : Arguments() {
        val match by string("match", "Text to match on")
        val matchType by enumChoice<MatchType>("match-type", "Type of match", "match type`")

        val action by optionalEnumChoice<FilterAction>("action", "Action to take", "action")
        val ping by defaultingBoolean("ping", "Whether to ping the moderators", false)
    }
}
