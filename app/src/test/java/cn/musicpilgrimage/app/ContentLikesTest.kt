package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.ContentItem
import cn.musicpilgrimage.app.data.ContentLikes
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ContentLikesTest {
    private data class Request(val owner: String, val id: String, val liked: Boolean) {
        val result = CompletableDeferred<Unit>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val owner = MutableStateFlow<String?>("listener")
    private val requests = mutableListOf<Request>()
    private val likes = ContentLikes(scope, owner) { account, id, liked ->
        val request = Request(account, id, liked)
        requests += request
        request.result.await()
    }

    @After fun stop() = scope.cancel()

    @Test fun rapidReversalUpdatesImmediatelyAndSavesInOrder() {
        likes.toggle("listener", "message", false)
        assertTrue(likes.states.value.getValue("message").liked)
        assertEquals(4, likes.states.value.getValue("message").count(false, 3))

        likes.toggle("listener", "message", false)
        assertFalse(likes.states.value.getValue("message").liked)
        assertEquals(listOf(true), requests.map { it.liked })

        // The first write may have reached the server before the transport error.
        requests.first().result.completeExceptionally(IOException())
        assertEquals(listOf(true, false), requests.map { it.liked })
        requests.last().result.complete(Unit)
        assertFalse(likes.states.value.getValue("message").liked)
        assertNull(likes.states.value.getValue("message").failedTarget)
    }

    @Test fun failureRestoresLastConfirmedReactionWithoutTouchingOtherRows() {
        likes.toggle("listener", "message", true)
        likes.toggle("listener", "visit", false)
        assertFalse(likes.states.value.getValue("message").liked)
        assertEquals(2, requests.size)
        requests.first().result.completeExceptionally(IOException())

        val message = likes.states.value.getValue("message")
        assertTrue(message.liked)
        assertEquals(false, message.failedTarget)
        assertEquals(3, message.count(true, 3))
        assertTrue(likes.states.value.getValue("visit").liked)
    }

    @Test fun delayedReadsCannotOverwritePendingOrJustConfirmedReaction() {
        val beforeWrite = likes.beginRead()
        likes.toggle("listener", "message", false)
        val duringWrite = likes.beginRead()
        val unliked = listOf(ContentItem(JSONObject().put("id", "message").put("liked", false)))
        likes.reconcile("listener", duringWrite, unliked)
        assertTrue(likes.states.value.getValue("message").liked)
        requests.single().result.complete(Unit)

        likes.reconcile("listener", duringWrite, unliked)
        likes.reconcile("listener", beforeWrite, unliked)
        assertTrue(likes.states.value.getValue("message").liked)

        likes.reconcile("listener", likes.beginRead(), unliked)
        assertFalse(likes.states.value.getValue("message").liked)
    }

    @Test fun accountSwitchDiscardsPendingUiAndIgnoresOldCompletion() {
        likes.toggle("listener", "message", false)
        owner.value = "another-listener"
        assertTrue(likes.states.value.isEmpty())
        likes.toggle("another-listener", "message", true)
        requests.first().result.complete(Unit)

        val message = likes.states.value.getValue("message")
        assertEquals("another-listener", message.owner)
        assertFalse(message.liked)
    }
}
