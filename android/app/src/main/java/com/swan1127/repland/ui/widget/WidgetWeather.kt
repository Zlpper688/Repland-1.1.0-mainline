package com.swan1127.repland.ui.widget

import com.swan1127.repland.R
import com.swan1127.repland.domain.model.WeatherReading
import com.swan1127.repland.domain.model.WeatherSky
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * What the weather row is allowed to say, and when.
 *
 * **Why these rules are here rather than inline in the provider.** [weatherNeedsRefresh] decides
 * whether a home-screen redraw is worth an HTTPS request, and [WeatherSky.labelRes] decides which
 * word the user reads. Both are the kind of thing that goes quietly wrong — an off-by-one in the
 * window hammers a free API, a mis-pointed enum prints 雪 where it rained — and neither can be
 * caught by rendering, because there is no Robolectric here. They are pure and tested as such.
 *
 * **Why the widget asks at all, instead of the app.** The row is drawn by a broadcast receiver that
 * already runs its work on the IO dispatcher with `goAsync()`, so a fetch fits without a second
 * scheduler. It is also the only moment the answer has to be right: the app can be closed for hours
 * and the widget still has to be readable.
 */

/**
 * How long a reading stays on screen.
 *
 * One window covers both consequences of it, and they have to agree: a row older than this is
 * labelled as a cache instead of as current, and a redraw asks the service again. Two constants for
 * one number would be a bug waiting to happen — if the refresh window were shorter than the stale
 * window, the widget would pay for a call and then still label its own answer stale.
 *
 * The redraw itself fires at every schedule boundary, which on a busy afternoon is far more often
 * than an hour; this is what keeps that from becoming a poll.
 */
const val WEATHER_FRESH_WINDOW_MINUTES: Long = 60

/** Below this, a chance of rain is noise on a line whose whole job is a single glance. */
const val WEATHER_PRECIPITATION_MENTION_PERCENT = 30

/** The chance worth printing, or null when the sky did not say one or said too little of one. */
fun weatherPrecipitationMention(reading: WeatherReading): Int? =
    reading.precipitationChancePercent?.takeIf { it >= WEATHER_PRECIPITATION_MENTION_PERCENT }

/**
 * The one thing worth doing about the sky, or nothing.
 *
 * **Why one, and why after the raw numbers.** A 4×3 widget has room for a glance, not for advice;
 * five suggestions is a list nobody reads and a row that wraps into the schedule. So this returns at
 * most one, ordered by what would be worse to ignore: getting caught in a storm, then getting wet,
 * then sunburn, then being cold, then being hot.
 *
 * **Why it is a word and never a change.** The row may say 「带伞」; it may not move, shorten or mark
 * anything. An external signal that could edit the schedule would make the widget's answer depend on
 * a third party's uptime, which is the one thing this app's rules forbid.
 */
enum class WeatherHint { THUNDER, RAIN_GEAR, SUN, COLD, HEAT }

// The thresholds the two annotation words hang on. A suggestion needs more certainty than a number:
// 「降水 40%」 is worth printing, 「带伞」 is not, until the sky is actually saying rain — hence 60 here
// and 30 above. Feels-like rather than the raw temperature decides comfort, because that is what a
// layer of clothing, or shade, responds to.
private const val WEATHER_RAIN_CERTAINTY_PERCENT = 60
private const val WEATHER_UV_MENTION = 6.0
private const val WEATHER_COLD_FEELS_LIKE = 5
private const val WEATHER_HOT_FEELS_LIKE = 33

fun weatherHint(reading: WeatherReading): WeatherHint? {
    val wet = reading.sky == WeatherSky.RAIN || reading.sky == WeatherSky.DRIZZLE ||
        reading.sky == WeatherSky.SNOW ||
        (reading.precipitationChancePercent ?: 0) >= WEATHER_RAIN_CERTAINTY_PERCENT
    return when {
        reading.sky == WeatherSky.THUNDER -> WeatherHint.THUNDER
        wet -> WeatherHint.RAIN_GEAR
        (reading.uvIndexMax ?: 0.0) >= WEATHER_UV_MENTION -> WeatherHint.SUN
        reading.apparentTemperatureCelsius <= WEATHER_COLD_FEELS_LIKE -> WeatherHint.COLD
        reading.apparentTemperatureCelsius >= WEATHER_HOT_FEELS_LIKE -> WeatherHint.HEAT
        else -> null
    }
}

/** The place name and the sky it was read under, as one row's worth of truth. */
data class WeatherStatus(val place: String, val reading: WeatherReading)

fun weatherNeedsRefresh(cached: WeatherReading?, now: LocalDateTime): Boolean =
    cached == null || ChronoUnit.MINUTES.between(cached.observedAt, now) >= WEATHER_FRESH_WINDOW_MINUTES

