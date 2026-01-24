package org.fuchss.matrix.yarb

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import de.connect2x.trixnity.client.room.getTimelineEventReactionAggregation
import de.connect2x.trixnity.client.room.message.mentions
import de.connect2x.trixnity.client.room.message.reply
import de.connect2x.trixnity.client.store.sender
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import de.connect2x.trixnity.core.model.UserId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.emoji
import org.fuchss.matrix.bots.markdown
import org.fuchss.matrix.bots.matrixTo
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalTime
import java.util.Timer
import java.util.TimerTask
import kotlin.time.Duration.Companion.seconds

class TimerManager(
    private val matrixBot: MatrixBot,
    javaTimer: Timer,
    config: Config
) {
    companion object {
        private val logger = LoggerFactory.getLogger(TimerManager::class.java)
        val DEFAULT_REACTION = ":+1:".emoji()
    }

    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()).enable(SerializationFeature.INDENT_OUTPUT)
    private val timerFileLocation = config.dataDirectory + "/timers.json"
    private val timers = mutableListOf<TimerData>()

    init {
        val timerFile = File(timerFileLocation)
        if (timerFile.exists()) {
            val timersFromFile: List<TimerData> = objectMapper.readValue(timerFile)
            timers.addAll(timersFromFile)
        }
    }

    init {
        val millisecondsToNextMinute = (60 - LocalTime.now().second) * 1000L
        javaTimer.schedule(
            object : TimerTask() {
                override fun run() {
                    runBlocking {
                        logger.debug("Reminders: {}", timers)
                        val timerCopy = timers.toList()
                        val now = LocalTime.now()
                        for (timer in timerCopy) {
                            if (timer.timeToRemind.isAfter(now)) {
                                continue
                            }
                            removeTimer(timer)
                            remind(timer)
                        }
                    }
                }
            },
            millisecondsToNextMinute,
            30.seconds.inWholeMilliseconds
        )
    }

    fun addTimer(timer: TimerData) {
        timers.add(timer)
        saveTimers()
    }

    fun removeByOriginalRequestMessage(eventId: EventId): TimerData? {
        val timer = timers.find { it.originalRequestMessage() == eventId } ?: return null
        timers.remove(timer)
        saveTimers()
        return timer
    }

    private fun removeTimer(timer: TimerData) {
        timers.remove(timer)
        saveTimers()
    }

    @Synchronized
    private fun saveTimers() {
        val tempFile = File("$timerFileLocation.tmp")
        objectMapper.writeValue(tempFile, timers)
        val timerFile = File(timerFileLocation)
        Files.move(tempFile.toPath(), timerFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private suspend fun remind(timer: TimerData) {
        try {
            val(remainingReactions, emojiCount) = removeReactionOfBot(timer)
            if (remainingReactions.isEmpty()) {
                return
            }

            matrixBot.room().sendMessage(timer.roomId()) {
                reply(timer.botMessageId(), null)
                mentions(remainingReactions.toSet())
                val message = createReminderMessage(timer, remainingReactions, emojiCount)
                markdown(message)
            }
        } catch (e: Exception) {
            logger.error("Error during remind: ${e.message}", e)
        }
    }

    private fun createReminderMessage(
        timer: TimerData,
        remainingReactions: List<UserId>,
        emojiCount: Map<String, Int>
    ): String {
        val maxReactions = emojiCount.values.max()

        val mentions = remainingReactions.joinToString(", ") { it.matrixTo() }
        val messages = emojiCount.filter { it.value == maxReactions }.map { timer.emojiToMessage[it.key] }
        if (messages.size == 1) {
            return "$mentions : '${messages.first()}'"
        }

        return messages.joinToString("\n") { "* $it" } + "\n\n$mentions"
    }

    /**
     * Remove the reaction of the bot from the message
     * @return the list of users reacted to the message
     */
    private suspend fun removeReactionOfBot(timer: TimerData): Pair<List<UserId>, Map<String, Int>> {
        timer.redactBotReaction(matrixBot)

        val allReactions =
            matrixBot
                .room()
                .getTimelineEventReactionAggregation(timer.roomId(), timer.botMessageId())
                .first()
                .reactions

        val users =
            timer.emojiToMessage.keys
                .flatMap { allReactions[it] ?: emptyList() }
                .map { it.sender }
                .filter { it != matrixBot.self() }
                .distinct()
        val countsOfEmojis =
            timer.emojiToMessage.keys
                .map { it to (allReactions[it] ?: emptyList()) }
                .map { (emoji, reactions) -> emoji to reactions.filter { reaction -> reaction.sender != matrixBot.self() } }
                .associate { (emoji, reactions) -> emoji to reactions.size }
        return users to countsOfEmojis
    }

    data class TimerData(
        @param:JsonProperty val roomId: String,
        @param:JsonProperty val originalRequestMessage: String,
        @param:JsonProperty val currentRequestMessage: String,
        @param:JsonProperty val timeToRemind: LocalTime,
        @param:JsonProperty val botMessageId: String,
        @param:JsonProperty val botReactionMessageIds: List<String>,
        @param:JsonProperty val emojiToMessage: Map<String, String>
    ) {
        companion object {
            fun create(
                roomId: RoomId,
                originalRequestMessage: EventId,
                currentRequestMessage: EventId,
                timeToRemind: LocalTime,
                botMessageId: EventId,
                botReactionMessageIds: List<EventId>,
                emojiToMessage: Map<String, String>
            ) = TimerData(
                roomId.full,
                originalRequestMessage.full,
                currentRequestMessage.full,
                timeToRemind,
                botMessageId.full,
                botReactionMessageIds.map { it.full },
                emojiToMessage
            )
        }

        fun roomId() = RoomId(roomId)

        fun originalRequestMessage() = EventId(originalRequestMessage)

        fun botMessageId() = EventId(botMessageId)

        fun botReactionMessageIds() = botReactionMessageIds.map { EventId(it) }

        suspend fun redactAll(matrixBot: MatrixBot) {
            redactBotReaction(matrixBot)
            matrixBot.roomApi().redactEvent(roomId(), botMessageId())
        }

        suspend fun redactBotReaction(matrixBot: MatrixBot) {
            botReactionMessageIds().forEach { matrixBot.roomApi().redactEvent(roomId(), it) }
        }
    }
}
