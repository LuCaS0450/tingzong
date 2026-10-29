package cn.musicpilgrimage.app

import androidx.test.platform.app.InstrumentationRegistry
import cn.musicpilgrimage.app.performance.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PerformanceAudioImportTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun zip(vararg entries:Pair<String,ByteArray>):ByteArray=ByteArrayOutputStream().also{bytes->
        ZipOutputStream(bytes).use{stream->entries.forEach{(name,data)->stream.putNextEntry(ZipEntry(name));stream.write(data);stream.closeEntry()}}
    }.toByteArray()
    @Test fun verifiedImportAndInvalidReplacementPreserveExistingAudio() {
        val root=File(context.cacheDir,"audio-import-${UUID.randomUUID()}")
        val bytes=ByteArray(128){it.toByte()}
        val digest=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val recording=RecordedMusic(44100,mapOf(1.0 to "original.pcmz"),true,44100,mapOf("original.pcmz" to RecordingFile(bytes.size.toLong(),digest)))
        val song=SongPackage("import-test",1,"测试","测试","测试",1_000_000,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),JSONObject(),recording)
        val store=PerformanceAudioStore(context,root)
        try {
            assertNull(store.directory(song))
            store.import(song,ByteArrayInputStream(zip("original.pcmz" to bytes)))
            val installed=requireNotNull(store.directory(song))
            assertArrayEquals(bytes,File(installed,"original.pcmz").readBytes())
            listOf(
                zip("../original.pcmz" to bytes),
                zip("original.pcmz" to bytes.reversedArray()),
                zip("original.pcmz" to bytes.copyOf(10)),
                zip("original.pcmz" to bytes.copyOf(129)),
                zip("original.pcmz" to bytes,"song.json" to byteArrayOf(1))
            ).forEach{bad->
                assertThrows(Exception::class.java){store.import(song,ByteArrayInputStream(bad))}
                assertArrayEquals(bytes,File(requireNotNull(store.directory(song)),"original.pcmz").readBytes())
            }
            // A provider failure half way through a replacement also leaves the good copy intact.
            assertThrows(Exception::class.java){store.import(song,object:java.io.InputStream(){override fun read():Int=throw java.io.IOException("provider interrupted")})}
            assertArrayEquals(bytes,File(requireNotNull(store.directory(song)),"original.pcmz").readBytes())
        } finally {root.deleteRecursively()}
    }
    @Test fun installAuthorizedFullRecordingIntoFormalLibrary() {
        val song=PerformanceCatalog.load(context).single{it.id=="greatest-works"}
        assertEquals("release-accent-rhythm",song.sources.getString("chartKind"))
        assertEquals("pcm16-delta",song.recording?.encoding)
        assertTrue(song.durationUs in 90_000_000..120_000_000)
        assertTrue(song.standard.size>=160)
        assertTrue(song.standard.count{it.hold}>=4)
        val chart=context.assets.open("performance/greatest-works/song.json").bufferedReader().use{JSONObject(it.readText())}.getJSONObject("charts")
        assertFalse(chart.has("easy"))
        val targets=chart.getJSONArray("standard")
        val contributors=List(targets.length()){index->targets.getJSONObject(index).getJSONArray("contributors").let{values->List(values.length()){values.getString(it)}}}
        assertTrue(contributors.count{"vocals" in it}>20)
        assertTrue(contributors.count{"piano" in it}>20)
        val source=File(context.getExternalFilesDir(null),"Greatest-Works-full-audio.zip")
        assertTrue("需提供已获授权的本地整曲音源",source.isFile)
        val store=PerformanceAudioStore(context)
        source.inputStream().use{store.import(song,it)}
        assertNotNull(store.directory(song))
        assertTrue(PerformanceEntry(song,audioDirectory=store.directory(song)).audioReady)
    }
}
