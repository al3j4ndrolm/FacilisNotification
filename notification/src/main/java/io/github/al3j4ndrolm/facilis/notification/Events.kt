package io.github.al3j4ndrolm.facilis.notification

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.OnNewIntentProvider
import androidx.core.app.RemoteInput
import androidx.core.util.Consumer
import androidx.startup.Initializer
import java.lang.ref.WeakReference

/** Something the user did with a notification. */
internal class Event(
    val kind: Kind,
    val data: Map<String, String>,
    val action: String? = null,
    val text: String? = null,
) {
    enum class Kind { Tap, Action, Reply, Dismiss }
}

/**
 * Handlers are registered by name and live in memory; events wait here until their handler
 * exists. Taps and app-opening buttons also wait for a screen to be resumed, so handlers can
 * navigate safely. Only touched from the main thread.
 */
internal object Events {

    var onTap: ((Map<String, String>) -> Unit)? = null
    var onDismiss: ((Map<String, String>) -> Unit)? = null
    val actions = HashMap<String, (Map<String, String>) -> Unit>()
    val replies = HashMap<String, (Map<String, String>, String) -> Unit>()

    private val forScreen = ArrayDeque<Event>()
    private val forHandler = ArrayDeque<Event>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A tap or an app-opening button: the app is coming to the front. */
    fun fromScreen(event: Event) {
        forScreen.addLast(event)
        if (forScreen.size > MAX_WAITING) forScreen.removeFirst()
        mainHandler.post { deliverToScreen() }
    }

    /** A silent button, a reply or a dismissal: no screen involved. */
    fun fromBackground(event: Event) {
        if (dispatch(event)) return
        forHandler.addLast(event)
        if (forHandler.size > MAX_WAITING) forHandler.removeFirst()
        warn(
            "No handler for \"${event.action}\" yet. Register silent buttons with notify.onAction or " +
                "notify.onReply in Application.onCreate so they run even when the app is closed."
        )
    }

    fun handlersChanged() {
        val waiting = forHandler.toList()
        forHandler.clear()
        waiting.forEach { if (!dispatch(it)) forHandler.addLast(it) }
        mainHandler.post { deliverToScreen() }
    }

    fun deliverToScreen() {
        if (Tracker.resumed == null) return
        val waiting = forScreen.toList()
        forScreen.clear()
        waiting.forEach { if (!dispatch(it)) forScreen.addLast(it) }
    }

    /** Returns false if the event's handler isn't registered yet. */
    private fun dispatch(event: Event): Boolean {
        when (event.kind) {
            Event.Kind.Tap -> onTap?.invoke(event.data) ?: return false
            Event.Kind.Action -> actions[event.action]?.invoke(event.data) ?: return false
            Event.Kind.Reply -> replies[event.action]?.invoke(event.data, event.text.orEmpty()) ?: return false
            Event.Kind.Dismiss -> onDismiss?.invoke(event.data) // nobody listening is fine
        }
        return true
    }

    private const val MAX_WAITING = 20
}

/** Watches activities to catch apps opened from a notification, both cold starts and `onNewIntent`. */
internal object Tracker : Application.ActivityLifecycleCallbacks {

    private var appContextOrNull: Context? = null
    private var resumedRef: WeakReference<Activity>? = null

    val appContext: Context
        get() = appContextOrNull ?: error(
            "FacilisNotification isn't initialized. Make sure androidx.startup's InitializationProvider " +
                "isn't removed from your merged manifest."
        )

    val resumed: Activity? get() = resumedRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun install(context: Context) {
        if (appContextOrNull != null) return
        val app = context.applicationContext as Application
        appContextOrNull = app
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // A recreated screen (rotation) still carries the old intent: don't deliver it twice.
        if (savedInstanceState == null) inspect(activity.intent)
        if (activity is OnNewIntentProvider) activity.addOnNewIntentListener(Consumer { inspect(it) })
    }

    override fun onActivityResumed(activity: Activity) {
        resumedRef = WeakReference(activity)
        Events.deliverToScreen()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedRef?.get() === activity) resumedRef = null
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun inspect(intent: Intent?) {
        val kind = intent?.getStringExtra(Poster.EXTRA_EVENT) ?: return
        intent.removeExtra(Poster.EXTRA_EVENT)
        val data = intent.dataExtra()
        when (kind) {
            Poster.EVENT_TAP -> Events.fromScreen(Event(Event.Kind.Tap, data))
            Poster.EVENT_ACTION -> {
                if (intent.getBooleanExtra(Poster.EXTRA_DISMISS, true)) intent.target()?.let { Poster.cancel(appContext, it) }
                Events.fromScreen(Event(Event.Kind.Action, data, action = intent.getStringExtra(Poster.EXTRA_ACTION)))
            }
        }
    }
}

/** Silent buttons, replies, dismissals and scheduled alarms. */
internal class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Tracker.install(context)
        val data = intent.dataExtra()
        val action = intent.getStringExtra(Poster.EXTRA_ACTION)
        when (intent.action) {
            BUTTON -> {
                if (intent.getBooleanExtra(Poster.EXTRA_DISMISS, true)) intent.target()?.let { Poster.cancel(context, it) }
                Events.fromBackground(Event(Event.Kind.Action, data, action))
            }
            REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString().orEmpty()
                // Also stops the "sending" spinner Android shows after a reply.
                intent.target()?.let { target ->
                    val spec = Spec(
                        title = intent.getStringExtra(Poster.EXTRA_TITLE).orEmpty(),
                        text = intent.getStringExtra(Poster.EXTRA_TEXT),
                        data = data,
                        channel = intent.getStringExtra(Poster.EXTRA_CHANNEL) ?: DEFAULT_CHANNEL,
                        key = target.key,
                    )
                    Poster.showReply(context, target, spec, text)
                }
                Events.fromBackground(Event(Event.Kind.Reply, data, action, text))
            }
            DISMISSED -> Events.fromBackground(Event(Event.Kind.Dismiss, data))
            ALARM -> intent.getStringExtra(Scheduler.EXTRA_KEY)?.let { key ->
                Scheduler.fire(context, key, due = intent.getLongExtra(Scheduler.EXTRA_DUE, 0L).takeIf { it != 0L })
            }
        }
    }

    companion object {
        const val BUTTON = "io.github.al3j4ndrolm.facilis.notification.BUTTON"
        const val REPLY = "io.github.al3j4ndrolm.facilis.notification.REPLY"
        const val DISMISSED = "io.github.al3j4ndrolm.facilis.notification.DISMISSED"
        const val ALARM = "io.github.al3j4ndrolm.facilis.notification.ALARM"
        const val KEY_REPLY = "facilis.notification.reply"
    }
}

/** Puts scheduled notifications back after a reboot, an app update or a clock/time-zone change. */
internal class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Tracker.install(context)
        Scheduler.restoreAll(context)
    }
}

internal class FacilisNotificationInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Tracker.install(context)
        // Force-stopping an app clears its alarms; put them back whenever the app starts.
        Thread({ runCatching { Scheduler.restoreAll(context) } }, "FacilisNotification-restore").start()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

private fun Intent.dataExtra(): Map<String, String> {
    val bundle = getBundleExtra(Poster.EXTRA_DATA) ?: return emptyMap()
    return bundle.keySet().associateWith { bundle.getString(it).orEmpty() }
}

private fun Intent.target(): Target? {
    val tag = getStringExtra(Poster.EXTRA_TAG) ?: return null
    return Target(tag, getIntExtra(Poster.EXTRA_ID, Target.KEYED_ID))
}
