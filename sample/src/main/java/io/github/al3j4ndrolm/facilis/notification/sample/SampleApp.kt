package io.github.al3j4ndrolm.facilis.notification.sample

import android.app.Application
import io.github.al3j4ndrolm.facilis.notification.Action
import io.github.al3j4ndrolm.facilis.notification.notify
import io.github.al3j4ndrolm.facilis.storage.storage
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

/** The event log the screen shows. Saved with FacilisStorage so background events appear too. */
val eventLog get() = storage.list<String>("events")

fun log(message: String) = eventLog.add(0, "${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}  $message")

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Silent buttons: these run in the background, even when the app is closed.
        notify.onAction("Snooze") { data ->
            log("Snooze pressed for ${data["course"]}: reminding again in 1 minute")
            notify.after(
                1.minutes,
                title = "${data["course"]} starts soon",
                text = "Snoozed reminder",
                data = data,
                actions = classActions,
                key = "snooze-${data["classId"]}",
            )
        }
        notify.onReply("Reply") { data, text -> log("Reply to ${data["course"]}: \"$text\"") }
        notify.onDismiss { data -> log("Swiped away${data["course"]?.let { " ($it)" }.orEmpty()}") }
    }
}

val classActions = listOf(
    Action("Snooze"),
    Action("Directions", opensApp = true),
    Action("Reply", input = "Message your classmates"),
)
