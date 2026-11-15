package com.swan1127.repland.data.weather

import com.swan1127.repland.data.json.StrictJson
import com.swan1127.repland.domain.model.WeatherPlace
import com.swan1127.repland.domain.model.WeatherReading
import com.swan1127.repland.domain.model.WeatherSky
import java.net.URLEncoder
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The pure half of the Open-Meteo adapter: query building and response reading, no IO.
 *
 * Split out for the same reason the AI codec is: a JVM unit test can hand it a response body and
 * check what the widget would say, which is the part most likely to be wrong. Every literal here,
 * both host names and parameter names, belongs to one vendor, which is what lets the domain talk
 * about "the sky" instead of about an API.
 *
 * Parsing never throws: an unreadable body becomes null, and null means "keep saying what was last
 * known". A missing required field fails the whole reading while an optional one is dropped.
 */
object OpenMeteoCodec {

    private const val FORECAST_ENDPOINT = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODING_ENDPOINT = "https://geocoding-api.open-meteo.com/v1/search"

    /** Names a user is unlikely to scroll past, and well under the service's own cap. */
    private const val GEOCODING_RESULT_COUNT = 8

    /**
     * `current` carries what is true now; `daily` carries the one day-long figure worth annotating
     * with, which is how strong the UV actually gets rather than what it is at this minute.
     * `timezone=auto` makes that local to the chosen city rather than to the phone.
     *
     * Sunset is deliberately not asked for. It was fetched for a 「天黑前收尾」 hint that turned out to
     * be undeliverable: in autumn and winter nearly every evening block runs past sunset, so the
     * line would be permanent noise, and nothing in a [com.swan1127.repland.domain.model.TimelineEntry]
     * says whether a block is outdoors in the first place. A field with no consumer is also a field
     * the privacy note has to keep explaining.
     */
    fun forecastUrl(place: WeatherPlace): String = FORECAST_ENDPOINT +
        "?latitude=" + coordinate(place.latitude) +
        "&longitude=" + coordinate(place.longitude) +
        "&current=temperature_2m,apparent_temperature,weather_code,precipitation_probability" +
        "&daily=uv_index_max" +
        "&timezone=auto&forecast_days=1"

    /**
     * `language=zh` because the app's city list is Chinese-named: a user typing the name of Shanghai
     * wants the municipality, not the village of the same name elsewhere, which is why the search
     * returns several results and the UI makes the user choose one.
     */
    fun geocodingUrl(query: String): String = GEOCODING_ENDPOINT +
        "?name=" + URLEncoder.encode(query.trim(), "UTF-8") +
        "&count=$GEOCODING_RESULT_COUNT&language=zh&format=json"

    /** Fixed decimals: a bare Double.toString can produce 1.0E-7, which the API rejects. */
    private fun coordinate(value: Double): String = String.format(Locale.US, "%.4f", value)

    /** The reading for [forecastUrl], stamped as observed at [observedAt], or null. */
    fun parseReading(body: String, observedAt: LocalDateTime): WeatherReading? = runCatching {
        val root = StrictJson.parseObject(body)
        val current = root["current"] as? Map<*, *> ?: return null
        val temperature = (current["temperature_2m"] as? Number)?.toDouble()?.roundToInt() ?: return null
        val code = (current["weather_code"] as? Number)?.toInt() ?: return null
        WeatherReading(
            sky = sky(code),
            temperatureCelsius = temperature,
            apparentTemperatureCelsius = (current["apparent_temperature"] as? Number)
                ?.toDouble()?.roundToInt() ?: temperature,
            precipitationChancePercent = (current["precipitation_probability"] as? Number)
                ?.toDouble()?.roundToInt()?.takeIf { it in 0..100 },
            uvIndexMax = dailyNumbers(root, "uv_index_max").firstOrNull { it in 0.0..15.0 },
            observedAt = observedAt,
        )
    }.getOrNull()

    /** Places for [geocodingUrl], in the order the service ranked them. */
    fun parsePlaces(body: String): List<WeatherPlace> = runCatching {
        val results = StrictJson.parseObject(body)["results"] as? List<*> ?: return emptyList()
        results.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val latitude = (map["latitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val longitude = (map["longitude"] as? Number)?.toDouble() ?: return@mapNotNull null
            val timezone = (map["timezone"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            runCatching { WeatherPlace(name, regionOf(map), latitude, longitude, timezone) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** A place with no first-level region still needs something to disambiguate it by. */
    private fun regionOf(map: Map<*, *>): String =
        (map["admin1"] as? String).orEmpty().takeIf(String::isNotBlank)
            ?: (map["country"] as? String).orEmpty()

    /**
     * WMO 4677 interpretation codes, as this service reports them.
     *
     * Exhaustive for the codes the service emits: freezing rain is rain and snow showers are snow,
     * because the widget has one job, which is to say what to expect outside. OTHER is only
     * reachable from a code invented later, and its label says nothing rather than guessing.
     */
    fun sky(code: Int): WeatherSky = when (code) {
        0 -> WeatherSky.CLEAR
        1, 2 -> WeatherSky.PARTLY_CLOUDY
        3 -> WeatherSky.CLOUDY
        45, 48 -> WeatherSky.FOG
        51, 53, 55, 56, 57 -> WeatherSky.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> WeatherSky.RAIN
        71, 73, 75, 77, 85, 86 -> WeatherSky.SNOW
        95, 96, 99 -> WeatherSky.THUNDER
        else -> WeatherSky.OTHER
    }

    // Daily variables come back as parallel arrays even for a single day, and an element can be
    // null, so "the first usable one" is its own step.
    private fun dailyValues(root: Map<String, Any?>, key: String): List<*> =
        ((root["daily"] as? Map<*, *>)?.get(key) as? List<*>).orEmpty()

    private fun dailyNumbers(root: Map<String, Any?>, key: String): List<Double> =
        dailyValues(root, key).filterIsInstance<Number>().map { it.toDouble() }
}
