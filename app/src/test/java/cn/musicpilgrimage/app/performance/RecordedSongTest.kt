package cn.musicpilgrimage.app.performance

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RecordedSongTest {
    private fun packageJson()=JSONObject("""{
        "id":"example","version":1,"title":"录音测试","artist":"测试","referenceEdition":"测试版",
        "releaseRhythmVerified":true,"durationUs":3000000,"placeIds":[],"sources":{},
        "recording":{"sampleRate":44100,"variants":[{"speed":1.0,"file":"original.pcmz"},{"speed":0.9,"file":"slower.pcmz"}]},
        "charts":{"easy":[{"onsetUs":1000000,"durationUs":100000,"lane":0,"hold":false}],
                  "standard":[{"onsetUs":1000000,"durationUs":100000,"lane":0,"hold":false},{"onsetUs":1500000,"durationUs":750000,"lane":1,"hold":true}]},
        "sections":[{"name":"测试","startUs":0,"endUs":3000000,"scene":"hall","bpm":80}]
    }""")
    @Test fun recordingTargetsHaveNoAudioOwnershipAndPreserveIndependentTiming() {
        val song=SongPackage.from(packageJson())
        assertTrue(song.events.isEmpty());assertTrue(song.easy.single().eventIds.isEmpty())
        assertEquals(1_500_000L,song.standard[1].onsetUs);assertEquals(750_000L,song.standard[1].durationUs)
        assertEquals(setOf(1.0,.9),song.recording!!.variants.keys)
    }
    @Test fun unverifiedContentAndUnsafeAudioPathsRemainRejected() {
        assertThrows(IllegalArgumentException::class.java){SongPackage.from(packageJson().put("releaseRhythmVerified",false))}
        val unsafe=packageJson();unsafe.getJSONObject("recording").getJSONArray("variants").getJSONObject(0).put("file","../other.pcmz")
        assertThrows(IllegalArgumentException::class.java){SongPackage.from(unsafe)}
        val ownership=packageJson();ownership.getJSONObject("charts").getJSONArray("easy").getJSONObject(0).put("eventIds",org.json.JSONArray())
        assertThrows(IllegalArgumentException::class.java){SongPackage.from(ownership)}
    }
    @Test fun localAudioRequiresBoundedOriginalFramesAndTrustedFileManifest() {
        fun local()=packageJson().also {song->song.put("recording",JSONObject("""{
            "sampleRate":44100,"storage":"local","encoding":"pcm16-delta","frames":132300,
            "variants":[{"speed":1.0,"file":"original.pcmz"}],
            "audioFiles":[{"file":"original.pcmz","bytes":1234,"sha256":"${"a".repeat(64)}"}]
        }"""))}
        val parsed=SongPackage.from(local()).recording!!
        assertTrue(parsed.local)
        assertEquals("pcm16-delta",parsed.encoding)
        val badEncoding=local();badEncoding.getJSONObject("recording").put("encoding","mp3")
        assertThrows(IllegalArgumentException::class.java){SongPackage.from(badEncoding)}
        val oversized=local();oversized.getJSONObject("recording").put("frames",Long.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java){SongPackage.from(oversized)}
        val missing=local();missing.getJSONObject("recording").remove("audioFiles")
        assertThrows(IllegalArgumentException::class.java){SongPackage.from(missing)}
        val unsafe=local();unsafe.getJSONObject("recording").getJSONArray("audioFiles").getJSONObject(0).put("file","../original.pcmz")
        assertThrows(IllegalArgumentException::class.java){SongPackage.from(unsafe)}
    }
    @Test fun standardOnlyPackageIsPlayableWithoutCreatingAnEasyEntry() {
        val json=packageJson();json.getJSONObject("charts").remove("easy")
        val song=SongPackage.from(json)
        assertEquals(2,song.targets(2).size)
        assertEquals(2,PlayOptions().difficulty)
    }
    @Test fun pcm16DeltaStreamDecodesExactStereoSamplesAndSha256() {
        val samples=shortArrayOf(12000,-12000,30000,-30000,-25000,25000,0,16384)
        val deltas=shortArrayOf(
            samples[0],samples[1],
            (samples[2]-samples[0]).toShort(),(samples[3]-samples[1]).toShort(),
            (samples[4]-samples[2]).toShort(),(samples[5]-samples[3]).toShort(),
            (samples[6]-samples[4]).toShort(),(samples[7]-samples[5]).toShort()
        )
        val raw=java.io.ByteArrayOutputStream().also {bytes->
            java.util.zip.GZIPOutputStream(bytes).use {gz->
                val buf=java.nio.ByteBuffer.allocate(deltas.size*2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                deltas.forEach(buf::putShort)
                gz.write(buf.array())
            }
        }.toByteArray()
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
        val recording=RecordedMusic(44100,mapOf(1.0 to "original.pcmz"),true,4,mapOf("original.pcmz" to RecordingFile(raw.size.toLong(),digest)),"pcm16-delta")
        val (decoded,actualDigest)=recording.decodeSamples(raw.inputStream(),4)
        assertEquals(digest,actualDigest)
        assertEquals(samples.size,decoded.size)
        samples.indices.forEach{i->assertEquals(samples[i]/32768f,decoded[i],1e-6f)}
    }
    @Test fun allFifteenConfiguredAssetsParseValidlyInPrescribedOrder() {
        val root=java.io.File("src/main/assets/performance")
        val folders=root.listFiles()?.filter{it.isDirectory&&it.name!="piano"}?.map{it.name}.orEmpty()
        assertEquals(15,PerformanceCatalog.ORDERED_SONG_IDS.size)
        assertEquals(PerformanceCatalog.ORDERED_SONG_IDS.toSet(),folders.toSet())
        PerformanceCatalog.ORDERED_SONG_IDS.forEach {id->
            val file=java.io.File(root,"$id/song.json")
            assertTrue("Missing song.json for $id",file.isFile)
            val song=SongPackage.from(JSONObject(file.readText(Charsets.UTF_8)))
            assertEquals(id,song.id)
            assertTrue(song.recording!!.local)
            assertEquals("pcm16-delta",song.recording!!.encoding)
            assertTrue(song.durationUs in 90_000_000..120_000_000)
            assertTrue(song.standard.size>=140)
            assertTrue(song.standard.any{it.hold})
            assertTrue(song.placeIds.isNotEmpty())
            if(id!=PerformanceCatalog.SHARED_BACKGROUND_FOLDER) {
                assertFalse("Duplicate background should not exist in $id",java.io.File(root,"$id/${song.background}").exists())
            }
        }
    }
}