/**
 * Whether the row may claim the sky is what it says right now.
 *
 * A reading stamped in the future (a clock that moved backwards) counts as fresh rather than stale:
 * the honest reading of "this is newer than my clock" is not a cache warning the user cannot
 * interpret.
 */
fun weatherIsFresh(reading: WeatherReading, now: LocalDateTime): Boolean =
    ChronoUnit.MINUTES.between(reading.observedAt, now) < WEATHER_FRESH_WINDOW_MINUTES

/**
 * When this reading was taken, in the smallest form that is still unambiguous.
 *
 * A date only appears once the reading stops being from today, so a stale row never says
 * 「10-20 09:12」 about something two hours old.
 */
fun weatherObservedLabel(reading: WeatherReading, now: LocalDateTime): String =
    reading.observedAt.format(
        if (reading.observedAt.toLocalDate() == now.toLocalDate()) WEATHER_CLOCK else WEATHER_CLOCK_WITH_DAY,
    )

/** The word the user reads for this sky. [WeatherSky] is pure and cannot name a resource. */
val WeatherSky.labelRes: Int
    get() = when (this) {
        WeatherSky.CLEAR -> R.string.widget_wx_clear
        WeatherSky.PARTLY_CLOUDY -> R.string.widget_wx_partly_cloudy
        WeatherSky.CLOUDY -> R.string.widget_wx_cloudy
        WeatherSky.FOG -> R.string.widget_wx_fog
        WeatherSky.DRIZZLE -> R.string.widget_wx_drizzle
        WeatherSky.RAIN -> R.string.widget_wx_rain
        WeatherSky.SNOW -> R.string.widget_wx_snow
        WeatherSky.THUNDER -> R.string.widget_wx_thunder
        WeatherSky.OTHER -> R.string.widget_wx_other
    }

/** The glyph drawn beside the word. */
val WeatherSky.iconRes: Int
    get() = when (this) {
        WeatherSky.CLEAR -> R.drawable.widget_ic_sunny
        WeatherSky.PARTLY_CLOUDY -> R.drawable.widget_ic_partly
        WeatherSky.CLOUDY -> R.drawable.widget_ic_cloudy
        WeatherSky.FOG -> R.drawable.widget_ic_fog
        WeatherSky.DRIZZLE -> R.drawable.widget_ic_drizzle
        WeatherSky.RAIN -> R.drawable.widget_ic_rain
        WeatherSky.SNOW -> R.drawable.widget_ic_snow
        WeatherSky.THUNDER -> R.drawable.widget_ic_thunder
        WeatherSky.OTHER -> R.drawable.widget_ic_cloudy
    }

/** The landscape behind the whole widget, picked by the same sky as the icon. */
val WeatherSky.sceneRes: Int
    get() = when (this) {
        WeatherSky.CLEAR -> R.drawable.widget_bg_sunny
        WeatherSky.PARTLY_CLOUDY, WeatherSky.CLOUDY, WeatherSky.FOG, WeatherSky.DRIZZLE ->
            R.drawable.widget_bg_cloudy
        WeatherSky.RAIN, WeatherSky.THUNDER -> R.drawable.widget_bg_rainy
        WeatherSky.SNOW -> R.drawable.widget_bg_snowy
        WeatherSky.OTHER -> R.drawable.widget_background
    }

/**
 * 背景 = 天空 × 时辰：晴/云在黄昏（17–19 点）换暮色、夜里换夜色；
 * 雨雪雷不按时辰换——它们的景本身就是天气的答案。没有读数维持素底。
 */
fun sceneResFor(sky: WeatherSky?, hour: Int): Int {
    val base = sky?.sceneRes ?: R.drawable.widget_background
    val daylit = when (sky) {
        WeatherSky.CLEAR, WeatherSky.PARTLY_CLOUDY, WeatherSky.CLOUDY, WeatherSky.FOG -> true
        else -> false
    }
    if (!daylit) return base
    return when {
        hour >= 20 || hour < 5 -> R.drawable.widget_bg_night
        hour >= 17 -> R.drawable.widget_bg_dusk
        else -> base
    }
}

/** The one thing worth doing about this sky. */
val WeatherHint.labelRes: Int
    get() = when (this) {
        WeatherHint.THUNDER -> R.string.widget_wx_hint_thunder
        WeatherHint.RAIN_GEAR -> R.string.widget_wx_hint_rain_gear
        WeatherHint.SUN -> R.string.widget_wx_hint_sun
        WeatherHint.COLD -> R.string.widget_wx_hint_cold
        WeatherHint.HEAT -> R.string.widget_wx_hint_heat
    }

private val WEATHER_CLOCK = DateTimeFormatter.ofPattern("HH:mm")
private val WEATHER_CLOCK_WITH_DAY = DateTimeFormatter.ofPattern("MM-dd HH:mm")
