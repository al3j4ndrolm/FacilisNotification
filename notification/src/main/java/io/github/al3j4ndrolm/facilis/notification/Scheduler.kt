package io.github.al3j4ndrolm.facilis.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** When a scheduled notification repeats. */
internal sealed interface Rule {
    /** Fires once at [atMillis]. */
    data class Once(val atMillis: Long) : Rule

    /** Fires at [time] (local time) on each of [days]. */
    data class Weekly(val days: Set<DayOfWeek>, val time: LocalTime) : Rule

    /** Fires every [everyMillis]. */
    data class Interval(val everyMillis: Long) : Rule
}

internal data class Schedule(
    val key: String,
    val spec: Spec,
    val rule: Rule,
    val exact: Boolean,
    val nextMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("spec", spec.toJson())
        put("exact", exact)
        put("next", nextMillis)
        put("rule", when (rule) {
            is Rule.Once -> JSONObject().put("type", "once").put("at", rule.atMillis)
            is Rule.Weekly -> JSONObject().put("type", "weekly")
                .put("days", JSONArray(rule.days.map { it.value }.sorted()))
                .put("time", rule.time.toString())
            is Rule.Interval -> JSONObject().put("type", "interval").put("every", rule.everyMillis)
        })
    }

    companion object {
        fun fromJson(json: JSONObject): Schedule {
            val r = json.getJSONObject("rule")
            val rule = when (r.getString("type")) {
                "once" -> Rule.Once(r.getLong("at"))
                "weekly" -> Rule.Weekly(
                    days = r.getJSONArray("days").let { a -> (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet() },
                    time = LocalTime.parse(r.getString("time")),
                )
                else -> Rule.Interval(r.getLong("every"))
            }
            return Schedule(
                key = json.getString("key"),
                spec = Spec.fromJson(json.getJSONObject("spec")),
                rule = rule,
                exact = json.getBoolean("exact"),
                nextMillis = json.getLong("next"),
            )
        }
    }
}

// region Time math (unit tested)

/** The next time [rule] fires strictly after [now], or null if it never fires again. */
internal fun nextFiring(rule: Rule, now: ZonedDateTime, previous: Long? = null): ZonedDateTime? = when (rule) {
    is Rule.Once -> Instant.ofEpochMilli(rule.atMillis).atZone(now.zone).takeIf { previous == null }
    is Rule.Weekly -> nextWeekly(now, rule.days, rule.time)
    is Rule.Interval -> {
        // Catch up without firing a burst for every missed interval (e.g. after the phone was off).
        var next = Instant.ofEpochMilli((previous ?: now.toInstant().toEpochMilli()) + rule.everyMillis).atZone(now.zone)
        while (!next.isAfter(now)) next = next.plusNanos(rule.everyMillis * 1_000_000)
        next
    }
}

/** The first [time] on one of [days] that is after [now], in [now]'s time zone. */
internal fun nextWeekly(now: ZonedDateTime, days: Set<DayOfWeek>, time: LocalTime): ZonedDateTime {
    require(days.isNotEmpty()) { "Pick at least one day." }
    for (offset in 0L..7L) {
        val date = now.toLocalDate().plusDays(offset)
        if (date.dayOfWeek !in days) continue
        val candidate = ZonedDateTime.of(date, time, now.zone)
        if (candidate.isAfter(now)) return candidate
    }
    error("unreachable: a week always contains each day")
}

/** Today at [time] if that's still ahead, otherwise tomorrow. */
internal fun nextAt(now: ZonedDateTime, time: LocalTime): ZonedDateTime {
    val today = ZonedDateTime.of(now.toLocalDate(), time, now.zone)
    return if (today.isAfter(now)) today else ZonedDateTime.of(now.toLocalDate().plusDays(1), time, now.zone)
}

// endregion

/** Saves schedules and arms an alarm for each one's next firing. */
internal object Scheduler {

    private const val PREFS = "facilis_notification_schedules"
    const val EXTRA_KEY = "facilis.notification.schedule"
    const val EXTRA_DUE = "facilis.notification.due"

    /** How late a default (not exact) notification may be. Android 12+ won't go below 10 minutes. */
    private const val WINDOW_MILLIS = 10 * 60 * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun now() = ZonedDateTime.now(ZoneId.systemDefault())

    fun schedule(context: Context, key: String, spec: Spec, rule: Rule, exact: Boolean) {
        if (exact) checkExactAllowed(context)
        val next = nextFiring(rule, now()) ?: return
        val schedule = Schedule(key, spec.copy(key = key), rule, exact, next.toInstant().toEpochMilli())
        save(context, schedule)
        arm(context, schedule)
    }

