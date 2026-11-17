package cn.musicpilgrimage.app

import androidx.compose.ui.geometry.Rect
import cn.musicpilgrimage.app.performance.Judgment
import cn.musicpilgrimage.app.performance.SongPackage
import cn.musicpilgrimage.app.performance.SongSection
import cn.musicpilgrimage.app.performance.Target
import cn.musicpilgrimage.app.performance.ambientIntensityAt
import cn.musicpilgrimage.app.performance.computeAccuracyPercent
import cn.musicpilgrimage.app.performance.computeSectionAmbientCues
import cn.musicpilgrimage.app.performance.formatAccuracy
import cn.musicpilgrimage.app.ui.CoverTransitionCoordinator
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrototypeMotionUnitTest {
    @Test
    fun nativeCoverRevealsInTheSameStateChangeThatClearsTheOverlay() {
        val coordinator = CoverTransitionCoordinator()
        val hero = Rect(32f, 208f, 688f, 864f)
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateSource("release:record", "model", 1f, Rect(76f, 367f, 345f, 636f), sleeve = true)
        assertTrue(coordinator.startEnter("release:record", "album/record", hero, physicalMotion = true))
        val generation = requireNotNull(coordinator.activeMorph).generation
        coordinator.reportDestinationHero("album/record", "release:record", hero, firstPaint = true, nativeDestination = true)
        coordinator.markRevealDispatched(generation, hero)
        assertTrue(coordinator.shouldHoldHeroHidden("release:record", "album/record"))
        coordinator.markAnimFinished(generation)
        coordinator.markRevealDispatched(generation, hero)
        assertNull(coordinator.activeMorph)
        assertFalse(coordinator.shouldHoldHeroHidden("release:record", "album/record"))
    }

    @Test
    fun coverCoordinator_generationMonotonicityAndStaleCallbackRejection() {
        val coordinator = CoverTransitionCoordinator()
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateSource(
            key = "release:abbey",
            model = "https://example.invalid/abbey.jpg",
            aspectRatio = 1f,
            bounds = Rect(40f, 300f, 240f, 500f),
            sleeve = true,
            visible = true,
        )

        assertTrue(
            coordinator.startEnter(
                key = "release:abbey",
                route = "album/abbey",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
            ),
        )
        val gen1 = requireNotNull(coordinator.activeMorph).generation

        // Fast interruption into another destination increments generation cleanly.
        assertTrue(
            coordinator.startEnter(
                key = "release:abbey",
                route = "album/revolver",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
            ),
        )
        val gen2 = requireNotNull(coordinator.activeMorph).generation
        assertTrue(gen2 > gen1)

        // Late WebView callback from stale destination must be dropped.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
        )
        assertFalse(requireNotNull(coordinator.activeMorph).destinationReady)
        assertEquals("album/revolver", coordinator.activeMorph?.route)

        // Stale animation/reveal completion signals must also be dropped.
        coordinator.markAnimFinished(gen1)
        coordinator.markRevealDispatched(gen1, coordinator.activeMorph?.toRect)
        assertFalse(requireNotNull(coordinator.activeMorph).animFinished)
        assertFalse(requireNotNull(coordinator.activeMorph).revealDispatched)

        // Current-generation completion & reveal are accepted and cleared only by gen2.
        coordinator.reportDestinationHero(
            route = "album/revolver",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
        )
        val landingRect = requireNotNull(coordinator.activeMorph?.toRect)
        coordinator.markRevealDispatched(gen2, landingRect)
        assertFalse(requireNotNull(coordinator.activeMorph).revealDispatched)
        coordinator.markAnimFinished(gen2)
        coordinator.markRevealDispatched(gen2, Rect(0f, 120f, 720f, 840f))
        assertFalse(requireNotNull(coordinator.activeMorph).revealDispatched)
        coordinator.markRevealDispatched(gen2, landingRect)
        assertTrue(requireNotNull(coordinator.activeMorph).destinationReady)
        assertTrue(requireNotNull(coordinator.activeMorph).animFinished)
        assertTrue(requireNotNull(coordinator.activeMorph).revealDispatched)

        coordinator.clearMorphIfGeneration(gen1)
        assertNotNull(coordinator.activeMorph)
        coordinator.clearMorphIfGeneration(gen2)
        assertNull(coordinator.activeMorph)
    }

    @Test
    fun coverCoordinator_reduceMotionAndInvisibleSourceFallback() {
        val coordinator = CoverTransitionCoordinator()
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateSource(
            key = "release:1",
            model = "https://example.invalid/1.jpg",
            aspectRatio = 1f,
            bounds = Rect(20f, 200f, 220f, 400f),
            sleeve = true,
            visible = true,
        )

        assertFalse(
            coordinator.startEnter(
                key = "release:1",
                route = "album/1",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = false,
            ),
        )
        assertNull(coordinator.activeMorph)

        // Offscreen shelf source must fall back to standard route transition.
        coordinator.updateSource(
            key = "release:1",
            model = "https://example.invalid/1.jpg",
            aspectRatio = 1f,
            bounds = Rect(-400f, 200f, -200f, 400f),
            sleeve = true,
            visible = false,
        )
        assertFalse(
            coordinator.startEnter(
                key = "release:1",
                route = "album/1",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
            ),
        )

        // Scrolled-away destination hero during return must fall back rather than jumping.
        coordinator.updateSource(
            key = "release:1",
            model = "https://example.invalid/1.jpg",
            aspectRatio = 1f,
            bounds = Rect(20f, 200f, 220f, 400f),
            sleeve = true,
            visible = true,
        )
        coordinator.updateDestinationHeroRect("release:1", Rect(0f, 1800f, 720f, 2500f))
        assertFalse(coordinator.startReturn("release:1", "album/1", "release:1", physicalMotion = true))
    }

    @Test
    fun coverCoordinator_decodedDrawnGating_failedReveal_andRenderTokenIsolation() {
        val coordinator = CoverTransitionCoordinator()
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateShelfState(ownerEntryId = "shelf-1", page = 0, layoutVersion = 1L)
        coordinator.updateSource(
            key = "release:abbey",
            model = "https://example.invalid/abbey.jpg",
            aspectRatio = 1f,
            bounds = Rect(40f, 300f, 240f, 500f),
            sleeve = true,
            visible = true,
            ownerEntryId = "shelf-1",
            page = 0,
            layoutVersion = 1L,
            imageLoaded = true,
        )

        assertTrue(
            coordinator.startEnter(
                key = "release:abbey",
                route = "album/abbey",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
            ),
        )
        coordinator.bindDestinationEntry(route = "album/abbey", key = "release:abbey", navEntryId = "album-1")

        // Stage 1 DOM paint alone must NOT unhide destination before image decode + post-decode draw.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
            navEntryId = "album-1",
            identity = "release:abbey",
            renderToken = 2L,
            decoded = false,
            drawn = false,
        )
        assertFalse(requireNotNull(coordinator.activeMorph).destinationDecoded)
        assertFalse(requireNotNull(coordinator.activeMorph).destinationDrawn)
        assertFalse(requireNotNull(coordinator.activeMorph).destinationReady)

        // Stage 2 JS decode alone must still keep cover overlay until post-decoded VisualStateCallback draw.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
            navEntryId = "album-1",
            identity = "release:abbey",
            renderToken = 2L,
            decoded = true,
            drawn = false,
        )
        assertTrue(requireNotNull(coordinator.activeMorph).destinationDecoded)
        assertFalse(requireNotNull(coordinator.activeMorph).destinationDrawn)
        assertFalse(requireNotNull(coordinator.activeMorph).destinationReady)

        // Stale lower renderToken (e.g. old A vs new A) must be ignored.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
            navEntryId = "album-1",
            identity = "release:abbey",
            renderToken = 1L,
            decoded = true,
            drawn = true,
        )
        assertFalse(requireNotNull(coordinator.activeMorph).destinationDrawn)

        // Matching navEntryId + current renderToken post-decoded draw completes readiness.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
            navEntryId = "album-1",
            identity = "release:abbey",
            renderToken = 2L,
            decoded = true,
            drawn = true,
        )
        assertTrue(requireNotNull(coordinator.activeMorph).destinationReady)

        // Higher renderToken failed state marks failure cleanly without leaving overlay stuck.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
            navEntryId = "album-1",
            identity = "release:abbey",
            renderToken = 3L,
            decoded = false,
            drawn = false,
            failed = true,
        )
        assertFalse(requireNotNull(coordinator.activeMorph).destinationReady)
        assertTrue(requireNotNull(coordinator.activeMorph).destinationFailed)
    }

    @Test
    fun coverCoordinator_interactiveReturn_cancel_commit_and_sessionRelease() {
        val coordinator = CoverTransitionCoordinator()
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateShelfState(ownerEntryId = "shelf-1", page = 0, layoutVersion = 1L)
        coordinator.updateSource(
            key = "release:1",
            model = "https://example.invalid/1.jpg",
            aspectRatio = 1f,
            bounds = Rect(20f, 200f, 220f, 400f),
            sleeve = true,
            visible = true,
            ownerEntryId = "shelf-1",
            page = 0,
            layoutVersion = 1L,
            imageLoaded = true,
        )
        coordinator.reportDestinationHero(
            route = "album/1",
            key = "release:1",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
            navEntryId = "album-1",
            identity = "release:1",
            renderToken = 1L,
            decoded = true,
            drawn = true,
        )

        val gen = coordinator.beginReturnSession(
            key = "release:1",
            route = "album/1",
            identity = "release:1",
            physicalMotion = true,
            sourceNavEntryId = "album-1",
            targetNavEntryId = "shelf-1",
            interactive = true,
        )
        assertNotNull(gen)
        coordinator.updateInteractiveReturn(gen!!, 0.55f)
        assertEquals(CoverTransitionCoordinator.Mode.RETURNING_INTERACTIVE, coordinator.activeMorph?.mode)

        // Cancel returns morph smoothly instead of snapping away.
        coordinator.cancelInteractiveReturn(gen)
        assertEquals(CoverTransitionCoordinator.Mode.RETURNING_CANCELING, coordinator.activeMorph?.mode)

        // Disposing cancelled interactive session cleans it (and clears stale hero), while committed return survives pop.
        coordinator.releaseEntrySession(navEntryId = "album-1")
        assertNull(coordinator.activeMorph)

        // Newly opened instance album-2 starts interactive return and commits; popping album-2 preserves RETURNING morph.
        coordinator.reportDestinationHero(
            route = "album/1",
            key = "release:1",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
            navEntryId = "album-2",
            identity = "release:1",
            renderToken = 1L,
            decoded = true,
            drawn = true,
            webInstanceId = 2L,
            loadAttempt = 1,
        )
        val gen2 = coordinator.beginReturnSession(
            key = "release:1",
            route = "album/1",
            identity = "release:1",
            physicalMotion = true,
            sourceNavEntryId = "album-2",
            targetNavEntryId = "shelf-1",
            interactive = true,
        )!!
        coordinator.updateInteractiveReturn(gen2, 0.4f)
        assertTrue(coordinator.commitInteractiveReturn(gen2))
        assertEquals(CoverTransitionCoordinator.Mode.RETURNING, coordinator.activeMorph?.mode)
        coordinator.releaseEntrySession(navEntryId = "album-2", generation = gen2, webInstanceId = 2L)
        assertEquals(CoverTransitionCoordinator.Mode.RETURNING, coordinator.activeMorph?.mode)

        // Wrong shelf page or navEntryId must reject cover return (fallback to route motion).
        coordinator.cancelAll()
        coordinator.updateShelfState(ownerEntryId = "shelf-1", page = 1, layoutVersion = 1L)
        assertNull(
            coordinator.beginReturnSession(
                key = "release:1",
                route = "album/1",
                identity = "release:1",
                physicalMotion = true,
                sourceNavEntryId = "album-1",
                targetNavEntryId = "shelf-1",
                interactive = false,
            ),
        )
    }

    @Test
    fun sectionAmbientCues_clearanceAndDenseGuard() {
        val song = SongPackage(
            id = "test-song",
            version = 1,
            title = "Test",
            artist = "Artist",
            referenceEdition = "Ref",
            durationUs = 10_000_000L,
            events = emptyList(),
            easy = emptyList(),
            standard = listOf(
                // Boundary 1 (2_000_000, cue 1_860_000..3_180_000): clean clearance before & after -> eligible
                Target(id = 0, eventIds = emptyList(), lane = 0, onsetUs = 1_100_000L, durationUs = 0L, hold = false),
                Target(id = 1, eventIds = emptyList(), lane = 1, onsetUs = 3_900_000L, durationUs = 0L, hold = false),
                // Boundary 2 (5_500_000): dense run across boundary -> suppressed
                Target(id = 2, eventIds = emptyList(), lane = 0, onsetUs = 5_000_000L, durationUs = 0L, hold = false),
                Target(id = 3, eventIds = emptyList(), lane = 1, onsetUs = 5_350_000L, durationUs = 0L, hold = false),
                Target(id = 4, eventIds = emptyList(), lane = 2, onsetUs = 5_700_000L, durationUs = 0L, hold = false),
                Target(id = 5, eventIds = emptyList(), lane = 3, onsetUs = 6_050_000L, durationUs = 0L, hold = false),
            ),
            sections = listOf(
                SongSection("Intro", 0L, 2_000_000L, "scene-1", 92.0),
                SongSection("Bridge", 2_000_000L, 5_500_000L, "scene-2", 92.0),
                SongSection("Climax", 5_500_000L, 10_000_000L, "scene-3", 92.0),
            ),
            placeIds = emptyList(),
            sources = JSONObject(),
        )

        val cues = computeSectionAmbientCues(song, song.standard, 0L, song.durationUs)
        assertEquals(1, cues.size)
        assertEquals(1, cues.first().sectionIndex)

        val cue = cues.first()
        assertEquals(0f, ambientIntensityAt(cues, cue.startUs - 10_000L), 0.0001f)
        val peak = ambientIntensityAt(cues, cue.startUs + cue.durationUs / 2L)
        assertTrue(peak in 0.9f..1.0f)
        assertEquals(0f, ambientIntensityAt(cues, cue.startUs + cue.durationUs + 10_000L), 0.0001f)
    }

    @Test
    fun sectionAmbientCues_holdSpanAndLateCueWindowSuppression() {
        val song = SongPackage(
            id = "test-hold-song",
            version = 1,
            title = "Test Hold",
            artist = "Artist",
            referenceEdition = "Ref",
            durationUs = 12_000_000L,
            events = emptyList(),
            easy = emptyList(),
            standard = listOf(
                // Boundary 1 (2_500_000, cue 2_360_000..3_680_000): hold starts earlier & extends into checkStart -> suppressed
                Target(id = 0, eventIds = emptyList(), lane = 0, onsetUs = 1_200_000L, durationUs = 1_000_000L, hold = true),
                // Boundary 2 (7_500_000, cue 7_360_000..8_680_000): tap falls inside second half of cue window -> suppressed
                Target(id = 1, eventIds = emptyList(), lane = 2, onsetUs = 8_200_000L, durationUs = 0L, hold = false),
            ),
            sections = listOf(
                SongSection("A", 0L, 2_500_000L, "scene-1", 90.0),
                SongSection("B", 2_500_000L, 7_500_000L, "scene-2", 90.0),
                SongSection("C", 7_500_000L, 12_000_000L, "scene-3", 90.0),
            ),
            placeIds = emptyList(),
            sources = JSONObject(),
        )

        val cues = computeSectionAmbientCues(song, song.standard, 0L, song.durationUs)
        assertTrue(cues.isEmpty())
    }

    @Test
    fun performanceAccuracy_computationAndFormatting() {
        val accuracy = computeAccuracyPercent(
            listOf(Judgment.PERFECT, Judgment.GOOD, Judgment.PARTIAL, Judgment.MISSED),
        )
        assertNotNull(accuracy)
        assertEquals(51.25f, accuracy!!, 0.01f)
        assertEquals("51.2%", formatAccuracy(accuracy))
        assertEquals("100%", formatAccuracy(100f))
        assertNull(computeAccuracyPercent(emptyList()))
    }

    @Test
    fun coverCoordinator_p1_oldEntryDispose_and_staleWebCallbacks_neverCorrupt_newEntry() {
        val coordinator = CoverTransitionCoordinator()
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateShelfState(ownerEntryId = "shelf-1", page = 0, layoutVersion = 1L)
        coordinator.updateSource(
            key = "release:abbey",
            model = "https://example.invalid/abbey.jpg",
            aspectRatio = 1f,
            bounds = Rect(40f, 300f, 240f, 500f),
            sleeve = true,
            visible = true,
            ownerEntryId = "shelf-1",
            page = 0,
            layoutVersion = 1L,
            imageLoaded = true,
        )

        // Open A1, settle webId=1, then start A2 before A1 disposes.
        assertTrue(
            coordinator.startEnter(
                key = "release:abbey",
                route = "album/abbey",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "abbey",
            ),
        )
        val gen1 = coordinator.bindDestinationEntry("album/abbey", "release:abbey", "album-a1")
        assertNotNull(gen1)
        coordinator.registerWebInstance("album-a1", webInstanceId = 1L)
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 120f, 720f, 840f),
            firstPaint = true,
            navEntryId = "album-a1",
            identity = "album:abbey",
            renderToken = 1L,
            decoded = true,
            drawn = true,
            webInstanceId = 1L,
            loadAttempt = 1,
            expectedGeneration = gen1,
        )
        coordinator.markAnimFinished(gen1!!)
        coordinator.markRevealDispatched(gen1, coordinator.activeMorph?.toRect)
        coordinator.markRevealConfirmed(gen1)
        coordinator.clearMorphIfGeneration(gen1)

        // Start A2 for the SAME route/key/release before A1 disposes (sub-case 1: A1 disposes BEFORE A2 binds).
        assertTrue(
            coordinator.startEnter(
                key = "release:abbey",
                route = "album/abbey",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "abbey",
            ),
        )
        val gen2Unbound = requireNotNull(coordinator.activeMorph).generation
        assertTrue(gen2Unbound > gen1)

        // A1 disposes while A2 is still unbound (targetNavEntryId == null): must NOT cancel gen2!
        coordinator.releaseWebInstance(webInstanceId = 1L, navEntryId = "album-a1", generation = gen1)
        coordinator.releaseEntrySession(navEntryId = "album-a1", generation = gen1, webInstanceId = 1L)
        assertEquals(gen2Unbound, coordinator.activeMorph?.generation)
        assertNull(coordinator.destinationHeroRect("album:abbey"))

        // Bind A2 and mount webId=2.
        val gen2 = coordinator.bindDestinationEntry("album/abbey", "release:abbey", "album-a2")
        assertEquals(gen2Unbound, gen2)
        coordinator.registerWebInstance("album-a2", webInstanceId = 2L)

        // Delayed report from dead A1 / webId=1 (before and after binding) must be ignored.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 140f, 720f, 860f),
            firstPaint = true,
            navEntryId = "album-a1",
            identity = "album:abbey",
            renderToken = 99L,
            decoded = true,
            drawn = true,
            webInstanceId = 1L,
            loadAttempt = 9,
            expectedGeneration = gen1,
        )
        assertEquals(0L, coordinator.activeMorph?.webInstanceId)
        assertFalse(coordinator.activeMorph?.destinationDrawn ?: true)

        // Retry on webId=2: loadAttempt 1 fails, loadAttempt 2 resets and succeeds, stale attempt 1 callback ignored.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 124f, 720f, 844f),
            firstPaint = false,
            navEntryId = "album-a2",
            identity = "album:abbey",
            renderToken = 1L,
            decoded = false,
            drawn = false,
            failed = true,
            webInstanceId = 2L,
            loadAttempt = 1,
            expectedGeneration = gen2,
        )
        assertTrue(coordinator.activeMorph?.destinationFailed == true)

        // Stale token inside attempt 1 or lower attempt cannot overwrite attempt 2 once started.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 128f, 720f, 848f),
            firstPaint = false,
            navEntryId = "album-a2",
            identity = "album:abbey",
            renderToken = 1L,
            decoded = false,
            drawn = false,
            failed = false,
            webInstanceId = 2L,
            loadAttempt = 2,
            expectedGeneration = gen2,
        )
        assertEquals(2, coordinator.activeMorph?.loadAttempt)
        assertFalse(coordinator.activeMorph?.destinationFailed ?: true)

        // Late failure from attempt 1 must be discarded.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 128f, 720f, 848f),
            firstPaint = false,
            navEntryId = "album-a2",
            identity = "album:abbey",
            renderToken = 99L,
            decoded = false,
            drawn = false,
            failed = true,
            webInstanceId = 2L,
            loadAttempt = 1,
            expectedGeneration = gen2,
        )
        assertFalse(coordinator.activeMorph?.destinationFailed ?: true)

        // Attempt 2 completes decode + draw.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 130f, 720f, 850f),
            firstPaint = true,
            navEntryId = "album-a2",
            identity = "album:abbey",
            renderToken = 2L,
            decoded = true,
            drawn = true,
            webInstanceId = 2L,
            loadAttempt = 2,
            expectedGeneration = gen2,
        )
        assertTrue(coordinator.activeMorph?.destinationReady == true)
        assertEquals(Rect(0f, 130f, 720f, 850f), coordinator.destinationHeroRect("album:abbey"))

        // Simulated child-route round trip on SAME album-a2 (e.g. album -> work -> back to album):
        // old webId=2 disposes while child is active, then album-a2 mounts fresh webId=3.
        coordinator.markAnimFinished(gen2!!)
        coordinator.markRevealDispatched(gen2, coordinator.activeMorph?.toRect)
        coordinator.markRevealConfirmed(gen2)
        coordinator.clearMorphIfGeneration(gen2)

        coordinator.releaseWebInstance(webInstanceId = 2L, navEntryId = "album-a2", generation = gen2)
        coordinator.releaseEntrySession(navEntryId = "album-a2", generation = gen2, webInstanceId = 2L)
        assertNull(coordinator.destinationHeroRect("album:abbey"))

        coordinator.registerWebInstance("album-a2", webInstanceId = 3L)
        // Old dead webId=2 still rejected even after album-a2 remounted.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 999f, 720f, 1700f),
            firstPaint = true,
            navEntryId = "album-a2",
            identity = "album:abbey",
            renderToken = 5L,
            decoded = true,
            drawn = true,
            webInstanceId = 2L,
            loadAttempt = 3,
        )
        assertNull(coordinator.destinationHeroRect("album:abbey"))

        // Fresh webId=3 on album-a2 is accepted and enables return.
        coordinator.reportDestinationHero(
            route = "album/abbey",
            key = "release:abbey",
            heroRectInRoot = Rect(0f, 132f, 720f, 852f),
            firstPaint = true,
            navEntryId = "album-a2",
            identity = "album:abbey",
            renderToken = 1L,
            decoded = true,
            drawn = true,
            webInstanceId = 3L,
            loadAttempt = 1,
        )
        assertEquals(Rect(0f, 132f, 720f, 852f), coordinator.destinationHeroRect("album:abbey"))
    }

    @Test
    fun coverCoordinator_p2_1_slowWaitTimeout_degrade_revealConfirmTimeout_and_lateArrival() {
        val coordinator = CoverTransitionCoordinator()
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateShelfState(ownerEntryId = "shelf-1", page = 0, layoutVersion = 1L)
        coordinator.updateSource(
            key = "release:slow",
            model = "https://example.invalid/slow.jpg",
            aspectRatio = 1f,
            bounds = Rect(40f, 300f, 240f, 500f),
            sleeve = true,
            visible = true,
            ownerEntryId = "shelf-1",
            page = 0,
            layoutVersion = 1L,
            imageLoaded = true,
        )

        assertTrue(
            coordinator.startEnter(
                key = "release:slow",
                route = "album/slow",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "slow",
            ),
        )
        val gen = coordinator.bindDestinationEntry("album/slow", "release:slow", "album-slow")!!
        coordinator.registerWebInstance("album-slow", webInstanceId = 10L)

        // Hero still waiting after morph finishes: held hidden under degraded fallback threshold.
        coordinator.markAnimFinished(gen)
        assertTrue(coordinator.shouldHoldHeroHidden("release:slow", "album/slow", "album-slow"))
        assertFalse(coordinator.activeMorph?.degradedFallback ?: true)

        // Slow wait times out -> degradedFallback flips ON, holding hero stops so retry/placeholder card shows.
        coordinator.markDestinationWaitTimedOut(gen, timestampMs = 2_000L)
        assertTrue(coordinator.activeMorph?.destinationWaitTimedOut == true)
        assertTrue(coordinator.activeMorph?.degradedFallback == true)
        assertEquals(2_000L, coordinator.activeMorph?.degradeRequestedAtMs)
        assertTrue(coordinator.shouldHoldHeroHidden("release:slow", "album/slow", "album-slow"))

        // If reveal confirm callback is lost, revealConfirmTimeout clears overlay without getting stuck.
        coordinator.markRevealDispatched(gen, coordinator.activeMorph?.toRect)
        assertFalse(coordinator.shouldHoldHeroHidden("release:slow", "album/slow", "album-slow"))
        coordinator.markRevealConfirmTimedOut(gen, timestampMs = 2_300L)
        assertNull(coordinator.activeMorph)

        // Late image arrival after timeout updates destinationHeroRect in-place without restarting activeMorph.
        coordinator.reportDestinationHero(
            route = "album/slow",
            key = "release:slow",
            heroRectInRoot = Rect(0f, 125f, 720f, 845f),
            firstPaint = true,
            navEntryId = "album-slow",
            identity = "album:slow",
            renderToken = 2L,
            decoded = true,
            drawn = true,
            timestampMs = 3_100L,
            webInstanceId = 10L,
            loadAttempt = 1,
            expectedGeneration = gen,
        )
        assertNull(coordinator.activeMorph)
        assertEquals(Rect(0f, 125f, 720f, 845f), coordinator.destinationHeroRect("album:slow"))
        assertNotNull(
            coordinator.beginReturnSession(
                key = "release:slow",
                route = "album/slow",
                identity = "album:slow",
                physicalMotion = true,
                sourceNavEntryId = "album-slow",
                targetNavEntryId = "shelf-1",
                interactive = false,
            ),
        )
    }

    @Test
    fun coverCoordinator_p2_1_releaseEntrySession_preservesReusedWebInstance() {
        val coordinator = CoverTransitionCoordinator()
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateShelfState(ownerEntryId = "shelf-1", page = 0, layoutVersion = 1L)
        coordinator.updateSource(
            key = "release:reused",
            model = "https://example.invalid/reused.jpg",
            aspectRatio = 1f,
            bounds = Rect(40f, 300f, 240f, 500f),
            sleeve = true,
            visible = true,
            ownerEntryId = "shelf-1",
            page = 0,
            layoutVersion = 1L,
            imageLoaded = true,
        )

        assertTrue(
            coordinator.startEnter(
                key = "release:reused",
                route = "album/reused",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "reused",
            ),
        )
        val gen1 = coordinator.bindDestinationEntry("album/reused", "release:reused", "album-entry-1")!!
        coordinator.registerWebInstance("album-entry-1", webInstanceId = 1L)
        coordinator.releaseEntrySession(navEntryId = "album-entry-1", generation = gen1, webInstanceId = 1L)

        // Switching to a new entry reusing WebView instance 1L must still accept updates for the new entry,
        // while rejecting stale callbacks from the disposed entry session.
        assertTrue(
            coordinator.startEnter(
                key = "release:reused",
                route = "album/reused",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "reused",
            ),
        )
        val gen2 = coordinator.bindDestinationEntry("album/reused", "release:reused", "album-entry-2")!!
        coordinator.registerWebInstance("album-entry-2", webInstanceId = 1L)

        coordinator.reportDestinationHero(
            route = "album/reused",
            key = "release:reused",
            heroRectInRoot = Rect(0f, 100f, 720f, 820f),
            firstPaint = true,
            navEntryId = "album-entry-1",
            identity = "album:reused",
            renderToken = 99L,
            decoded = true,
            drawn = true,
            webInstanceId = 1L,
            loadAttempt = 2,
            expectedGeneration = gen1,
        )
        assertNull(coordinator.destinationHeroRect("album:reused"))

        coordinator.reportDestinationHero(
            route = "album/reused",
            key = "release:reused",
            heroRectInRoot = Rect(0f, 120f, 720f, 840f),
            firstPaint = true,
            navEntryId = "album-entry-2",
            identity = "album:reused",
            renderToken = 2L,
            decoded = true,
            drawn = true,
            webInstanceId = 1L,
            loadAttempt = 1,
            expectedGeneration = gen2,
        )
        assertEquals(Rect(0f, 120f, 720f, 840f), coordinator.destinationHeroRect("album:reused"))
        assertTrue(coordinator.activeMorph?.destinationDrawn == true)
    }

    @Test
    fun coverCoordinator_p2_1_earlyBackWhileWaiting_cancelsWithoutFakeMorph_midFlightReverses() {
        val coordinator = CoverTransitionCoordinator()
        coordinator.updateViewportBounds(Rect(0f, 0f, 720f, 1616f))
        coordinator.updateShelfState(ownerEntryId = "shelf-1", page = 0, layoutVersion = 1L)
        coordinator.updateSource(
            key = "release:early",
            model = "https://example.invalid/early.jpg",
            aspectRatio = 1f,
            bounds = Rect(40f, 300f, 240f, 500f),
            sleeve = true,
            visible = true,
            ownerEntryId = "shelf-1",
            page = 0,
            layoutVersion = 1L,
            imageLoaded = true,
        )

        // Waiting while unbound or already settled at destination without drawn hero cancels cleanly.
        assertTrue(
            coordinator.startEnter(
                key = "release:early",
                route = "album/early",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "early",
            ),
        )
        assertNull(
            coordinator.beginReturnSession(
                key = "release:early",
                route = "album/early",
                identity = "album:early",
                physicalMotion = true,
                sourceNavEntryId = "album-early",
                targetNavEntryId = "shelf-1",
                interactive = false,
            ),
        )
        assertNull(coordinator.activeMorph)

        // Bound + mid-flight overlay reverses smoothly from visual rect.
        assertTrue(
            coordinator.startEnter(
                key = "release:early",
                route = "album/early",
                predictedToRect = Rect(0f, 120f, 720f, 840f),
                physicalMotion = true,
                sourceNavEntryId = "shelf-1",
                releaseId = "early",
            ),
        )
        val gen = coordinator.bindDestinationEntry("album/early", "release:early", "album-early")!!
        val midRect = Rect(18f, 200f, 500f, 682f)
        coordinator.updateVisualProgress(gen, progress = 0.45f, visualRect = midRect)
        val midReturn = coordinator.beginReturnSession(
            key = "release:early",
            route = "album/early",
            identity = "album:early",
            physicalMotion = true,
            sourceNavEntryId = "album-early",
            targetNavEntryId = "shelf-1",
            interactive = false,
        )
        assertNotNull(midReturn)
        assertEquals(midRect, coordinator.activeMorph?.fromRect)
    }
}
