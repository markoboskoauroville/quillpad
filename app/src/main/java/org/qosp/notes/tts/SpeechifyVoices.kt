package org.qosp.notes.tts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Speechify voice catalogue, ported from the handoff. English only (Speechify has
 * no Croatian). Walks next_cursor (TRAP ONE: page one is 50 all-A names), picks
 * four voices per accent as two female/male pairs, dedupes repeated display names
 * (TRAP FOUR), and caches to disk so switching accent costs no network.
 */
object SpeechifyVoices {

    data class Voice(val id: String, val name: String, val sex: String, val tone: String, val accent: String)

    private const val PER_SEX = 2
    private val PREFER = mapOf(
        "uk" to mapOf("F" to listOf("Beatrice", "Imogen"), "M" to listOf("Edmund", "Hugh")),
        "us" to mapOf("F" to listOf("Geffen", "Emily"), "M" to listOf("Dominic", "Carter"))
    )

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "speechify_voices.json")

    /** Cached four voices for an accent, fetching+picking once if needed. */
    fun voicesFor(ctx: Context, ring: SpeechifyKeyRing, accent: String): List<Voice> {
        readCache(ctx, accent)?.let { if (it.isNotEmpty()) return it }
        val all = fetchAll(ring)
        if (all.isEmpty()) return emptyList()
        val uk = pick(all, "uk"); val us = pick(all, "us")
        writeCache(ctx, uk, us)
        return if (accent == "us") us else uk
    }

    private fun fetchAll(ring: SpeechifyKeyRing): List<JSONObject> {
        val out = ArrayList<JSONObject>(); var cursor: String? = null
        for (i in 0 until 12) {
            val q = "?limit=200" + (if (cursor != null) "&cursor=" + URLEncoder.encode(cursor, "UTF-8") else "")
            val r = ring.call("/v1/voices$q", null, 30000)
            val d = r.data ?: return out
            val arr = d.optJSONArray("voices") ?: JSONArray()
            for (j in 0 until arr.length()) out.add(arr.getJSONObject(j))
            cursor = d.optString("next_cursor", null)
            if (!d.optBoolean("has_more", false) || cursor.isNullOrEmpty()) break
        }
        return out
    }

    private fun locales(v: JSONObject): List<String> {
        val out = ArrayList<String>()
        v.optString("locale").takeIf { it.isNotEmpty() }?.let { out.add(it) }
        val models = v.optJSONArray("models") ?: JSONArray()
        for (i in 0 until models.length()) {
            val langs = models.optJSONObject(i)?.optJSONArray("languages") ?: continue
            for (j in 0 until langs.length()) langs.optJSONObject(j)?.optString("locale")?.let { if (it.isNotEmpty()) out.add(it) }
        }
        return out
    }

    private fun tone(v: JSONObject): String {
        val tags = v.optJSONArray("tags") ?: return ""
        for (i in 0 until tags.length()) {
            val t = tags.optString(i)
            if (t.startsWith("timbre:")) return t.substringAfter(":").replace("-", " ").replaceFirstChar { it.uppercase() }
        }
        return ""
    }

    private fun pick(voices: List<JSONObject>, accent: String): List<Voice> {
        val loc = if (accent == "uk") "en-GB" else "en-US"
        val pool = mapOf("F" to ArrayList<Voice>(), "M" to ArrayList<Voice>())
        val seen = HashSet<String>()
        for (v in voices) {
            val g = v.optString("gender").lowercase()
            val sex = when { g.startsWith("f") -> "F"; g.startsWith("m") -> "M"; else -> continue }
            if (locales(v).none { it == loc || it.startsWith(loc) }) continue
            val id = v.optString("id"); if (id.isEmpty()) continue
            val name = v.optString("display_name").ifEmpty { id }
            if (!seen.add(name.lowercase())) continue   // TRAP FOUR: dedupe repeated names
            pool[sex]!!.add(Voice(id, name, sex, tone(v), accent))
        }
        val picked = HashMap<String, List<Voice>>()
        for (sex in listOf("F", "M")) {
            val want = (PREFER[accent]?.get(sex) ?: emptyList()).map { it.lowercase() }
            val sorted = pool[sex]!!.sortedWith(compareBy(
                { if (it.name.lowercase() in want) want.indexOf(it.name.lowercase()) else 99 },
                { it.name.lowercase() }
            ))
            picked[sex] = sorted.take(PER_SEX)
        }
        val out = ArrayList<Voice>()
        for (i in 0 until PER_SEX) for (sex in listOf("F", "M")) picked[sex]?.getOrNull(i)?.let { out.add(it) }
        return out
    }

    private fun readCache(ctx: Context, accent: String): List<Voice>? {
        val f = cacheFile(ctx); if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText()).optJSONObject("byAccent")?.optJSONArray(accent) ?: return null
            (0 until o.length()).map {
                val v = o.getJSONObject(it)
                Voice(v.getString("id"), v.getString("name"), v.getString("sex"), v.optString("tone"), accent)
            }
        } catch (_: Exception) { null }
    }

    private fun writeCache(ctx: Context, uk: List<Voice>, us: List<Voice>) {
        fun arr(list: List<Voice>) = JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("sex", it.sex).put("tone", it.tone)) }
        }
        val root = JSONObject().put("byAccent", JSONObject().put("uk", arr(uk)).put("us", arr(us)))
            .put("at", System.currentTimeMillis() / 1000)
        cacheFile(ctx).writeText(root.toString())
    }
}
