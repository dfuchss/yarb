package org.fuchss.matrix.yarb

import de.connect2x.lognity.api.backend.Backend
import de.connect2x.lognity.backend.DefaultBackend
import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.create
import de.connect2x.trixnity.clientserverapi.client.MatrixClientAuthProviderData
import de.connect2x.trixnity.clientserverapi.client.classicLogin
import de.connect2x.trixnity.clientserverapi.model.authentication.IdentifierType
import de.connect2x.trixnity.core.model.events.m.room.RoomMessageEventContent
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import org.fuchss.matrix.bots.MatrixBot
import org.fuchss.matrix.bots.command.ChangeUsernameCommand
import org.fuchss.matrix.bots.command.Command
import org.fuchss.matrix.bots.command.HelpCommand
import org.fuchss.matrix.bots.command.LogoutCommand
import org.fuchss.matrix.bots.command.QuitCommand
import org.fuchss.matrix.bots.helper.createCryptoDriverModule
import org.fuchss.matrix.bots.helper.createMediaStoreModule
import org.fuchss.matrix.bots.helper.createRepositoriesModule
import org.fuchss.matrix.bots.helper.decryptMessage
import org.fuchss.matrix.bots.helper.handleCommand
import org.fuchss.matrix.bots.helper.handleEncryptedCommand
import org.fuchss.matrix.yarb.commands.ReminderCommand
import java.io.File
import java.util.Timer
import kotlin.random.Random

private lateinit var commands: List<Command>

fun main() {
    Backend.set(DefaultBackend)
    runBlocking {
        val config = Config.load()

        val matrixClient = getMatrixClient(config)
        val matrixBot = MatrixBot(matrixClient, config)

        val timer = Timer(true)
        val timerManager = TimerManager(matrixBot, timer, config)

        val reminderCommand = ReminderCommand(config, timerManager)
        commands =
            listOf(
                HelpCommand(config, "YARB") {
                    commands
                },
                QuitCommand(),
                LogoutCommand(),
                ChangeUsernameCommand(),
                reminderCommand
            )

        // Command Handling
        matrixBot.subscribeContent { event -> handleCommand(commands, event, matrixBot, config, ReminderCommand.COMMAND_NAME) }
        matrixBot.subscribeContent { encEvent -> handleEncryptedCommand(commands, encEvent, matrixBot, config, ReminderCommand.COMMAND_NAME) }

        // Listen for edits of user messages
        matrixBot.subscribeContent<RoomMessageEventContent.TextBased.Text> { eventId, sender, roomId, content ->
            reminderCommand.handleUserEditMessage(matrixBot, eventId, sender, roomId, content)
        }
        matrixBot.subscribeContent { encryptedEvent ->
            decryptMessage(encryptedEvent, matrixBot) { eventId, userId, roomId, text ->
                reminderCommand.handleUserEditMessage(matrixBot, eventId, userId, roomId, text)
            }
        }
        matrixBot.subscribeContent { event -> reminderCommand.handleUserDeleteMessage(matrixBot, event) }

        val loggedOut = matrixBot.startBlocking()

        // After Shutdown
        timer.cancel()

        if (loggedOut) {
            // Cleanup database
            val databaseFiles = listOf(File(config.dataDirectory + "/database.mv.db"), File(config.dataDirectory + "/database.trace.db"))
            databaseFiles.filter { it.exists() }.forEach { it.delete() }
        }
    }
}

private suspend fun getMatrixClient(config: Config): MatrixClient {
    val existingMatrixClient = MatrixClient.create(createRepositoriesModule(config), createMediaStoreModule(config), createCryptoDriverModule()).getOrNull()
    if (existingMatrixClient != null) {
        return existingMatrixClient
    }

    val matrixClient =
        MatrixClient
            .create(
                createRepositoriesModule(config),
                createMediaStoreModule(config),
                createCryptoDriverModule(),
                MatrixClientAuthProviderData
                    .classicLogin(
                        baseUrl = Url(config.baseUrl),
                        identifier = IdentifierType.User(config.username),
                        password = config.password,
                        initialDeviceDisplayName = "${MatrixBot::class.java.`package`.name}-${Random.Default.nextInt()}"
                    ).getOrThrow()
            ).getOrThrow()

    return matrixClient
}
