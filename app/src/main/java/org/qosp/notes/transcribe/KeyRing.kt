package org.qosp.notes.transcribe

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The API key ring, ported from TTT's MaKeyRing. Several AssemblyAI keys can live
 * here; one is used until it stops working, then the next. The health rules are
 * the design:
 *
 *   401 rejected      -> the key's fault, permanently -> flagged, never re-armed by time
 *   402 cap/balance   -> the key's fault, temporarily -> flagged, re-armed after 6h or month roll
 *   429 too many      -> NOT the key's fault          -> nothing recorded, try again
 *   timeout/no signal -> NOT the key's fault          -> nothing recorded, try again
 *
 * The ring can never return nothing: if every key is flagged, order() still returns
 * them all, worst last. A stale flag must never silence the app.
 */
object KeyRing {

    enum class Health { UNKNOWN, WORKING, REJECTED, EXHAUSTED }

    data class Key(val value: String, var health: Health = Health.UNKNOWN, var at: Long = 0L)

    private const val FILE = "keyring_assemblyai.json"
    private const val EXHAUSTED_RETRY_MS = 6L * 60 * 60 * 1000  // six hours

    fun load(ctx: Context): MutableList<Key> {
        val f = File(ctx.filesDir, FILE)
        val list = mutableListOf<Key>()
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Key(
                            o.getString("value"),
                            runCatching { Health.valueOf(o.optString("health", "UNKNOWN")) }
                                .getOrDefault(Health.UNKNOWN),
                            o.optLong("at", 0L)
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        return list
    }

    fun save(ctx: Context, keys: List<Key>) {
        val arr = JSONArray()
        for (k in keys) {
            arr.put(
                JSONObject()
                    .put("value", k.value)
                    .put("health", k.health.name)
                    .put("at", k.at)
            )
        }
        File(ctx.filesDir, FILE).writeText(arr.toString())
    }

    /** Adds any AssemblyAI keys found in the text. Returns how many were new. */
    fun addFromText(ctx: Context, text: String): Int {
        val keys = load(ctx)
        var added = 0
        for (f in KeyParser.extract(text)) {
            if (f.providerId != "assemblyai") continue
            if (keys.any { it.value == f.key }) continue
            keys.add(Key(f.key))
            added++
        }
        if (added > 0) save(ctx, keys)
        return added
    }

    /** Paste a single key. Returns true if it was new. */
    fun add(ctx: Context, value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        val keys = load(ctx)
        if (keys.any { it.value == v }) return false
        keys.add(Key(v)); save(ctx, keys); return true
    }

    fun remove(ctx: Context, value: String) {
        save(ctx, load(ctx).filterNot { it.value == value })
    }

    private fun usable(k: Key, now: Long): Boolean = when (k.health) {
        Health.UNKNOWN, Health.WORKING -> true
        Health.REJECTED -> false
        Health.EXHAUSTED -> now - k.at >= EXHAUSTED_RETRY_MS
    }

    /** Usable keys first, then the flagged ones anyway so the list is never empty. */
    fun order(ctx: Context): List<Key> {
        val now = System.currentTimeMillis()
        val all = load(ctx)
        val good = all.filter { usable(it, now) }
        val bad = all.filterNot { usable(it, now) }
        return good + bad
    }

    fun onSuccess(ctx: Context, value: String) = update(ctx, value, Health.WORKING)
    fun onRejected(ctx: Context, value: String) = update(ctx, value, Health.REJECTED)
    fun onExhausted(ctx: Context, value: String) = update(ctx, value, Health.EXHAUSTED)

    /** Manual test clears the flag first, so a topped-up key is not skipped over yesterday's verdict. */
    fun forget(ctx: Context, value: String) = update(ctx, value, Health.UNKNOWN)

    private fun update(ctx: Context, value: String, health: Health) {
        val keys = load(ctx)
        val k = keys.firstOrNull { it.value == value } ?: return
        k.health = health
        k.at = System.currentTimeMillis()
        save(ctx, keys)
    }
}
