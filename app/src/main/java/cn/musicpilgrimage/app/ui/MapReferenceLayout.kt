package cn.musicpilgrimage.app.ui

import kotlin.math.max

internal const val ReferenceCoverZoom = 12.0

internal data class MapPixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun overlaps(other: MapPixelRect, gap: Float = 0f) = left < other.right + gap && right + gap > other.left && top < other.bottom + gap && bottom + gap > other.top
    fun contains(other: MapPixelRect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

internal data class MapReferenceAnchor(val id: String, val x: Float, val y: Float)
internal enum class MapReferenceKind { Cover, Point, Cluster }
internal data class MapReferenceBubble(
    val anchors: List<MapReferenceAnchor>, val kind: MapReferenceKind,
    val body: MapPixelRect, val touch: MapPixelRect, val tipX: Float, val tipY: Float
) {
    val key get() = anchors.joinToString("|") { it.id }
}

/** Geometry is expressed in screen pixels. Individual tips always retain the supplied map projection. */
internal fun layoutMapReferences(
    anchors: List<MapReferenceAnchor>, width: Float, height: Float, density: Float, zoom: Double,
    fontScale: Float = 1f
): List<MapReferenceBubble> {
    if (width < 48 * density || height < 48 * density || density <= 0) return emptyList()
    var groups = anchors.distinctBy { it.id }.filter { it.x.isFinite() && it.y.isFinite() }
        .sortedBy { it.id }.map { listOf(it) }.filter { bubbleFor(it, width, height, density, zoom, fontScale) != null }
    while (true) {
        // A compact merged marker can leave the viewport even when the taller covers intersect it.
        val bubbles = groups.mapNotNull { bubbleFor(it, width, height, density, zoom, fontScale) }
        groups = bubbles.map { it.anchors }
        val parents = IntArray(groups.size) { it }
        fun root(index: Int): Int { var cursor = index; while (parents[cursor] != cursor) cursor = parents[cursor]; return cursor }
        for (left in bubbles.indices) for (right in left + 1 until bubbles.size) {
            if (bubbles[left].touch.overlaps(bubbles[right].touch, 4 * density)) parents[root(right)] = root(left)
        }
        val merged = groups.indices.groupBy { root(it) }.values.map { indices -> indices.flatMap { groups[it] }.sortedBy { it.id } }
        if (merged.size == groups.size) return bubbles
        groups = merged
    }
}

private fun bubbleFor(
    anchors: List<MapReferenceAnchor>, width: Float, height: Float, density: Float, zoom: Double,
    fontScale: Float
): MapReferenceBubble? {
    val x = anchors.map { it.x }.average().toFloat()
    val y = anchors.map { it.y }.average().toFloat()
    val kind = when { anchors.size > 1 -> MapReferenceKind.Cluster; zoom >= ReferenceCoverZoom && width >= 64 * density && height >= 64 * density -> MapReferenceKind.Cover; else -> MapReferenceKind.Point }
    val bodyWidth = when (kind) {
        MapReferenceKind.Cover -> 52f
        MapReferenceKind.Point -> 20f
        MapReferenceKind.Cluster -> max(36f, 16f + anchors.size.toString().length * 9f * fontScale)
    }.coerceAtMost(width / density - 12) * density
    val bodyHeight = when (kind) { MapReferenceKind.Cover -> 52f; MapReferenceKind.Point -> 20f; MapReferenceKind.Cluster -> max(36f, 12f + 16f * fontScale) }
        .coerceAtMost(height / density - 12) * density
    val pointer = (if (kind == MapReferenceKind.Cover) 16 else 8) * density
    // Preserve fixed-above placement at every edge. Only the map viewport clips the artwork.
    val body = MapPixelRect(x - bodyWidth / 2, y - pointer - bodyHeight, x + bodyWidth / 2, y - pointer)
    val visible = MapPixelRect(max(0f, body.left), max(0f, body.top), minOf(width, body.right), minOf(height, y))
    if (visible.width <= 0 || visible.height <= 0) return null
    val touchWidth = max(48 * density, visible.width).coerceAtMost(width)
    val touchHeight = max(48 * density, visible.height).coerceAtMost(height)
    val touchLeft = ((visible.left + visible.right - touchWidth) / 2).coerceIn(0f, width - touchWidth)
    val touchTop = ((visible.top + visible.bottom - touchHeight) / 2).coerceIn(0f, height - touchHeight)
    return MapReferenceBubble(anchors, kind, body, MapPixelRect(touchLeft, touchTop, touchLeft + touchWidth, touchTop + touchHeight), x, y)
}
