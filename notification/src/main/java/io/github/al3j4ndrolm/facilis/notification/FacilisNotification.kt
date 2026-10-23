package io.github.al3j4ndrolm.facilis.notification

import android.app.NotificationManager
import android.app.Notification as AndroidNotification
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.github.al3j4ndrolm.facilis.permission.Permission
import io.github.al3j4ndrolm.facilis.permission.permissions
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Notifications with no setup. Available everywhere (Activity, ViewModel, background work):
 *
 * ```
 * notify("CS 149 starts in 15 minutes", text = "Room MQH 225")
 * notify.after(10.minutes, "Time to stretch")
 * ```
 */
val notify: FacilisNotification get() = FacilisNotification

object FacilisNotification {

    /**
     * Ask for the Android 13+ permission automatically, once: when a notification is scheduled
     * (if a screen is up), or the first time one can't be shown.
     */
    @JvmStatic
    var autoRequestPermission: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())

    // region Show

    /**
     * Shows a notification right away. Tapping it opens your app (see [onTap]).
     *
     * @param data small key/value pairs handed back to [onTap] and [onAction]. Store ids, not objects.
     * @param actions up to 3 buttons.
     * @param channel the category users see in Settings; created automatically from the name.
     * @param image a picture shown when the notification is expanded (e.g. a `content://` Uri).
     * @param key a name for this notification. Showing another one with the same key replaces it,
     *   and [cancel] can remove it later, even after the app restarts.
     * @param timeout removes the notification automatically after this long.
     * @param dismissOnTap removes the notification when it's tapped.
     * @param ongoing the user can't swipe it away; you must update or cancel it.
     */
    operator fun invoke(
        title: String,
        text: String? = null,
        data: Map<String, String> = emptyMap(),
        actions: List<Action> = emptyList(),
        channel: String = DEFAULT_CHANNEL,
        image: Uri? = null,
        key: String? = null,
        timeout: Duration? = null,
        dismissOnTap: Boolean = true,
        ongoing: Boolean = false,
    ): Notification {
        val spec = Spec(
            title = title,
            text = text,
            data = data,
            actions = actions,
            channel = channel,
            image = image?.toString(),
            key = key,
            timeoutMs = timeout?.inWholeMilliseconds,
            dismissOnTap = dismissOnTap,
            ongoing = ongoing,
        ).validate()
        val target = Poster.targetFor(spec)
        Poster.post(Tracker.appContext, target, spec)
        return Notification(target, spec)
    }

    /**
     * Shows an ongoing notification with a progress bar. Finish it with
     * [ProgressNotification.done] or [ProgressNotification.fail].
     *
     * ```
     * val upload = notify.progress("Uploading photo…")
     * upload.progress = 0.5f
     * upload.done("Photo uploaded ✓")
     * ```
     */
    fun progress(
        title: String,
        text: String? = null,
        data: Map<String, String> = emptyMap(),
        channel: String = DEFAULT_CHANNEL,
        key: String? = null,
    ): ProgressNotification {
        val spec = Spec(
            title = title,
            text = text,
            data = data,
            channel = channel,
            key = key,
            ongoing = true,
            progress = 0f,
            alertOnce = true,
        ).validate()
        val target = Poster.targetFor(spec)
        Poster.post(Tracker.appContext, target, spec)
        return ProgressNotification(target, spec)
    }

    // endregion

    // region Schedule

    /**
     * Shows a notification after [delay].
     *
     * Timing: by default it shows within about 10 minutes of the time (no special permission).
     * Pass `exact = true` for to-the-second timing; that needs the "Alarms & reminders" permission.
     */
    fun after(
        delay: Duration,
        title: String,
        text: String? = null,
        data: Map<String, String> = emptyMap(),
        actions: List<Action> = emptyList(),
        channel: String = DEFAULT_CHANNEL,
        key: String? = null,
        exact: Boolean = false,
    ): ScheduledNotification {
        require(!delay.isNegative()) { "delay can't be negative." }
        val at = System.currentTimeMillis() + delay.inWholeMilliseconds
        return schedule(Rule.Once(at), title, text, data, actions, channel, key, exact)
    }

    /** Shows a notification at a date and time (local time). A time in the past shows it right away. */
    fun at(
        time: LocalDateTime,
        title: String,
        text: String? = null,
        data: Map<String, String> = emptyMap(),
        actions: List<Action> = emptyList(),
        channel: String = DEFAULT_CHANNEL,
        key: String? = null,
        exact: Boolean = false,
    ): ScheduledNotification {
        val at = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return schedule(Rule.Once(at), title, text, data, actions, channel, key, exact)
    }

    /** Shows a notification at [time] today, or tomorrow if that time has already passed. */
    fun at(
        time: LocalTime,
        title: String,
        text: String? = null,
        data: Map<String, String> = emptyMap(),
        actions: List<Action> = emptyList(),
        channel: String = DEFAULT_CHANNEL,
        key: String? = null,
        exact: Boolean = false,
    ): ScheduledNotification {
        val at = nextAt(java.time.ZonedDateTime.now(ZoneId.systemDefault()), time).toInstant().toEpochMilli()
        return schedule(Rule.Once(at), title, text, data, actions, channel, key, exact)
    }

    /**
     * Shows a notification at [at] (local time) on each of [days], every week, until cancelled.
     * Defaults to every day.
     *
     * ```
     * notify.every(at = LocalTime.of(9, 30), days = setOf(MONDAY, WEDNESDAY), title = "CS 149 soon", key = "cs149")
     * ```
     */
    fun every(
        at: LocalTime,
        title: String,
        text: String? = null,
        days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
        data: Map<String, String> = emptyMap(),
        actions: List<Action> = emptyList(),
        channel: String = DEFAULT_CHANNEL,
        key: String? = null,
        exact: Boolean = false,
    ): ScheduledNotification = schedule(Rule.Weekly(days, at), title, text, data, actions, channel, key, exact)

    /** Shows a notification every [interval] (at least 15 minutes), until cancelled. */
    fun every(
        interval: Duration,
        title: String,
        text: String? = null,
        data: Map<String, String> = emptyMap(),
        actions: List<Action> = emptyList(),
        channel: String = DEFAULT_CHANNEL,
        key: String? = null,
    ): ScheduledNotification {
        require(interval >= 15.minutes) { "Repeat at most every 15 minutes, to save battery." }
        return schedule(Rule.Interval(interval.inWholeMilliseconds), title, text, data, actions, channel, key, exact = false)
    }

    private fun schedule(
        rule: Rule,
        title: String,
        text: String?,
        data: Map<String, String>,
        actions: List<Action>,
        channel: String,
        key: String?,
        exact: Boolean,
    ): ScheduledNotification {
        val scheduleKey = key ?: "auto-${UUID.randomUUID()}"
        val spec = Spec(title, text, data, actions, channel, key = scheduleKey).validate()
        Scheduler.schedule(Tracker.appContext, scheduleKey, spec, rule, exact)
        Poster.askBeforeScheduling(Tracker.appContext)
        return ScheduledNotification(scheduleKey)
    }

    /** Stops a scheduled notification from showing (it doesn't remove one already on screen). */
    fun cancelScheduled(key: String) = Scheduler.cancel(Tracker.appContext, key)

    /** Stops every scheduled notification. */
    fun cancelAllScheduled() = Scheduler.cancelAll(Tracker.appContext)

    /** Every scheduled notification, soonest first. */
    fun scheduled(): List<ScheduledInfo> = Scheduler.all(Tracker.appContext).map {
        ScheduledInfo(it.key, it.spec.title, LocalDateTime.ofInstant(Instant.ofEpochMilli(it.nextMillis), ZoneId.systemDefault()))
    }

    // endregion

    // region Remove and inspect

    /** Removes the notification shown with [key]. */
    fun cancel(key: String) = Poster.cancel(Tracker.appContext, Target.forKey(key))

    /** Removes every notification shown with `notify`. */
    fun cancelAll() {
        val manager = Tracker.appContext.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { Target.isOurs(it.tag) }.forEach { manager.cancel(it.tag, it.id) }
    }

    /** Notifications shown with `notify` that are on screen now. */
    fun active(): List<ActiveNotification> =
        Tracker.appContext.getSystemService(NotificationManager::class.java).activeNotifications
            .filter { Target.isOurs(it.tag) }
            .map { ActiveNotification(Target(it.tag, it.id).key, it.notification.extras.getString(AndroidNotification.EXTRA_TITLE).orEmpty()) }

    // endregion

    // region Handlers

    /**
     * Runs when the user taps a notification. Your app opens first; register this in your screen.
     */
    fun onTap(handler: (data: Map<String, String>) -> Unit) = onMain {
        Events.onTap = handler
        Events.handlersChanged()
    }

    /**
     * Runs when the user presses the button called [name]. Register silent buttons in
     * `Application.onCreate` (they run without opening the app); register `opensApp` buttons in
     * your screen.
     */
    fun onAction(name: String, handler: (data: Map<String, String>) -> Unit) = onMain {
        Events.actions[name] = handler
        Events.handlersChanged()
    }

    /** Runs when the user sends text from the button called [name] (an [Action] with `input`). */
    fun onReply(name: String, handler: (data: Map<String, String>, text: String) -> Unit) = onMain {
        Events.replies[name] = handler
        Events.handlersChanged()
    }

    /** Runs when the user swipes a notification away. */
    fun onDismiss(handler: (data: Map<String, String>) -> Unit) = onMain {
        Events.onDismiss = handler
    }

    // endregion

    /** Asks for the notification permission (Android 13+); calls back with whether notifications can show. */
    fun requestPermission(onResult: (granted: Boolean) -> Unit = {}) =
        permissions.request(Permission.Notifications, onResult = onResult)

    /** Whether notifications can show right now. */
    val enabled: Boolean get() = permissions.check(Permission.Notifications)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}

