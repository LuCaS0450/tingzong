package cn.musicpilgrimage.app.ui.room

import org.junit.Assert.*
import org.junit.Test

class GlobeProjectionTest {
    @Test fun cameraCentreProjectsToFrontCentre() {
        for(camera in listOf(GlobeCamera(35f,24f),GlobeCamera(179f,-60f),GlobeCamera(-179f,75f))) {
            val point=globeProject(camera.latitude.toDouble(),camera.longitude.toDouble(),camera)
            assertEquals(0f,point.x,.00001f);assertEquals(0f,point.y,.00001f);assertEquals(1f,point.depth,.00001f)
        }
    }
    @Test fun backHemisphereIsHiddenAndDatelineRemainsContinuous() {
        assertTrue(globeProject(0.0,180.0,GlobeCamera(0f,0f)).depth<0)
        val across=globeProject(0.0,-179.0,GlobeCamera(179f,0f))
        assertTrue(across.depth>.99f);assertTrue(across.x>0)
        assertEquals(GlobeCamera(-170f,80f,2.4f),GlobeCamera(550f,100f,10f).bounded())
    }
}
