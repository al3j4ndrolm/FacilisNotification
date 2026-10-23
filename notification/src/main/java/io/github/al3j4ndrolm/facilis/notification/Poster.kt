package io.github.al3j4ndrolm.facilis.notification

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import io.github.al3j4ndrolm.facilis.permission.Permission
import io.github.al3j4ndrolm.facilis.permission.permissions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Builds and shows notifications. */
internal object Poster {

    private val nextId = AtomicInteger((System.currentTimeMillis() / 1000 % 1_000_000).toInt() + 1000)
    private val imageLoader = Executors.newSingleThreadExecutor { Thread(it, "FacilisNotification-images").apply { isDaemon = true } }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var askedForPermission = false

    fun targetFor(spec: Spec): Target = spec.key?.let { Target.forKey(it) } ?: Target(Target.UNKEYED_TAG, nextId.incrementAndGet())

    fun post(context: Context, target: Target, spec: Spec) {
        val app = context.applicationContext
        ensureChannel(app, spec.channel)
        val image = spec.image
        if (image == null) {
            show(app, target, spec, bitmap = null)
        } else {
            // Decoding a photo can take a moment; keep it off the main thread.
            imageLoader.execute {
                val bitmap = runCatching { decode(app, Uri.parse(image)) }
                    .onFailure { warn("Couldn't load the notification image $image", it) }
                    .getOrNull()
                show(app, target, spec, bitmap)
            }
        }
    }

    fun cancel(context: Context, target: Target) = NotificationManagerCompat.from(context).cancel(target.tag, target.id)

