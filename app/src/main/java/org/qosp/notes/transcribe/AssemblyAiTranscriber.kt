package org.qosp.notes.transcribe

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * AssemblyAI async transcription over the KeyRing. Tries keys in order; a key that
 * is rejected (401) or exhausted (402) is flagged and the next is tried. A rate
 * limit (429) or a network failure never flags a key and never burns the rest of
 * the ring; it just reports so the user can try again.
 */
object AssemblyAiTranscriber {

    private const val BASE = "https://api.assemblyai.com/v2"
    private val main = Handler(Looper.getMainLooper())

    enum class ErrorKind { CONNECTION, KEY, RATE, OTHER }

    interface Listener {
        fun onProgress(message: String)
        fun onDone(text: String)
        fun onError(kind: ErrorKind, message: String)
    }

    private class HttpError(val code: Int) : Exception("http $code")

    fun transcribe(ctx: Context, file: File, listener: Listener) {
        Thread {
            val keys = KeyRing.order(ctx)
            if (keys.isEmpty()) { post { listener.onError(ErrorKind.KEY, "no key") }; return@Thread }
            var lastKind = ErrorKind.OTHER
            for (k in keys) {
                try {
                    post { listener.onProgress("send") }
                    val uploadUrl = upload(file, k.value)
                    post { listener.onProgress("process") }
                    val id = createTranscript(uploadUrl, k.value)
                    val text = poll(id, k.value)
                    KeyRing.onSuccess(ctx, k.value)
                    post { listener.onDone(text) }
                    return@Thread
                } catch (e: HttpError) {
                    when (e.code) {
                        401, 403 -> { KeyRing.onRejected(ctx, k.value); lastKind = ErrorKind.KEY }  // next key
                        402 -> { KeyRing.onExhausted(ctx, k.value); lastKind = ErrorKind.KEY }        // next key
                        429 -> { post { listener.onError(ErrorKind.RATE, "429") }; return@Thread }    // don't burn keys
                        else -> { lastKind = ErrorKind.OTHER }
                    }
                } catch (e: java.io.IOException) {
                    post { listener.onError(ErrorKind.CONNECTION, e.message ?: "net") }
                    return@Thread
                } catch (e: Exception) {
                    lastKind = ErrorKind.OTHER
                }
            }
            post { listener.onError(lastKind, "exhausted") }
        }.start()
    }

    private fun post(block: () -> Unit) = main.post(block)

    private fun upload(file: File, key: String): String {
        val conn = open("$BASE/upload", "POST", key).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Transfer-Encoding", "chunked")
            readTimeout = 60000
        }
        conn.outputStream.use { out ->
            BufferedInputStream(FileInputStream(file)).use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.flush()
            }
        }
        val code = conn.responseCode
        val body = readBody(conn)
        if (code !in 200..299) throw HttpError(code)
        return JSONObject(body).getString("upload_url")
    }

    private fun createTranscript(audioUrl: String, key: String): String {
        val payload = JSONObject().apply {
            put("audio_url", audioUrl); put("language_code", "hr")
            put("punctuate", true); put("format_text", true)
        }.toString()
        val conn = open("$BASE/transcript", "POST", key).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = readBody(conn)
        if (code !in 200..299) throw HttpError(code)
        return JSONObject(body).getString("id")
    }

    private fun poll(id: String, key: String): String {
        var waited = 0
        while (true) {
            val conn = open("$BASE/transcript/$id", "GET", key)
            val code = conn.responseCode
            val body = readBody(conn)
            if (code !in 200..299) throw HttpError(code)
            val json = JSONObject(body)
            when (json.getString("status")) {
                "completed" -> return json.optString("text", "")
                "error" -> throw Exception(json.optString("error", "error"))
                else -> {
                    val sleep = if (waited < 120) 3000L else 6000L
                    Thread.sleep(sleep); waited += (sleep / 1000L).toInt()
                }
            }
        }
    }

    private fun open(url: String, method: String, key: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("authorization", key)
            connectTimeout = 30000
            readTimeout = 30000
        }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = try { conn.inputStream } catch (e: Exception) { conn.errorStream ?: throw e }
        return stream.bufferedReader().use { it.readText() }
    }

    // ---- single-key test for the keys screen ----
    enum class KeyStatus { OK, REJECTED, NO_QUOTA, NO_CONNECTION }

    fun testKey(key: String, cb: (KeyStatus) -> Unit) {
        Thread {
            val status = try {
                val conn = open("$BASE/transcript?limit=1", "GET", key.trim()).apply {
                    connectTimeout = 15000; readTimeout = 15000
                }
                when (conn.responseCode) {
                    in 200..299 -> KeyStatus.OK
                    401, 403 -> KeyStatus.REJECTED
                    402 -> KeyStatus.NO_QUOTA
                    429 -> KeyStatus.OK   // a rate limit says nothing about the key
                    else -> KeyStatus.OK
                }
            } catch (e: Exception) { KeyStatus.NO_CONNECTION }
            main.post { cb(status) }
        }.start()
    }
}
