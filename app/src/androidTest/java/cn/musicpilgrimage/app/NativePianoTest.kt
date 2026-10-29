package cn.musicpilgrimage.app

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import cn.musicpilgrimage.app.performance.NativePiano
import cn.musicpilgrimage.app.performance.PianoSamples
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import kotlin.math.sin

class NativePianoTest {
    @Test fun outputMuteSilencesBothAudioPathsWithoutStoppingTheSourceClock() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            for(recording in listOf(true,false))NativePiano().use {piano->
                val h=piano.handle
                if(recording)assertTrue(piano.setBacking(h,44100,1.0,FloatArray(44100*3*2){index->(sin(index/2.0*2*Math.PI*440/44100)*.18).toFloat()}))
                else {PianoSamples.load(context,piano);assertTrue(piano.setScore(h,doubleArrayOf(0.0,2.8,69.0,100.0,0.0)))}
                assertTrue(piano.start(h,0.0,2.8,1.0,0,60.0,0))
                await{piano.position(h)>80_000&&piano.outputPeak(h)>.001f}
                val before=piano.position(h)
                piano.setMuted(h,true)
                await{piano.position(h)>before+100_000&&piano.outputPeak(h)==0f}
                assertTrue(piano.isPlaying(h))
                piano.setMuted(h,false)
                await{piano.outputPeak(h)>.001f}
                assertTrue(piano.position(h)>before)
            }
        }
    }
    private fun await(check:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+3000
        while(!check() && SystemClock.elapsedRealtime()<deadline)Thread.sleep(10)
        assertTrue(check())
    }
    @Test fun completeStereoTrackIgnoresTouchesAndPausesItsSourceClockAtBothSpeeds() {
        ActivityScenario.launch(MainActivity::class.java).use {
            for(speed in listOf(1.0,.9))NativePiano().use {piano->
                val h=piano.handle;val sampleRate=44100
                val pcm=FloatArray((sampleRate/speed).toInt()*2){index->
                    (sin(index/2.0*2*Math.PI*440/sampleRate)*if(index%2==0).1 else .2).toFloat()
                }
                assertTrue(piano.setBacking(h,sampleRate,speed,pcm))
                assertFalse(piano.start(h,0.0,.8,if(speed==1.0).9 else 1.0,0,80.0,0))
                assertTrue(piano.start(h,0.0,.8,speed,0,80.0,0))
                await{piano.position(h)>=150_000}
                assertFalse(piano.hit(h,0,0.0));assertFalse(piano.release(h,0))
                assertEquals(0,piano.playingVoices(h));assertTrue(piano.isPlaying(h))
                piano.pause(h);val position=piano.position(h)
                Thread.sleep(100);assertEquals(position,piano.position(h))
                assertTrue(piano.start(h,position/1e6,.8,speed,0,80.0,0))
                await{!piano.isPlaying(h)}
                assertEquals(800_000L,piano.position(h));assertFalse(piano.failed(h))
            }
        }
    }
    @Test fun frameClockPausesAndMaskedMelodyOnlyStartsAfterTouchCommand() {
        ActivityScenario.launch(MainActivity::class.java).use {
            NativePiano().use {piano->
                val h=piano.handle
                PianoSamples.load(InstrumentationRegistry.getInstrumentation().targetContext,piano)
                assertTrue(piano.setScore(h,doubleArrayOf(.1,.6,69.0,100.0,1.0)))
                assertTrue(piano.start(h,0.0,1.2,1.0,1,110.0,0))
                await{piano.position(h)>=200_000}
                assertEquals(0,piano.playingVoices(h))
                assertTrue(piano.hit(h,0,0.0));await{piano.playingVoices(h)>0}
                piano.pause(h)
                val saved=piano.position(h);Thread.sleep(120);assertEquals(saved,piano.position(h))
                assertFalse(piano.isPlaying(h))
                assertTrue(piano.start(h,0.0,1.2,1.0,0,110.0,0))
                await{piano.position(h)>=180_000 && piano.playingVoices(h)>0}
                assertFalse(piano.failed(h))
            }
        }
    }
}