    /**
     * After a direct reply, Android expects the app to update the notification (it keeps it on
     * screen and ignores a cancel). Show the reply under the original text, quietly, without buttons.
     */
    fun showReply(context: Context, target: Target, spec: Spec, reply: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            @SuppressLint("MissingPermission") // checked just above
            manager.notify(target.tag, target.id, build(context, target, spec.copy(alertOnce = true), bitmap = null, reply = reply))
        } catch (e: SecurityException) {
            warn("Couldn't update the notification after a reply.", e)
        }
    }

    private fun show(context: Context, target: Target, spec: Spec, bitmap: Bitmap?) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            askOnceThen(context) { granted -> if (granted) show(context, target, spec, bitmap) }
            return
        }
        try {
            @SuppressLint("MissingPermission") // checked just above
            manager.notify(target.tag, target.id, build(context, target, spec, bitmap))
        } catch (e: SecurityException) {
            warn("Notifications aren't allowed for this app.", e)
        }
    }

    /**
     * A scheduled notification may show while the app is in the background, where nothing can ask
     * for the permission. So ask now, once, while a screen is up. Scheduling goes ahead either way.
     */
    fun askBeforeScheduling(context: Context) {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val ask = shouldAskWhenScheduling(
            autoRequest = FacilisNotification.autoRequestPermission,
            alreadyAsked = askedForPermission,
            sdk = Build.VERSION.SDK_INT,
            enabled = enabled,
            onScreen = Tracker.resumed != null,
        )
        if (!ask) {
            if (!enabled) warn("Notifications are turned off for this app, so scheduled ones won't show. See notify.requestPermission().")
            return
        }
        askedForPermission = true
        mainHandler.post {
            permissions.request(Permission.Notifications) { granted ->
                if (!granted) warn("Notification permission denied, so scheduled notifications won't show.")
            }
        }
    }

    /** Asks for the Android 13+ permission the first time a notification can't be shown. */
    private fun askOnceThen(context: Context, then: (Boolean) -> Unit) {
        if (!FacilisNotification.autoRequestPermission || askedForPermission || Build.VERSION.SDK_INT < 33) {
            warn("Notifications are turned off for this app, so this one wasn't shown. See notify.requestPermission().")
            return
        }
        askedForPermission = true
        mainHandler.post {
            permissions.request(Permission.Notifications) { granted ->
                if (!granted) warn("Notification permission denied, so this one wasn't shown.")
                then(granted)
            }
        }
    }

    private fun build(context: Context, target: Target, spec: Spec, bitmap: Bitmap?, reply: String? = null) =
        NotificationCompat.Builder(context, channelId(spec.channel))
            .setSmallIcon(smallIcon(context))
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .apply {
                when {
                    bitmap != null -> setLargeIcon(bitmap).setStyle(
                        NotificationCompat.BigPictureStyle().bigPicture(bitmap).bigLargeIcon(null as Bitmap?)
                    )
                    spec.text != null -> setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
                }
                spec.progress?.let { setProgress(100, (it * 100).toInt(), false) }
                spec.timeoutMs?.let { setTimeoutAfter(it) }
                if (reply == null) {
                    spec.actions.forEach { addAction(action(context, target, spec, it)) }
                } else {
                    setRemoteInputHistory(arrayOf<CharSequence>(reply)).setSilent(true)
                }
            }
            .setContentIntent(appIntent(context, target, spec, action = null))
            .setDeleteIntent(broadcast(context, target, spec, NotificationReceiver.DISMISSED, actionName = null))
            .setAutoCancel(spec.dismissOnTap && !spec.ongoing)
            .setOngoing(spec.ongoing)
            .setOnlyAlertOnce(spec.alertOnce)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    private fun action(context: Context, target: Target, spec: Spec, action: Action): NotificationCompat.Action {
        val input = action.input
        return when {
            input != null -> {
                val intent = broadcast(context, target, spec, NotificationReceiver.REPLY, action.name, mutable = true)
                NotificationCompat.Action.Builder(0, action.name, intent)
                    .addRemoteInput(RemoteInput.Builder(NotificationReceiver.KEY_REPLY).setLabel(input).build())
                    .setAllowGeneratedReplies(false)
                    .build()
            }
            action.opensApp -> NotificationCompat.Action.Builder(0, action.name, appIntent(context, target, spec, action)).build()
            else -> NotificationCompat.Action.Builder(
                0, action.name, broadcast(context, target, spec, NotificationReceiver.BUTTON, action.name),
            ).build()
        }
    }

    /** Opens the app straight away (Android 12+ forbids opening it from a receiver). */
    private fun appIntent(context: Context, target: Target, spec: Spec, action: Action?): PendingIntent {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setPackage(context.packageName)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch.putExtra(EXTRA_EVENT, if (action == null) EVENT_TAP else EVENT_ACTION)
        action?.let {
            launch.putExtra(EXTRA_ACTION, it.name)
            launch.putExtra(EXTRA_DISMISS, it.dismiss)
        }
        launch.putExtras(targetExtras(target, spec))
        return PendingIntent.getActivity(
            context,
            requestCode(target, action?.name ?: "tap"),
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun broadcast(
        context: Context,
        target: Target,
        spec: Spec,
        kind: String,
        actionName: String?,
        mutable: Boolean = false,
    ): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java)
            .setAction(kind)
            .putExtras(targetExtras(target, spec))
        actionName?.let { name ->
            intent.putExtra(EXTRA_ACTION, name)
            intent.putExtra(EXTRA_DISMISS, spec.actions.firstOrNull { it.name == name }?.dismiss ?: true)
        }
        // A reply box needs a mutable PendingIntent so Android can add the typed text.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, requestCode(target, kind + (actionName ?: "")), intent, flags)
    }

    private fun targetExtras(target: Target, spec: Spec) = Bundle().apply {
        putString(EXTRA_TAG, target.tag)
        putInt(EXTRA_ID, target.id)
        putString(EXTRA_CHANNEL, spec.channel)
        putString(EXTRA_TITLE, spec.title)
        putString(EXTRA_TEXT, spec.text)
        putBundle(EXTRA_DATA, Bundle().apply { spec.data.forEach { (k, v) -> putString(k, v) } })
    }

    private fun requestCode(target: Target, what: String) = (target.tag + "#" + target.id + "#" + what).hashCode()

    private fun ensureChannel(context: Context, name: String) {
        val manager = NotificationManagerCompat.from(context)
        val id = channelId(name)
        if (manager.getNotificationChannelCompat(id) != null) return
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(id, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(name).build()
        )
    }

    /** The app's own `ic_notification` drawable if it has one, otherwise a plain bell. */
    @SuppressLint("DiscouragedApi")
    private fun smallIcon(context: Context): Int =
        context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: R.drawable.facilis_notification_ic_bell

    private fun decode(context: Context, uri: Uri, maxSize: Int = 1024): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        return resolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    const val EXTRA_EVENT = "facilis.notification.event"
    const val EXTRA_ACTION = "facilis.notification.action"
    const val EXTRA_DISMISS = "facilis.notification.dismiss"
    const val EXTRA_TAG = "facilis.notification.tag"
    const val EXTRA_ID = "facilis.notification.id"
    const val EXTRA_DATA = "facilis.notification.data"
    const val EXTRA_CHANNEL = "facilis.notification.channel"
    const val EXTRA_TITLE = "facilis.notification.title"
    const val EXTRA_TEXT = "facilis.notification.text"
    const val EVENT_TAP = "tap"
    const val EVENT_ACTION = "action"
}

/** Whether scheduling should ask for the permission now (unit tested). */
internal fun shouldAskWhenScheduling(
    autoRequest: Boolean,
    alreadyAsked: Boolean,
    sdk: Int,
    enabled: Boolean,
    onScreen: Boolean,
): Boolean = autoRequest && !alreadyAsked && sdk >= 33 && !enabled && onScreen

internal fun warn(message: String, error: Throwable? = null) {
    try {
        android.util.Log.w("FacilisNotification", message, error)
    } catch (_: Throwable) {
        System.err.println("FacilisNotification: $message ${error ?: ""}")
    }
}
