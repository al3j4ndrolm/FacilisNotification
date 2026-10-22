package io.github.al3j4ndrolm.facilis.notification

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class NotificationLogicTest {

    private val la = ZoneId.of("America/Los_Angeles")

    private fun at(text: String) = LocalDateTime.parse(text).atZone(la)

    // region Weekly

    @Test
    fun weeklyLaterTodayFiresToday() {
        // Monday 08:00 -> Monday 09:30
        assertEquals(at("2026-10-19T09:30"), nextWeekly(at("2026-10-19T08:00"), setOf(MONDAY), LocalTime.of(9, 30)))
    }

    @Test
    fun weeklyAlreadyPassedTodayMovesToNextDay() {
        // Monday 10:00, class days Mon/Wed -> Wednesday 09:30
        assertEquals(
            at("2026-10-21T09:30"),
            nextWeekly(at("2026-10-19T10:00"), setOf(MONDAY, WEDNESDAY), LocalTime.of(9, 30)),
        )
    }

    @Test
    fun weeklyExactlyNowMovesOn() {
        assertEquals(at("2026-10-26T09:30"), nextWeekly(at("2026-10-19T09:30"), setOf(MONDAY), LocalTime.of(9, 30)))
    }

    @Test
    fun weeklyWrapsAroundTheWeek() {
        // Friday 18:00, only Mondays -> next Monday
        assertEquals(at("2026-10-26T09:30"), nextWeekly(at("2026-10-23T18:00"), setOf(MONDAY), LocalTime.of(9, 30)))
    }

    @Test
    fun weeklyKeepsLocalTimeAcrossDaylightSaving() {
        // Clocks go back on Sunday 2026-11-01 in Los Angeles; 9:30 stays 9:30 local time.
        val next = nextWeekly(at("2026-10-31T10:00"), setOf(SUNDAY), LocalTime.of(9, 30))
        assertEquals(LocalDateTime.parse("2026-11-01T09:30"), next.toLocalDateTime())
        assertEquals(-8 * 3600, next.offset.totalSeconds)
    }

    @Test
    fun weeklyNeedsADay() {
        assertThrows(IllegalArgumentException::class.java) { nextWeekly(at("2026-10-19T08:00"), emptySet(), LocalTime.NOON) }
    }

    // endregion

    // region Once and daily

    @Test
    fun atTimeTodayOrTomorrow() {
        assertEquals(at("2026-10-19T21:00"), nextAt(at("2026-10-19T20:00"), LocalTime.of(21, 0)))
        assertEquals(at("2026-10-20T07:00"), nextAt(at("2026-10-19T20:00"), LocalTime.of(7, 0)))
    }

    @Test
    fun onceFiresOnceThenStops() {
        val time = at("2026-10-19T21:00").toInstant().toEpochMilli()
        val rule = Rule.Once(time)
        assertEquals(time, nextFiring(rule, at("2026-10-19T20:00"))!!.toInstant().toEpochMilli())
        assertNull(nextFiring(rule, at("2026-10-19T21:00"), previous = time))
    }

    // endregion

    // region Interval

    @Test
    fun intervalAddsToThePreviousFiring() {
        val hour = 3_600_000L
        val previous = at("2026-10-19T09:00").toInstant().toEpochMilli()
        assertEquals(at("2026-10-19T11:00"), nextFiring(Rule.Interval(2 * hour), at("2026-10-19T09:00:05"), previous))
    }

    @Test
    fun intervalSkipsMissedFiringsInsteadOfBursting() {
        val hour = 3_600_000L
        val previous = at("2026-10-19T09:00").toInstant().toEpochMilli()
        // Phone was off until 16:30: next is 17:00, not a burst of 11:00, 13:00, 15:00.
        assertEquals(at("2026-10-19T17:00"), nextFiring(Rule.Interval(2 * hour), at("2026-10-19T16:30"), previous))
    }

    // endregion

    // region Storage and identity

    @Test
    fun scheduleSurvivesJson() {
        val spec = Spec(
            title = "CS 149 starts in 15 minutes",
            text = "Room MQH 225",
            data = mapOf("classId" to "cs149"),
            actions = listOf(Action("Snooze"), Action("Directions", opensApp = true), Action("Reply", input = "Message")),
            channel = "Classes",
            key = "class-cs149",
            timeoutMs = 60_000,
        )
        val schedule = Schedule("class-cs149", spec, Rule.Weekly(setOf(MONDAY, WEDNESDAY, FRIDAY), LocalTime.of(9, 30)), false, 123L)
        val restored = Schedule.fromJson(JSONObject(schedule.toJson().toString()))
        assertEquals(schedule, restored)
    }

    @Test
    fun onceAndIntervalSurviveJson() {
        val spec = Spec(title = "Hi")
        listOf(Rule.Once(42L), Rule.Interval(900_000L)).forEach { rule ->
            val schedule = Schedule("k", spec, rule, exact = true, nextMillis = 7L)
            assertEquals(schedule, Schedule.fromJson(JSONObject(schedule.toJson().toString())))
        }
    }

    @Test
    fun channelIdsAreStable() {
        assertEquals("facilis_general", channelId("General"))
        assertEquals("facilis_class_reminders", channelId("Class reminders!"))
        assertEquals("facilis_general", channelId("  ***  "))
    }

    @Test
    fun keysMapToTheSameNotification() {
        assertEquals(Target.forKey("chat-42"), Target.forKey("chat-42"))
        assertEquals("chat-42", Target.forKey("chat-42").key)
        assertTrue(Target.isOurs(Target.forKey("x").tag))
        assertTrue(Target.isOurs(Target.UNKEYED_TAG))
        assertTrue(!Target.isOurs("someone-else"))
    }

    @Test
    fun validationCatchesMistakes() {
        assertThrows(IllegalArgumentException::class.java) { Spec(title = " ").validate() }
        assertThrows(IllegalArgumentException::class.java) {
            Spec(title = "x", actions = listOf(Action("a"), Action("b"), Action("c"), Action("d"))).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            Spec(title = "x", data = mapOf("big" to "x".repeat(MAX_DATA_CHARS + 1))).validate()
        }
    }

    // endregion
}
