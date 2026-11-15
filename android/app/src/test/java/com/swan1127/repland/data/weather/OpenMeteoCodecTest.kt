package com.swan1127.repland.data.weather

import com.swan1127.repland.domain.model.WeatherPlace
import com.swan1127.repland.domain.model.WeatherSky
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vendor's JSON against this app's vocabulary.
 *
 * **Why this file exists even though nothing here branches.** Every one of these fixtures is a
 * response shape remembered from the service's own documentation, and the widget's weather row is
 * drawn from whatever [OpenMeteoCodec] makes of them. A field renamed, an array that comes back as
 * `[null]`, a code nobody mapped — each of those shows up on someone's home screen as a wrong
 * number or a missing line, with no crash and no log to follow. The parser is the only place the
 * third party's format touches the user, so it is the only place worth pinning.
 *
 * The bodies are written the way the service writes them, including the parts the app ignores,
 * because a fixture that only contains the fields you expect proves nothing about the ones you do
 * not.
 */
class OpenMeteoCodecTest {

    private val shanghai = WeatherPlace("上海", "上海市", 31.2222, 121.4581, "Asia/Shanghai")
    private val observed = LocalDateTime.of(2026, 10, 20, 14, 5)

    // ---------------------------------------------------------------- requests

    @Test
    fun `the forecast request names the place by coordinate and asks for the place's own hours`() {
        val url = OpenMeteoCodec.forecastUrl(shanghai)
        assertTrue(url.startsWith("https://api.open-meteo.com/v1/forecast?"))
        assertTrue("latitude: $url", url.contains("latitude=31.2222"))
        assertTrue("longitude: $url", url.contains("longitude=121.4581"))
        assertTrue("timezone: $url", url.contains("timezone=auto"))
        assertTrue("current fields: $url", url.contains("temperature_2m"))
        assertTrue("daily fields: $url", url.contains("daily=uv_index_max"))
        // Nothing is asked for that no surface reads. Sunset was dropped along with the 「天黑前收尾」
        // idea; re-requesting it would widen the query the vendor sees for no user-visible answer.
        assertTrue("unsettled field requested: $url", !url.contains("sunset"))
    }

    @Test
    fun `a coordinate too small for plain decimal notation is still written as a decimal`() {
        // Double.toString turns 0.0000001 into `1.0E-7`, which the service rejects as an invalid
        // number — the request would fail with no hint of why.
        val url = OpenMeteoCodec.forecastUrl(WeatherPlace("赤道", "", 0.0000001, -0.5, "UTC"))
        assertTrue("got $url", url.contains("latitude=0.0000"))
        assertTrue("got $url", url.contains("longitude=-0.5000"))
    }

    @Test
    fun `a typed name is percent-encoded rather than pasted into the query`() {
        val url = OpenMeteoCodec.geocodingUrl("  乌鲁木齐  ")
        assertTrue("space and trimming: $url", url.contains("name=%E4%B9%8C%E9%B2%81%E6%9C%A8%E9%BD%90"))
        assertTrue("language: $url", url.contains("language=zh"))
        assertTrue("no raw text: $url", !url.contains("乌鲁木齐"))
    }

    // ---------------------------------------------------------------- the reading

    @Test
    fun `a full response becomes a reading with every field the widget can show`() {
        val reading = OpenMeteoCodec.parseReading(FULL_FORECAST, observed)
        assertEquals(WeatherSky.RAIN, reading?.sky)
        // 18.6 rounds to 19 rather than truncating to 18: the row is a glance, not a measurement.
        assertEquals(19, reading?.temperatureCelsius)
        assertEquals(17, reading?.apparentTemperatureCelsius)
        assertEquals(80, reading?.precipitationChancePercent)
        assertEquals(1.2, reading?.uvIndexMax)
        assertEquals(observed, reading?.observedAt)
    }

    @Test
    fun `the reading is stamped with the app's clock, not the response's`() {
        // `current.time` says 09:00 in the fixture's own zone. Trusting it would let a stale cached
        // answer look current, or a fresh one look stale, depending on a server the app cannot audit.
        val reading = OpenMeteoCodec.parseReading(FULL_FORECAST, observed)
        assertEquals(observed, reading?.observedAt)
    }

    @Test
    fun `what is not there stays not there`() {
        // A body without the optional fields must not invent them: `precipitation 0%` is a claim
        // about the sky that nobody made.
        val reading = OpenMeteoCodec.parseReading(MINIMAL_FORECAST, observed)
        assertEquals(WeatherSky.CLEAR, reading?.sky)
        assertEquals(6, reading?.temperatureCelsius)
        assertEquals(6, reading?.apparentTemperatureCelsius)
        assertNull(reading?.precipitationChancePercent)
        assertNull(reading?.uvIndexMax)
    }

    @Test
    fun `a daily array of nulls is an absence rather than a zero`() {
        // The service really does return `"uv_index_max": [null]` for a day it cannot compute one
        // for. Parsing that as 0.0 would be a claim about the sun that nobody made — and 0 is a
        // plausible value, so nothing downstream would notice.
        val reading = OpenMeteoCodec.parseReading(NULL_ARRAY_FORECAST, observed)
        assertEquals(WeatherSky.SNOW, reading?.sky)
        assertNull(reading?.uvIndexMax)
    }