    fun all(context: Context): List<Schedule> = prefs(context).all.values.mapNotNull { value ->
        runCatching { Schedule.fromJson(JSONObject(value as String)) }.getOrNull()
    }.sortedBy { it.nextMillis }

    fun get(context: Context, key: String): Schedule? =
        prefs(context).getString(key, null)?.let { runCatching { Schedule.fromJson(JSONObject(it)) }.getOrNull() }

    fun cancel(context: Context, key: String) {
        disarm(context, key)
        prefs(context).edit().remove(key).apply()
    }

    fun cancelAll(context: Context) = all(context).forEach { cancel(context, it.key) }

    /**
     * Shows a due notification and arms the next one. [due] is the time the alarm was set for:
     * each firing has two alarms (see [arm]), and whichever arrives second is ignored.
     */
    fun fire(context: Context, key: String, due: Long? = null) {
        val schedule = get(context, key) ?: return
        if (due != null && due != schedule.nextMillis) return
        Poster.post(context, Target.forKey(key), schedule.spec)
        val next = nextFiring(schedule.rule, now(), previous = schedule.nextMillis)
        if (next == null) {
            cancel(context, key)
        } else {
            val updated = schedule.copy(nextMillis = next.toInstant().toEpochMilli())
            save(context, updated)
            arm(context, updated)
        }
    }

    /** Re-arms everything (alarms don't survive reboots or force-stops). Missed one-time notifications show now. */
    fun restoreAll(context: Context) {
        val now = now()
        all(context).forEach { schedule ->
            if (schedule.nextMillis > now.toInstant().toEpochMilli()) {
                arm(context, schedule)
                return@forEach
            }
            when (schedule.rule) {
                is Rule.Once -> fire(context, schedule.key)
                else -> {
                    // Missed while the phone was off: skip ahead instead of showing stale reminders.
                    val next = nextFiring(schedule.rule, now, previous = schedule.nextMillis) ?: return@forEach
                    val updated = schedule.copy(nextMillis = next.toInstant().toEpochMilli())
                    save(context, updated)
                    arm(context, updated)
                }
            }
        }
    }

    private fun save(context: Context, schedule: Schedule) {
        prefs(context).edit().putString(schedule.key, schedule.toJson().toString()).apply()
    }

    private fun arm(context: Context, schedule: Schedule) {
        val manager = alarmManager(context)
        val due = schedule.nextMillis
        if (schedule.exact && canScheduleExact(context)) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, alarmIntent(context, schedule.key, PRIMARY, due))
            manager.cancel(alarmIntent(context, schedule.key, BACKUP, due))
            return
        }
        // Without the exact-alarm permission, plain alarms can be up to an hour late when they're
        // far away. So: a 10-minute window for when the phone is awake, plus an allow-while-idle
        // backup for deep sleep. Whichever arrives first shows the notification.
        manager.setWindow(AlarmManager.RTC_WAKEUP, due, WINDOW_MILLIS, alarmIntent(context, schedule.key, PRIMARY, due))
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, alarmIntent(context, schedule.key, BACKUP, due))
    }

    private fun disarm(context: Context, key: String) {
        val manager = alarmManager(context)
        manager.cancel(alarmIntent(context, key, PRIMARY, due = 0))
        manager.cancel(alarmIntent(context, key, BACKUP, due = 0))
    }

    private fun alarmIntent(context: Context, key: String, which: String, due: Long): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java)
            .setAction(NotificationReceiver.ALARM)
            // A distinct data Uri per key and alarm keeps each one separate.
            .setData(Uri.fromParts("facilis-notification", key, which))
            .putExtra(EXTRA_KEY, key)
            .putExtra(EXTRA_DUE, due)
        return PendingIntent.getBroadcast(
            context, (key + which).hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private const val PRIMARY = "window"
    private const val BACKUP = "idle"

    private fun alarmManager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun canScheduleExact(context: Context) =
        Build.VERSION.SDK_INT < 31 || alarmManager(context).canScheduleExactAlarms()

    private fun checkExactAllowed(context: Context) {
        check(canScheduleExact(context)) {
            "exact = true needs the \"Alarms & reminders\" permission on Android 12+. Add\n" +
                "<uses-permission android:name=\"android.permission.SCHEDULE_EXACT_ALARM\" />\n" +
                "to your AndroidManifest.xml and let the user turn it on, or use the default timing " +
                "(within a few minutes), which needs no permission."
        }
    }
}
