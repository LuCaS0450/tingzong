package cn.musicpilgrimage.app.data

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal data class PersonalRecordPage(
    val rows: List<ContentItem>,
    val nextOffset: Int?,
    val revision: Int,
    val refreshedAt: Long,
)

/** In-memory history shared across archive screens. Called on the app's main scope. */
internal class PersonalRecordHistory(
    private val scope: CoroutineScope,
    private val owner: StateFlow<String?>,
    private val revision: StateFlow<Int>,
    private val fetch: suspend (String, String, Int) -> List<ContentItem>,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private data class Request(val generation: Long, val kind: String, val revision: Int, val offset: Int)
    private var account = owner.value
    private var generation = 0L
    private val pages = mutableMapOf<String, PersonalRecordPage>()
    private val pending = mutableMapOf<Request, Deferred<PersonalRecordPage?>>()

    init {
        scope.launch { owner.collect { syncOwner() } }
    }

    private fun syncOwner() {
        if (account == owner.value) return
        account = owner.value
        generation++
        pages.clear()
        pending.values.toList().forEach { it.cancel() }
        pending.clear()
    }

    fun cached(account: String, kind: String): PersonalRecordPage? =
        pages[kind].takeIf { this.account == account && owner.value == account }

    suspend fun load(account: String, kind: String, force: Boolean = false, more: Boolean = false): PersonalRecordPage? {
        syncOwner()
        val page = cached(account, kind)
        if (this.account != account) return null
        val currentRevision = revision.value
        val offset = if (more) page?.nextOffset ?: return page else 0
        if (!more && !force && page != null && page.revision == currentRevision && clock() - page.refreshedAt < 30_000) return page
        val request = Request(generation, kind, currentRevision, offset)
        val job = pending[request] ?: scope.async(start = CoroutineStart.LAZY) {
            try {
                val rows = fetch(account, kind, offset)
                if (owner.value != account || generation != request.generation || revision.value != request.revision) return@async null
                // A first-page refresh can replace the base while a later page is in flight.
                if (more && pages[kind] !== page) return@async pages[kind]
                val previous = if (more) page?.rows.orEmpty() else emptyList()
                PersonalRecordPage(
                    (previous + rows).distinctBy(ContentItem::id),
                    (offset + rows.size).takeIf { rows.size >= 30 },
                    currentRevision,
                    if (more) page?.refreshedAt ?: clock() else clock(),
                ).also { pages[kind] = it }
            } finally {
                pending.remove(request)
            }
        }.also { pending[request] = it }
        return job.await()
    }
}
