package org.qosp.notes.tts

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import android.util.Base64

/**
 * Synthesis and the free, exact word timings. speech_marks give character offsets
 * into the exact string sent and millisecond times; the handoff verified them
 * character-for-character, so they are used as given, never re-pinned to the
 * waveform (which an edge-tts reader is forced to do).
 */
object SpeechifyTts {

    /** s,e are char offsets into the sent text; t,d are seconds. */
    data class Token(val s: Int, val e: Int, val t: Double, val d: Double)

    class Synth(val mp3: File?, val tokens: List<Token>, val error: String)

    fun synth(ring: SpeechifyKeyRing, text: String, voiceId: String, mp3: File): Synth {
        val payload = JSONObject()
            .put("input", text).put("voice_id", voiceId)
            .put("audio_format", "mp3").put("model", "simba-english")
        val r = ring.call("/v1/audio/speech", payload)
        val body = r.data ?: return Synth(null, emptyList(), r.error)
        val b64 = body.optString("audio_data", "")
        if (b64.isEmpty()) return Synth(null, emptyList(), "no audio")
        val bytes = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { return Synth(null, emptyList(), "bad audio") }
        mp3.writeBytes(bytes)
        val tokens = toTokens(text, body.opt("speech_marks"))
        return Synth(mp3, tokens, "")
    }

    /** Flatten a nested speech-mark tree defensively; clamp offsets; make times sane. */
    fun toTokens(text: String, marks: Any?): List<Token> {
        val flat = ArrayList<JSONObject>()
        fun walk(n: Any?) {
            if (n !is JSONObject) return
            val kids = n.optJSONArray("chunks") ?: n.optJSONArray("nestedChunks")
            if (kids != null && kids.length() > 0) {
                for (i in 0 until kids.length()) walk(kids.opt(i))
            } else if (n.has("start_time") || n.has("startTime")) flat.add(n)
        }
        when (marks) {
            is JSONObject -> walk(marks)
            is org.json.JSONArray -> for (i in 0 until marks.length()) walk(marks.opt(i))
        }
        val n = text.length
        val out = ArrayList<Token>()
        for (m in flat) {
            val s = maxOf(0, minOf(n, m.optInt("start", 0)))
            val e = maxOf(s, minOf(n, m.optInt("end", s)))
            if (e <= s) continue
            val t = (if (m.has("start_time")) m.optDouble("start_time") else m.optDouble("startTime")) / 1000.0
            val d = (if (m.has("end_time")) m.optDouble("end_time") else m.optDouble("endTime")) / 1000.0
            out.add(Token(s, e, t, d))
        }
        out.sortWith(compareBy({ it.t }, { it.s }))
        var prev = -1.0
        val fixed = ArrayList<Token>()
        for (w in out) { val t = if (w.t <= prev) prev + 0.01 else w.t; prev = t; fixed.add(w.copy(t = t)) }
        val res = ArrayList<Token>()
        for (i in fixed.indices) {
            val w = fixed[i]
            val nxt = if (i + 1 < fixed.size) fixed[i + 1].t else w.d
            var d = w.d
            if (d <= w.t || d > nxt) d = nxt
            if (d <= w.t) d = w.t + 0.05
            res.add(w.copy(d = d))
        }
        return res
    }
}

/**
 * Plays a synthesised clip and calls onWord(start, end) at each word's time so the
 * caller can highlight that span in the note. Times are aligned to audio start.
 */
class SpeechifyReader {

    private var mp: MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())
    private var playing = false

    val isPlaying get() = playing

    fun play(mp3: File, tokens: List<SpeechifyTts.Token>, onWord: (Int, Int) -> Unit, onDone: () -> Unit) {
        stop()
        try {
            mp = MediaPlayer().apply {
                setDataSource(mp3.path)
                setOnPreparedListener {
                    it.start()
                    playing = true
                    for (w in tokens) ui.postDelayed({ if (playing) onWord(w.s, w.e) }, (w.t * 1000).toLong())
                }
                setOnCompletionListener { stop(); onDone() }
                setOnErrorListener { _, _, _ -> stop(); onDone(); true }
                prepareAsync()
            }
        } catch (_: Exception) { stop(); onDone() }
    }

    fun stop() {
        playing = false
        ui.removeCallbacksAndMessages(null)
        try { mp?.release() } catch (_: Exception) {}
        mp = null
    }
}
