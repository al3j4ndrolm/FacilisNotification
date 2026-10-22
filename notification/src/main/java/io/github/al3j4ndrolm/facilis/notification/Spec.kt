package io.github.al3j4ndrolm.facilis.notification

import org.json.JSONArray
import org.json.JSONObject

/**
 * A button on a notification. Its [name] is the button's label and the name its handler is
 * registered under.
 *
 * @param opensApp opens your app when pressed (handle it with `notify.onAction` in your screen).
 *   Otherwise the button runs silently in the background (handle it in `Application.onCreate`).
 * @param input shows a text box with this hint; handle the text with `notify.onReply`.
 * @param dismiss removes the notification after the button is pressed.
 */
data class Action(
    val name: String,
    val opensApp: Boolean = false,
    val input: String? = null,
    val dismiss: Boolean = true,
)

/** Everything needed to show a notification. Saved as JSON for scheduled ones. */
internal data class Spec(
    val title: String,
    val text: String? = null,
    val data: Map<String, String> = emptyMap(),
    val actions: List<Action> = emptyList(),
    val channel: String = DEFAULT_CHANNEL,
    val image: String? = null,
    val key: String? = null,
    val timeoutMs: Long? = null,
    val dismissOnTap: Boolean = true,
    val ongoing: Boolean = false,
    val progress: Float? = null,
    val alertOnce: Boolean = false,
) {
    fun validate(): Spec {
        require(title.isNotBlank()) { "A notification needs a title." }
        val size = data.entries.sumOf { it.key.length + it.value.length }
        require(size <= MAX_DATA_CHARS) {
            "Notification data is too big ($size characters, max $MAX_DATA_CHARS). Store ids, not whole objects."
        }
        require(actions.size <= 3) { "Android shows at most 3 buttons on a notification." }
        require(actions.map { it.name }.distinct().size == actions.size) { "Button names must be unique." }
        return this
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        text?.let { put("text", it) }
        put("data", JSONObject(data))
        put("actions", JSONArray().apply {
            actions.forEach { a ->
                put(JSONObject().apply {
                    put("name", a.name)
                    put("opensApp", a.opensApp)
                    a.input?.let { put("input", it) }
                    put("dismiss", a.dismiss)
                })
            }
        })
        put("channel", channel)
        image?.let { put("image", it) }
        key?.let { put("key", it) }
        timeoutMs?.let { put("timeoutMs", it) }
        put("dismissOnTap", dismissOnTap)
    }

    companion object {
        fun fromJson(json: JSONObject): Spec {
            val data = json.getJSONObject("data").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            val actions = json.getJSONArray("actions").let { array ->
                (0 until array.length()).map { i ->
                    val a = array.getJSONObject(i)
                    Action(
                        name = a.getString("name"),
                        opensApp = a.getBoolean("opensApp"),
                        input = a.optStringOrNull("input"),
                        dismiss = a.getBoolean("dismiss"),
                    )
                }
            }
            return Spec(
                title = json.getString("title"),
                text = json.optStringOrNull("text"),
                data = data,
                actions = actions,
                channel = json.getString("channel"),
                image = json.optStringOrNull("image"),
                key = json.optStringOrNull("key"),
                timeoutMs = if (json.has("timeoutMs")) json.getLong("timeoutMs") else null,
                dismissOnTap = json.getBoolean("dismissOnTap"),
            )
        }
    }
}

internal const val DEFAULT_CHANNEL = "General"
internal const val MAX_DATA_CHARS = 4000

/** Notification channel id for a channel [name]: stable, so the same name always maps to the same channel. */
internal fun channelId(name: String): String =
    "facilis_" + name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "general" }

/** Where a notification lives in the system: Android identifies notifications by tag and id. */
internal data class Target(val tag: String, val id: Int) {
    val key: String? get() = if (tag.startsWith(KEY_PREFIX)) tag.removePrefix(KEY_PREFIX) else null

    companion object {
        private const val KEY_PREFIX = "facilis:key:"
        const val UNKEYED_TAG = "facilis"
        const val KEYED_ID = 1

        fun forKey(key: String) = Target(KEY_PREFIX + key, KEYED_ID)

        fun isOurs(tag: String?) = tag != null && (tag == UNKEYED_TAG || tag.startsWith(KEY_PREFIX))
    }
}

private fun JSONObject.optStringOrNull(name: String): String? = if (has(name) && !isNull(name)) getString(name) else null
