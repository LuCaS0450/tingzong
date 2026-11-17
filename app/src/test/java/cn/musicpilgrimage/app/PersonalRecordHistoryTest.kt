package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.data.PersonalRecordHistory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PersonalRecordHistoryTest {
    @Test fun confirmedEmptyListsRemainAvailableDuringRefresh() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val owner = MutableStateFlow<String?>("first")
            val revision = MutableStateFlow(0)
            var now = 0L
            var requests = 0
            val slow = CompletableDeferred<Unit>()
            val history = PersonalRecordHistory(scope, owner, revision, { _, _, _ ->
                if (++requests == 2) slow.await()
                emptyList()
            }, clock = { now })
            val first = requireNotNull(history.load("first", "message"))
            assertTrue(first.rows.isEmpty())
            assertNull(history.cached("first", "recreation"))
            assertSame(first, history.load("first", "message"))
            assertEquals(1, requests)

            now = 31_000
            val one = async { history.load("first", "message") }
            val two = async { history.load("first", "message") }
            withTimeout(2_000) { while (requests < 2) yield() }
            assertSame(first, history.cached("first", "message"))
            slow.complete(Unit)
            assertSame(one.await(), two.await())
            assertEquals(2, requests)

            revision.value++
            assertEquals(1, history.load("first", "message")?.revision)
            assertEquals(3, requests)
            owner.value = "second"
            assertNull(history.cached("first", "message"))
            assertNull(history.cached("second", "message"))
            assertNull(history.load("first", "message"))
            assertEquals(3, requests)
        } finally {
            scope.cancel()
        }
    }
}
