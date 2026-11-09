package ai.gilbeot.app.core

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SunCalcTest {
    @Test
    fun `seoul november sunrise and sunset match kasi within three minutes`() {
        val zone = ZoneId.of("Asia/Seoul")
        val cases = listOf(
            Triple(LocalDate.of(2026, 11, 5), LocalTime.of(7, 1), LocalTime.of(17, 30)),
            Triple(LocalDate.of(2026, 11, 15), LocalTime.of(7, 11), LocalTime.of(17, 22)),
            Triple(LocalDate.of(2026, 11, 30), LocalTime.of(7, 27), LocalTime.of(17, 14)),
        )

        cases.forEach { (date, expectedRise, expectedSet) ->
            val sunrise = SunCalc.sunrise(date, 37.566666, 126.983333, zone)
            val sunset = SunCalc.sunset(date, 37.566666, 126.983333, zone)
            assertWithinThreeMinutes(sunrise, date.atTime(expectedRise).atZone(zone))
            assertWithinThreeMinutes(sunset, date.atTime(expectedSet).atZone(zone))
        }
    }

    @Test
    fun `polar day or night returns null when event does not occur`() {
        val zone = ZoneId.of("Arctic/Longyearbyen")
        assertNull(SunCalc.sunset(LocalDate.of(2026, 6, 21), 78.2232, 15.6469, zone))
        assertNull(SunCalc.sunrise(LocalDate.of(2026, 12, 21), 78.2232, 15.6469, zone))
    }

    private fun assertWithinThreeMinutes(actual: ZonedDateTime?, expected: ZonedDateTime) {
        assertNotNull(actual)
        val difference = Duration.between(expected, actual).abs()
        assertTrue("difference was ${difference.toMinutes()} minutes", difference <= Duration.ofMinutes(3))
    }
}
