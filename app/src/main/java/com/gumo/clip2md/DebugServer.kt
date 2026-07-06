package com.gumo.clip2md

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/**
 * Loopback-only debug/test HTTP API (127.0.0.1:8765). Never bound to a
 * network interface — reachable only via `adb forward` from the host
 * driving this device, so it carries no remote-exposure risk.
 *
 * Endpoints:
 *   GET  /health            -> "ok"
 *   GET  /state             -> {"markdown": ..., "raw": ...}
 *   POST /convert  (body=HTML) -> converted markdown (plain text)
 *   POST /paste                -> reads clipboard, converts, returns markdown
 */
class DebugServer(private val activity: MainActivity) : NanoHTTPD("127.0.0.1", 8765) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/health" ->
                    newFixedLengthResponse("ok")

                session.method == Method.GET && session.uri == "/state" -> {
                    val json = JSONObject()
                    json.put("markdown", activity.currentMarkdown())
                    json.put("raw", activity.currentRawHtml() ?: JSONObject.NULL)
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
                }

                session.method == Method.POST && session.uri == "/convert" -> {
                    val html = readBody(session)
                    val result = runConvertSync(html)
                    newFixedLengthResponse(result)
                }

                session.method == Method.POST && session.uri == "/paste" -> {
                    val result = runPasteSync()
                    newFixedLengthResponse(result)
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: ${e.message}")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val map = HashMap<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun runConvertSync(html: String): String {
        val latch = CountDownLatch(1)
        var result = ""
        activity.runOnUiThread {
            activity.convertForDebug(html) { md ->
                result = md
                latch.countDown()
            }
        }
        latch.await()
        return result
    }

    private fun runPasteSync(): String {
        val latch = CountDownLatch(1)
        var result = ""
        activity.runOnUiThread {
            activity.pasteForDebug { md ->
                result = md
                latch.countDown()
            }
        }
        latch.await()
        return result
    }
}
