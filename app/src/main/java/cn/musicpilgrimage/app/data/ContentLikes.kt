package cn.musicpilgrimage.app.data

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class ContentLikeState(
    val owner: String,
    val liked: Boolean,
    val revision: Long,
    val failedTarget: Boolean? = null,
) {
    fun count(serverLiked: Boolean, serverCount: Int): Int =
        (serverCount + (if (liked) 1 else 0) - (if (serverLiked) 1 else 0)).coerceAtLeast(0)
}

/** Shares pending and confirmed reactions across feeds for the signed-in account. */
internal class ContentLikes(
    private val scope: CoroutineScope,
    private val userId: StateFlow<String?>,
    private val save: suspend (owner: String, id: String, liked: Boolean) -> Unit,
) {
    private class Write(val owner: String, var confirmed: Boolean, var desired: Boolean)

    private val clock = AtomicLong()
    private val writes = mutableMapOf<String, Write>()
    private val mutable = MutableStateFlow<Map<String, ContentLikeState>>(emptyMap())
    val states = mutable.asStateFlow()
    private var owner = userId.value

    init {
        scope.launch { userId.collect { updateOwner() } }
    }

    private fun updateOwner() {
        val current = userId.value
        if (owner == current) return
        owner = current
        writes.clear()
        mutable.value = emptyMap()
    }

    // List and profile requests capture this before going to the network.
    fun beginRead(): Long = clock.incrementAndGet()

    fun reconcile(readOwner: String?, readRevision: Long, rows: List<ContentItem>) {
        updateOwner()
        if (readOwner != owner || mutable.value.isEmpty()) return
        val next = mutable.value.toMutableMap()
        for (row in rows) {
            val previous = next[row.id] ?: continue
            if (row.id !in writes && previous.revision < readRevision) {
                next[row.id] = previous.copy(liked = row.liked, revision = readRevision, failedTarget = null)
            }
        }
        mutable.value = next
    }

    fun toggle(requestOwner: String, id: String, serverLiked: Boolean) {
        updateOwner()
        if (requestOwner != owner) return
        val existing = writes[id]
        val current = mutable.value[id]?.liked ?: serverLiked
        val desired = !current
        val write = existing ?: Write(requestOwner, confirmed = current, desired = desired)
        write.desired = desired
        publish(id, requestOwner, desired)
        if (existing == null) {
            writes[id] = write
            scope.launch { flush(id, write) }
        }
    }

    private suspend fun flush(id: String, write: Write) {
        while (writes[id] === write && userId.value == write.owner) {
            val target = write.desired
            val saved = try {
                save(write.owner, id, target)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (writes[id] !== write || userId.value != write.owner) return
            if (saved) write.confirmed = target
            // A newer tap wins even if the previous request failed after reaching the server.
            if (target != write.desired) continue
            writes.remove(id)
            val settled = publish(id, write.owner, write.confirmed, if (saved) null else target)
            if (!saved) scope.launch {
                delay(3500)
                val current = mutable.value[id]
                if (current?.revision == settled.revision) {
                    mutable.value = mutable.value + (id to current.copy(failedTarget = null))
                }
            }
            return
        }
    }

    private fun publish(id: String, owner: String, liked: Boolean, failedTarget: Boolean? = null): ContentLikeState {
        val state = ContentLikeState(owner, liked, clock.incrementAndGet(), failedTarget)
        mutable.value = mutable.value + (id to state)
        return state
    }
}
