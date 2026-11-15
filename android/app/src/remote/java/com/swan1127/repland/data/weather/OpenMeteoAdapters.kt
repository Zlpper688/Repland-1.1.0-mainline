package com.swan1127.repland.data.weather

import com.swan1127.repland.domain.model.WeatherPlace
import com.swan1127.repland.domain.model.WeatherPlaceSearch
import com.swan1127.repland.domain.model.WeatherReading
import com.swan1127.repland.domain.model.WeatherSource
import java.time.LocalDateTime

/**
 * The only class in this app that asks a third party about the sky.
 *
 * Loaded reflectively by `AppContainer`, exactly like the AI advisors, so the release build has no
 * weather at all rather than a broken one. What leaves the device is one HTTPS GET whose query is a
 * coordinate pair the user chose by typing it — no key, no account, and nothing that identifies the
 * phone.
 */
class OpenMeteoWeatherSource : WeatherSource {

    override suspend fun fetch(place: WeatherPlace): WeatherReading? {
        val body = openMeteoGet(OpenMeteoCodec.forecastUrl(place)) ?: return null
        // Stamped locally, not taken from the response: `observedAt` is what decides whether the
        // widget calls its own answer current or stale, and a server clock the app cannot check
        // should not get to make that call.
        return OpenMeteoCodec.parseReading(body, LocalDateTime.now())
    }
}

/**
 * Turns the city name the user typed into the places that answer to it.
 *
 * Never a single result: the service ranks several places under one name, and picking for the user
 * would put somebody else's weather on their home screen. The UI shows the list, including the
 * region, and this returns it in the order the service gave.
 */
class OpenMeteoPlaceSearch : WeatherPlaceSearch {

    override suspend fun search(query: String): List<WeatherPlace>? {
        if (query.isBlank()) return emptyList()
        // null, not emptyList(): a sky that could not be reached is not a place that does not exist.
        val body = openMeteoGet(OpenMeteoCodec.geocodingUrl(query)) ?: return null
        return OpenMeteoCodec.parsePlaces(body)
    }
}
