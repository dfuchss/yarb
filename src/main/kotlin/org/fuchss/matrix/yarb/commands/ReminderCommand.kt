package org.fuchss.matrix.yarb.commands

import de.connect2x.trixnity.client.room.message.react
import de.connect2x.trixnity.client.room.message.reply
import de.connect2x.trixnity.client.room.message.text
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.m.RelatesTo
import de.connect2x.trixnity.core.model.events.m.RelationType
import de.connect2x.trixnity.core.model.events.m.room.RedactionEventContent
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import de.connect2x.trixnity.core.model.events.senderOrNull
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.bots.helper.markdown
import org.fuchss.matrix.yarb.Config
import org.fuchss.matrix.yarb.TimerManager
import org.fuchss.matrix.yarb.getMessageId
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class ReminderCommand(
    private val config: Config,
    private val timerManager: TimerManager
) : Command() {
    companion object {
        const val COMMAND_NAME = "new"
        private val TIME_REGEX = Regex("^(0?[0-9]|1[0-9]|2[0-3]):[0-5][0-9]$")

        /**
         * Parse the message of a reminder into the options of a poll.
         *
         * Options are only read from the lines below the command, so the first line (the one that contains the time) never contains an option.
         * A message without such option lines -- or with a line that is not structured like "Emoji: Option" -- is used as a plain reminder message.
         */
        internal fun parseEmojiToMessage(message: String): Map<String, String> {
            val lines = message.lines()
            if (lines.size < 2 || lines.first().isNotBlank()) {
                return mapOf(TimerManager.DEFAULT_REACTION to message.trim())
            }

            val optionLines = lines.drop(1).map { it.trim() }.filter { it.isNotBlank() }
            val options = optionLines.map { it.split(":", limit = 2) }
            if (options.isEmpty() || options.any { it.size != 2 || it[0].isBlank() || it[1].isBlank() }) {
                return mapOf(TimerManager.DEFAULT_REACTION to message.trim())
            }

            return options.associate { it[0].trim() to it[1].trim() }
        }
    }

    override val help: String = "Set a reminder for a specific time."
    override val params: String = "<time|11:30> <message|Time for Lunch!>"
    override val name: String = COMMAND_NAME
    override val autoAcknowledge: Boolean = false

    override suspend fun execute(
        matrixBot: MatrixBot,
        sender: UserId,
        roomId: RoomId,
        parameters: String,
        textEventId: EventId,
        textEvent: RoomMessageEventContent.TextBased.Text
    ) {
        // Handle New Messages
        execute(matrixBot, roomId, parameters, textEventId, textEventId)
    }

    private suspend fun execute(
        matrixBot: MatrixBot,
        roomId: RoomId,
        parameters: String,
        currentMessageEventId: EventId,
        initialMessageEventId: EventId
    ) {
        val (time, message) = extractTimeAndMessage(matrixBot, roomId, parameters) ?: return
        logger.debug("Reminder for {} with '{}'", time, message)
        val emojiToMessage = parseEmojiToMessage(message)

        val botMessageId = sendReminderMessage(matrixBot, roomId, initialMessageEventId, time, emojiToMessage) ?: return
        val botReactionMessageIds = createBotReactions(matrixBot, roomId, botMessageId, emojiToMessage) ?: return

        val timer =
            TimerManager.TimerData.create(
                roomId,
                initialMessageEventId,
                currentMessageEventId,
                time,
                botMessageId,
                botReactionMessageIds.map { it!! },
                emojiToMessage
            )
        timerManager.addTimer(timer)
    }

    private suspend fun createBotReactions(
        matrixBot: MatrixBot,
        roomId: RoomId,
        botMessageId: EventId,
        emojiToMessage: Map<String, String>
    ): List<EventId?>? {
        val botReactionMessageTransactionIds =
            emojiToMessage.map {
                matrixBot.room().sendMessage(roomId) {
                    react(botMessageId, it.key)
                }
            }

        val botReactionMessageIds = botReactionMessageTransactionIds.map { matrixBot.room().getMessageId(roomId, it) }
        if (botReactionMessageIds.any { it == null }) {
            logger.error("Could not send bot reaction message :( -- TransactionIds: {}", botReactionMessageTransactionIds)
            matrixBot.roomApi().redactEvent(roomId, botMessageId).getOrNull()
            matrixBot.room().sendMessage(roomId) {
                text("Run into server rate limits. Please use less emojis :/")
            }
            return null
        }
        return botReactionMessageIds
    }

    private suspend fun sendReminderMessage(
        matrixBot: MatrixBot,
        roomId: RoomId,
        initialMessageEventId: EventId,
        time: LocalTime,
        emojiToMessage: Map<String, String>
    ): EventId? {
        val botMessageTransactionId =
            matrixBot.room().sendMessage(roomId) {
                reply(initialMessageEventId, null)
                val message = createReminderMessage(time, emojiToMessage)
                markdown(message)
            }

        val botMessageId = matrixBot.room().getMessageId(roomId, botMessageTransactionId)
        if (botMessageId == null) {
            logger.error("Could not send bot message :( -- TransactionId: {}", botMessageTransactionId)
        }
        return botMessageId
    }

    private suspend fun extractTimeAndMessage(
        matrixBot: MatrixBot,
        roomId: RoomId,
        parameters: String
    ): Pair<LocalTime, String>? {
        val timeText = parameters.takeWhile { !it.isWhitespace() }
        if (!TIME_REGEX.matches(timeText)) {
            matrixBot.room().sendMessage(roomId) { text("Invalid time format. Please use commands like '!${config.prefix} 09:00 Time to Work!'") }
            return null
        }

        val formatter = DateTimeFormatter.ofPattern("H:mm")
        val time = LocalTime.parse(timeText, formatter).withSecond(0).minusMinutes(config.offsetInMinutes)
        val now = LocalTime.now()
        if (now.isAfter(time)) {
            matrixBot
                .room()
                .sendMessage(roomId) {
                    text(
                        "Time $time is in the past. I can only remind you at the same day :) Also remember that I'll inform you ${config.offsetInMinutes} min before :)"
                    )
                }
            return null
        }

        // Keep the line structure of the message. Only the lines below the command may contain the options of a poll.
        var message = parameters.drop(timeText.length).removePrefix(" ")
        if (message.isBlank()) {
            if (config.defaultMessage.isNullOrBlank()) {
                matrixBot.room().sendMessage(roomId) { text("Message not found. Please use commands like '!${config.prefix} 09:00 Time to Work!'") }
                return null
            } else {
                message = config.defaultMessage
            }
        }

        return time to message
    }

    private fun createReminderMessage(
        time: LocalTime,
        emojiToMessage: Map<String, String>
    ): String {
        if (emojiToMessage.size == 1) {
            return "I'll remind all people that react '${emojiToMessage.keys.first()}' at $time: '${emojiToMessage.values.first()}'"
        }

        val header = "I'll remind all people at $time.\n\n"
        val options = emojiToMessage.map { (emoji, message) -> "* Use '$emoji': $message" }.joinToString("\n")
        return header + options
    }

    suspend fun handleUserDeleteMessage(
        matrixBot: MatrixBot,
        event: ClientEvent<RedactionEventContent>
    ) {
        if (event.senderOrNull == matrixBot.self()) {
            return
        }
        val timer = timerManager.removeByOriginalRequestMessage(event.content.redacts) ?: return
        timer.redactAll(matrixBot)
    }

    suspend fun handleUserEditMessage(
        matrixBot: MatrixBot,
        eventId: EventId,
        senderId: UserId,
        roomId: RoomId,
        textEvent: RoomMessageEventContent.TextBased.Text
    ) {
        logger.debug("Edit Message: {}", textEvent)
        val relatesTo = textEvent.relatesTo ?: return

        if (relatesTo.relationType != RelationType.Replace) {
            return
        }

        val timer = this.timerManager.removeByOriginalRequestMessage(relatesTo.eventId) ?: return
        timer.redactAll(matrixBot)

        val replace = (textEvent.relatesTo as? RelatesTo.Replace) ?: return
        val newBody = (replace.newContent as? RoomMessageEventContent.TextBased.Text)?.body ?: return

        var parameters = newBody.substring("!${config.prefix}".length).trim()
        if (parameters.startsWith(COMMAND_NAME)) {
            parameters = parameters.substring(COMMAND_NAME.length).trim()
        }
        execute(matrixBot, roomId, parameters, eventId, relatesTo.eventId)
    }
}
