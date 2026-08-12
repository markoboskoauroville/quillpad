package org.qosp.notes.tts

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Speechify key ring, ported faithfully from the Speechify engine handoff
 * (ma-reader-thermux, verified 12.8.2026 against 21 live keys).
 *
 * Lazy and self-healing: never tests speculatively. It takes the first key not
 * known to be dead and uses it until a real request returns 401/403, then
 * condemns that key permanently (a fingerprint on disk, never the key), rolls to
 * the next and retries the SAME request so the user never sees a failure. 429
 * rests a key for a few minutes but never kills it, and a network error is never
 * the key's fault. A dead key costs exactly one wasted request in its whole life.
 */
class SpeechifyKeyRing(private val ctx: Context) {

    private val api = "https://api.sws.speechify.com"
    private val keyFile get() = File(ctx.filesDir, "speechify_keys.txt")
    private val deadFile get() = File(ctx.filesDir, "speechify_dead.json")
    private val shape = Regex("^sk_[A-Za-z0-9_\\-]{20,}$")
    private val limited = HashMap<String, Long>()
    private val limitedRestMs = 300_000L
    private var keyInHand: String? = null

    data class Entry(val key: String, val label: String)
    class Result(val data: JSONObject?, val error: String)

    // ---- key file: shape filters keys, everything else is a label above them ----
    fun addKey(key: String, label: String = "unnamed"): Boolean {
        val k = key.trim()
        if (!shape.matches(k)) return false
        if (loadKeys().any { it.key == k }) return false
        val prev = if (keyFile.exists()) keyFile.readText() else ""
        val sep = if (prev.isNotEmpty() && !prev.endsWith("\n")) "\n" else ""
        keyFile.writeText(prev + sep + label + "\n" + k + "\n")
        try { keyFile.setReadable(false, false); keyFile.setReadable(true, true) } catch (_: Exception) {}
        return true
    }

    fun loadKeys(): List<Entry> {
        if (!keyFile.exists()) return emptyList()
        val out = ArrayList<Entry>(); val seen = HashSet<String>(); var label = ""
        for (raw in keyFile.readText().split("\n")) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (shape.matches(s)) {
                if (seen.add(s)) out.add(Entry(s, if (label.isEmpty()) "unnamed" else label))
                label = ""
            } else if (s != "[DELETED]") label = s.take(40)
        }
        return out
    }

    fun hasKeys(): Boolean = loadKeys().isNotEmpty()

    /** Load keys from arbitrary pasted/file text (labels kept), append the new ones. */
    fun addFromText(text: String): Int {
        var added = 0; var label = ""
        for (raw in text.split("\n")) {
            val s = raw.trim()
            if (s.isEmpty()) continue
            if (shape.matches(s)) { if (addKey(s, if (label.isEmpty()) "unnamed" else label)) added++; label = "" }
            else if (s != "[DELETED]") label = s.take(40)
        }
        return added
    }

    // ---- dead list: fingerprints only, never the key ----
    private fun fingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    private fun mask(key: String) = key.take(6) + "\u2026" + key.takeLast(4)
    private fun deadLoad(): JSONObject =
        try { if (deadFile.exists()) JSONObject(deadFile.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
    private fun deadSave(o: JSONObject) = deadFile.writeText(o.toString())

    fun deadList(): JSONObject = deadLoad()
    fun clearFlag(fingerprint: String) { val d = deadLoad(); d.remove(fingerprint); deadSave(d) }

    /** Strike a key out of the file for good, keeping the label above it aligned with nothing. */
    fun removeKey(key: String) {
        if (!keyFile.exists()) return
        val lines = keyFile.readText().split("\n").map { if (it.trim() == key) "[DELETED]" else it }
        keyFile.writeText(lines.joinToString("\n"))
    }

    private fun condemn(e: Entry, reason: String) {
        val d = deadLoad()
        d.put(fingerprint(e.key), JSONObject()
            .put("mask", mask(e.key)).put("label", e.label)
            .put("reason", reason).put("at", System.currentTimeMillis() / 1000))
        deadSave(d)
    }

    private fun liveKeys(): List<Entry> {
        val dead = deadLoad(); val now = System.currentTimeMillis()
        val deadSet = HashSet<String>().apply { deadLoad(); val it = dead.keys(); while (it.hasNext()) add(it.next()) }
        return loadKeys().filter { fingerprint(it.key) !in deadSet && (limited[it.key] ?: 0L) <= now }
    }

    private fun current(advance: Boolean): Entry? {
        val live = liveKeys()
        if (live.isEmpty()) { keyInHand = null; return null }
        if (advance && keyInHand != null) {
            val i = live.indexOfFirst { it.key == keyInHand }
            keyInHand = if (i in 0 until live.size - 1) live[i + 1].key else live[0].key
        } else if (keyInHand == null || live.none { it.key == keyInHand }) {
            keyInHand = live[0].key
        }
        return live.first { it.key == keyInHand }
    }

    /** One request, carried by the ring. Every call (synth, catalogue) goes through here. */
    fun call(path: String, payload: JSONObject? = null, timeoutMs: Int = 60000): Result {
        val tries = maxOf(1, liveKeys().size)
        var last = "no key"
        for (attempt in 0 until tries) {
            val e = current(advance = attempt > 0) ?: return Result(null, "no working key")
            try {
                val conn = (URL(api + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = if (payload != null) "POST" else "GET"
                    setRequestProperty("Authorization", "Bearer " + e.key)
                    connectTimeout = 30000; readTimeout = timeoutMs
                    if (payload != null) { doOutput = true; setRequestProperty("Content-Type", "application/json") }
                }
                if (payload != null) conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    return Result(JSONObject(body), "")
                }
                when (code) {
                    401, 403 -> { condemn(e, "rejected (HTTP $code)"); last = "a key was rejected and marked dead"; try { conn.errorStream?.close() } catch (_: Exception) {} }
                    429 -> { limited[e.key] = System.currentTimeMillis() + limitedRestMs; last = "rate limited" }
                    else -> return Result(null, "HTTP $code")
                }
            } catch (ex: Exception) {
                return Result(null, "unreachable: " + (ex.message ?: "").take(80))
            }
        }
        return Result(null, last)
    }

    /** The one place allowed to spend a request per key. Tests each key explicitly. */
    fun testAll(): List<Triple<String, String, String>> {  // key, label, verdict
        val out = ArrayList<Triple<String, String, String>>()
        for (e in loadKeys()) {
            val verdict = try {
                val conn = (URL("$api/v1/voices?limit=1").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; setRequestProperty("Authorization", "Bearer " + e.key)
                    connectTimeout = 15000; readTimeout = 15000
                }
                when (conn.responseCode) {
                    in 200..299 -> "radi"
                    401, 403 -> { condemn(e, "rejected (HTTP ${conn.responseCode})"); "odbijen" }
                    429 -> "ograničen"
                    else -> "nejasno"
                }
            } catch (ex: Exception) { "nema veze" }
            out.add(Triple(e.key, e.label, verdict))
        }
        return out
    }
}
