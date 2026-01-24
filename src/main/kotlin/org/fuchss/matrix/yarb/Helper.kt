package org.fuchss.matrix.yarb

import de.connect2x.trixnity.client.room.RoomService
import de.connect2x.trixnity.core.model.EventId
import de.connect2x.trixnity.core.model.RoomId
import org.fuchss.matrix.bots.firstWithTimeout

suspend fun RoomService.getMessageId(
    roomId: RoomId,
    transactionId: String
): EventId? {
    val transaction = this.getOutbox(roomId, transactionId)
    return transaction.firstWithTimeout { it?.eventId != null }?.eventId
}