/** A notification on screen. Use it to change or remove that notification. */
open class Notification internal constructor(
    internal val target: Target,
    @Volatile internal var spec: Spec,
) {
    /** The key it was shown with, if any. */
    val key: String? get() = spec.key

    /** Changes the notification in place, without a new sound or popup. Leave a value out to keep it. */
    fun update(
        title: String = spec.title,
        text: String? = spec.text,
        data: Map<String, String> = spec.data,
        actions: List<Action> = spec.actions,
    ) = show(spec.copy(title = title, text = text, data = data, actions = actions, alertOnce = true))

    /** Removes the notification. */
    fun cancel() = Poster.cancel(Tracker.appContext, target)

    internal fun show(newSpec: Spec) {
        spec = newSpec.validate()
        Poster.post(Tracker.appContext, target, spec)
    }
}

/** An ongoing notification with a progress bar. */
class ProgressNotification internal constructor(target: Target, spec: Spec) : Notification(target, spec) {

    /** From 0 to 1. Updates the bar in place. */
    var progress: Float
        get() = spec.progress ?: 1f
        set(value) = show(spec.copy(progress = value.coerceIn(0f, 1f), alertOnce = true))

    /** Ends the progress: becomes a normal notification the user can tap or swipe away. */
    fun done(title: String, text: String? = null) =
        show(spec.copy(title = title, text = text, progress = null, ongoing = false, alertOnce = false))

    /** Ends the progress with an error, optionally with a [retry] button. */
    fun fail(title: String, text: String? = null, retry: Action? = null) =
        show(spec.copy(title = title, text = text, progress = null, ongoing = false, alertOnce = false, actions = listOfNotNull(retry)))
}

/** A scheduled notification. */
class ScheduledNotification internal constructor(
    /** The name it was scheduled under: pass it to `notify.cancelScheduled` later, even after a restart. */
    val key: String,
) {
    /** Stops it from showing. */
    fun cancel() = FacilisNotification.cancelScheduled(key)

    /** When it shows next, or null once it's done or cancelled. */
    val nextTime: LocalDateTime?
        get() = Scheduler.get(Tracker.appContext, key)?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it.nextMillis), ZoneId.systemDefault())
        }
}

data class ScheduledInfo(val key: String, val title: String, val nextTime: LocalDateTime)

data class ActiveNotification(val key: String?, val title: String)
