package ai.gilbeot.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** NOAA 근사식으로 오프라인 일출·일몰 시각을 계산한다. */
object SunCalc {
    private const val ZENITH_DEG = 90.833

    /** 지정 날짜와 위치의 일몰을 계산하며 극지방에서 해가 지지 않으면 null이다. */
    fun sunset(date: LocalDate, lat: Double, lng: Double, zone: ZoneId): ZonedDateTime? =
        calculate(date, lat, lng, zone, sunrise = false)

    /** 지정 날짜와 위치의 일출을 계산하며 극지방에서 해가 뜨지 않으면 null이다. */
    fun sunrise(date: LocalDate, lat: Double, lng: Double, zone: ZoneId): ZonedDateTime? =
        calculate(date, lat, lng, zone, sunrise = true)

    /** 태양의 평균 근점각부터 시각각까지 계산해 사건 시각을 구한다. */
    private fun calculate(
        date: LocalDate,
        lat: Double,
        lng: Double,
        zone: ZoneId,
        sunrise: Boolean,
    ): ZonedDateTime? {
        val day = date.dayOfYear.toDouble()
        val lngHour = lng / 15.0
        val approximateTime = day + ((if (sunrise) 6.0 else 18.0) - lngHour) / 24.0
        val meanAnomaly = 0.9856 * approximateTime - 3.289
        var trueLongitude = meanAnomaly + 1.916 * sinDeg(meanAnomaly) +
            0.020 * sinDeg(2.0 * meanAnomaly) + 282.634
        trueLongitude = normalizeDegrees(trueLongitude)

        var rightAscension = Math.toDegrees(atan(0.91764 * tan(Math.toRadians(trueLongitude))))
        rightAscension = normalizeDegrees(rightAscension)
        val longitudeQuadrant = floor(trueLongitude / 90.0) * 90.0
        val ascensionQuadrant = floor(rightAscension / 90.0) * 90.0
        rightAscension = (rightAscension + longitudeQuadrant - ascensionQuadrant) / 15.0

        val sinDeclination = 0.39782 * sinDeg(trueLongitude)
        val cosDeclination = cos(Math.asin(sinDeclination))
        val cosHour = (cosDeg(ZENITH_DEG) - sinDeclination * sinDeg(lat)) /
            (cosDeclination * cosDeg(lat))
        if (cosHour !in -1.0..1.0) return null

        val hourAngleDegrees = if (sunrise) {
            360.0 - Math.toDegrees(acos(cosHour))
        } else {
            Math.toDegrees(acos(cosHour))
        }
        val hourAngle = hourAngleDegrees / 15.0
        val localMeanTime = hourAngle + rightAscension - 0.06571 * approximateTime - 6.622
        val utcHours = normalizeHours(localMeanTime - lngHour)
        var instant = date.atStartOfDay(ZoneId.of("UTC")).toInstant()
            .plusMillis((utcHours * 3_600_000.0).toLong())
        var localResult = ZonedDateTime.ofInstant(Instant.ofEpochMilli(instant.toEpochMilli()), zone)
        if (localResult.toLocalDate().isAfter(date)) instant = instant.minusSeconds(86_400)
        if (localResult.toLocalDate().isBefore(date)) instant = instant.plusSeconds(86_400)
        localResult = ZonedDateTime.ofInstant(instant, zone)
        return localResult
    }

    /** 도 단위 각도의 사인값을 구한다. */
    private fun sinDeg(value: Double) = sin(Math.toRadians(value))

    /** 도 단위 각도의 코사인값을 구한다. */
    private fun cosDeg(value: Double) = cos(Math.toRadians(value))

    /** 각도를 0 이상 360도 미만으로 맞춘다. */
    private fun normalizeDegrees(value: Double) = ((value % 360.0) + 360.0) % 360.0

    /** 시각을 0 이상 24시 미만으로 맞춘다. */
    private fun normalizeHours(value: Double) = ((value % 24.0) + 24.0) % 24.0
}
