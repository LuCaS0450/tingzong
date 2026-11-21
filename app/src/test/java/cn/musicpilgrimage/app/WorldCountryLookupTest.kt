package cn.musicpilgrimage.app

import cn.musicpilgrimage.app.ui.room.WorldCountryLookup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorldCountryLookupTest {

    @Test
    fun parseCountries_handlesOuterRingHolesAndWrap() {
        val sampleJson = """
            {
              "countries": [
                {
                  "name": "测试岛",
                  "areas": [
                    {
                      "bounds": [0.0, 0.0, 20.0, 20.0],
                      "wrap": false,
                      "rings": [
                        [[0.0, 0.0], [20.0, 0.0], [20.0, 20.0], [0.0, 20.0]],
                        [[6.0, 6.0], [14.0, 6.0], [14.0, 14.0], [6.0, 14.0]]
                      ]
                    }
                  ]
                },
                {
                  "name": "跨日界线国",
                  "areas": [
                    {
                      "bounds": [175.0, -20.0, 185.0, -10.0],
                      "wrap": true,
                      "rings": [
                        [[175.0, -20.0], [185.0, -20.0], [185.0, -10.0], [175.0, -10.0]]
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val countries = WorldCountryLookup.parseCountries(sampleJson)
        assertEquals("测试岛", WorldCountryLookup.countryAt(countries, 3.0, 3.0))
        // Inside hole must not match outer polygon.
        assertEquals("", WorldCountryLookup.countryAt(countries, 10.0, 10.0))
        // Wrapped negative longitude (-178 == 182) matches wrap=true area.
        assertEquals("跨日界线国", WorldCountryLookup.countryAt(countries, -178.0, -15.0))
    }

    @Test
    fun realCountriesAsset_resolvesRepresentativeCitiesAndOcean() {
        val assetFile = listOf(
            File("src/main/assets/design/swiss-world/countries.json"),
            File("app/src/main/assets/design/swiss-world/countries.json"),
        ).firstOrNull(File::exists)
        checkNotNull(assetFile) { "countries.json asset not found in workspace" }

        val countries = WorldCountryLookup.parseCountries(assetFile.readText())
        assertTrue("countries.json must load non-empty", countries.isNotEmpty())

        assertEquals("中华人民共和国", WorldCountryLookup.countryAt(countries, 116.4074, 39.9042))
        assertEquals("中国香港", WorldCountryLookup.countryAt(countries, 114.1694, 22.3193))
        assertEquals("法国", WorldCountryLookup.countryAt(countries, 2.3522, 48.8566))
        assertEquals("日本", WorldCountryLookup.countryAt(countries, 139.6917, 35.6895))
        assertEquals("", WorldCountryLookup.countryAt(countries, -140.0, 0.0))
    }
}