    @Test
    fun `a reading the app would have to lie about is refused`() {
        // No sky, no temperature: either one would make the row say something about weather that the
        // response did not say. `null` is the caller's cue to keep the cache it already had.
        assertNull(OpenMeteoCodec.parseReading("""{"current":{"temperature_2m":10.0}}""", observed))
        assertNull(OpenMeteoCodec.parseReading("""{"current":{"weather_code":0}}""", observed))
        assertNull(OpenMeteoCodec.parseReading("""{"latitude":31.2}""", observed))
        assertNull(OpenMeteoCodec.parseReading("not json at all", observed))
    }

    @Test
    fun `an out-of-range decoration is dropped without losing the substance`() {
        // UV is an annotation; temperature is the row. A nonsense UV must not cost the user their
        // reading of the day.
        val reading = OpenMeteoCodec.parseReading(RIDICULOUS_UV_FORECAST, observed)
        assertEquals(20, reading?.temperatureCelsius)
        assertNull(reading?.uvIndexMax)
    }

    @Test
    fun `every weather code this service emits has a word`() {
        assertEquals(WeatherSky.CLEAR, OpenMeteoCodec.sky(0))
        assertEquals(WeatherSky.PARTLY_CLOUDY, OpenMeteoCodec.sky(2))
        assertEquals(WeatherSky.CLOUDY, OpenMeteoCodec.sky(3))
        assertEquals(WeatherSky.FOG, OpenMeteoCodec.sky(45))
        assertEquals(WeatherSky.DRIZZLE, OpenMeteoCodec.sky(53))
        assertEquals(WeatherSky.RAIN, OpenMeteoCodec.sky(65))
        assertEquals(WeatherSky.RAIN, OpenMeteoCodec.sky(82)) // 强阵雨
        assertEquals(WeatherSky.SNOW, OpenMeteoCodec.sky(75))
        assertEquals(WeatherSky.SNOW, OpenMeteoCodec.sky(86)) // 阵雪
        assertEquals(WeatherSky.THUNDER, OpenMeteoCodec.sky(99))
        // A code the service invents later says nothing, which is the honest answer.
        assertEquals(WeatherSky.OTHER, OpenMeteoCodec.sky(12345))
    }

    // ---------------------------------------------------------------- the places

    @Test
    fun `a search keeps the region, which is the only way to tell two same-named cities apart`() {
        val places = OpenMeteoCodec.parsePlaces(GEOCODING_BODY)
        assertEquals(2, places.size)
        assertEquals("上海 · 上海市", places[0].displayName)
        assertEquals("上海 · 丽江市", places[1].displayName)
        assertEquals("Asia/Shanghai", places[0].timezone)
    }

    @Test
    fun `a place with no region falls back to its country`() {
        val places = OpenMeteoCodec.parsePlaces("""{"results":[{"name":"Singapore","latitude":1.29,
            "longitude":103.85,"timezone":"Asia/Singapore","country":"Singapore"}]}""".trimIndent())
        assertEquals("Singapore", places.single().admin1)
    }

    @Test
    fun `an unusable result is dropped and a no-match answer is an empty list, not an error`() {
        // A coordinate-less or timezone-less row would throw inside WeatherPlace; the rest of the
        // list still names places the user can choose.
        val partial = OpenMeteoCodec.parsePlaces(
            """{"results":[{"name":"没有坐标"},{"name":"有坐标","latitude":1.0,"longitude":2.0,
              "timezone":"UTC"}]}""",
        )
        assertEquals(listOf("有坐标"), partial.map { it.name })
        assertEquals(emptyList<WeatherPlace>(), OpenMeteoCodec.parsePlaces("""{"results":[]}"""))
        assertEquals(emptyList<WeatherPlace>(), OpenMeteoCodec.parsePlaces("{}"))
        assertEquals(emptyList<WeatherPlace>(), OpenMeteoCodec.parsePlaces("garbage"))
    }

    private companion object {

        private val FULL_FORECAST = """
            {"latitude":31.2,"longitude":121.4,"timezone":"Asia/Shanghai",
             "current":{"time":"2026-10-20T09:00","interval":900,"temperature_2m":18.6,
                        "apparent_temperature":16.8,"weather_code":61,"precipitation_probability":80.4},
             "daily":{"time":["2026-10-20"],"uv_index_max":[1.2]}}
        """.trimIndent()

        private val MINIMAL_FORECAST = """
            {"latitude":31.2,"current":{"time":"2026-10-20T09:00","temperature_2m":6.4,"weather_code":0}}
        """.trimIndent()

        private val NULL_ARRAY_FORECAST = """
            {"current":{"temperature_2m":-3.0,"weather_code":73},
             "daily":{"time":["2026-10-20"],"uv_index_max":[null]}}
        """.trimIndent()

        private val RIDICULOUS_UV_FORECAST = """
            {"current":{"temperature_2m":20.0,"weather_code":1},
             "daily":{"uv_index_max":[940.5]}}
        """.trimIndent()

        private val GEOCODING_BODY = """
            {"results":[
              {"id":1790714,"name":"上海","latitude":31.2222,"longitude":121.4581,
               "admin1":"上海市","country":"中国","timezone":"Asia/Shanghai"},
              {"id":2038832,"name":"上海","latitude":26.86,"longitude":100.21,
               "admin1":"丽江市","country":"中国","timezone":"Asia/Shanghai"}]}
        """.trimIndent()
    }
}
