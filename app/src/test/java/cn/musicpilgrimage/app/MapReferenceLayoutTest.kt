package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class MapReferenceLayoutTest {
    private fun layout(points: List<MapReferenceAnchor>, zoom: Double = 13.0, fontScale: Float = 1f) =
        layoutMapReferences(points, 360f, 420f, 1f, zoom, fontScale)

    @Test fun normalCardKeepsFixedAboveCoordinateEvenWhenLabelsCouldOverlap() {
        val card = layout(listOf(MapReferenceAnchor("havana", 180f, 210f))).single()
        assertEquals(MapReferenceKind.Cover, card.kind)
        assertEquals(180f, card.tipX, 0f); assertEquals(210f, card.tipY, 0f)
        assertEquals(142f, card.body.top, 0f)
        assertEquals(210f, card.body.bottom + 16f, 0f)
    }

    @Test fun farViewHasCompactMarkersWithUsableTouchAreas() {
        val marker = layout(listOf(MapReferenceAnchor("havana", 180f, 210f)), zoom = 4.0).single()
        assertEquals(MapReferenceKind.Point, marker.kind)
        assertTrue(marker.body.width < 52f)
        assertTrue(marker.touch.width >= 48f && marker.touch.height >= 48f)
        assertEquals(180f, marker.tipX, 0f); assertEquals(210f, marker.tipY, 0f)
    }

    @Test fun touchingCardsAggregateAllIdsAndSplitWhenSeparated() {
        val close = listOf(MapReferenceAnchor("a", 140f, 210f), MapReferenceAnchor("b", 175f, 210f), MapReferenceAnchor("c", 210f, 210f))
        val cluster = layout(close).single()
        assertEquals(MapReferenceKind.Cluster, cluster.kind)
        assertEquals(setOf("a", "b", "c"), cluster.anchors.map { it.id }.toSet())
        val spread = layout(listOf(MapReferenceAnchor("a", 60f, 210f), MapReferenceAnchor("b", 180f, 210f), MapReferenceAnchor("c", 300f, 210f)))
        assertEquals(3, spread.size)
        assertTrue(spread.all { it.kind == MapReferenceKind.Cover })
    }

    @Test fun viewportEdgesClipBodiesWithoutMovingThePointOrCard() {
        val viewport = MapPixelRect(0f, 0f, 360f, 420f)
        listOf(2f to 2f, 358f to 2f, 2f to 418f, 358f to 418f, 180f to 2f).forEach { (x, y) ->
            val bubble = layout(listOf(MapReferenceAnchor("edge", x, y))).single()
            assertFalse(viewport.contains(bubble.body)); assertTrue(viewport.contains(bubble.touch))
            assertEquals(x, bubble.tipX, 0f); assertEquals(y, bubble.tipY, 0f)
            assertEquals(x - 26f, bubble.body.left, 0f); assertEquals(y - 68f, bubble.body.top, 0f)
        }
    }

    @Test fun slightlyOffscreenAnchorCanStillHaveVisibleArtwork() {
        val bubble = layout(listOf(MapReferenceAnchor("edge", -5f, 125f))).single()
        assertTrue(bubble.body.right > 0f)
        assertEquals(-5f, bubble.tipX, 0f); assertEquals(125f, bubble.tipY, 0f)
    }

    @Test fun mergingClippedCardsBelowTheViewportDoesNotCreateAnInvalidBubble() {
        val points = listOf(MapReferenceAnchor("a", 180f, 480f), MapReferenceAnchor("b", 180f, 480f))
        assertEquals(1, layout(points.take(1)).size)
        assertTrue(layout(points).isEmpty())
    }

    @Test fun outsideCoordinatesDoNotProduceDetachedCards() {
        assertTrue(layout(listOf(MapReferenceAnchor("outside", -80f, 210f))).isEmpty())
        assertTrue(layout(listOf(MapReferenceAnchor("invalid", Float.NaN, 210f))).isEmpty())
    }

    @Test fun aggregationIsStableAcrossInputOrderingAndCoincidentPoints() {
        val anchors = listOf(MapReferenceAnchor("c", 180f, 210f), MapReferenceAnchor("a", 180f, 210f), MapReferenceAnchor("b", 180f, 210f))
        assertEquals(layout(anchors), layout(anchors.reversed()))
        assertEquals("a|b|c", layout(anchors).single().key)
    }

    @Test fun largeTextCanFitClusterCountsInsideTheViewport() {
        val cluster = layout((1..35).map { MapReferenceAnchor(it.toString(), 8f, 8f) }, fontScale = 2f).single()
        assertEquals(35, cluster.anchors.size)
        assertTrue(cluster.body.width >= 52f)
        assertTrue(MapPixelRect(0f, 0f, 360f, 420f).contains(cluster.touch))
    }
}
