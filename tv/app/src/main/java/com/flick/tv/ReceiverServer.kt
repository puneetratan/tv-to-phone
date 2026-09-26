package com.flick.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "FlickTV"

private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com")
private val YOUTU_BE_HOSTS = setOf("youtu.be", "www.youtu.be")
private val VIDEO_ID = Regex("[A-Za-z0-9_-]+")

private val IMAGE_MIME = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "avif" to "image/avif",
)
private val UPLOAD_NAME = Regex("[0-9a-f]{32}\\.(${IMAGE_MIME.keys.joinToString("|")})")

private const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"

/** The video id of a YouTube watch, shorts or youtu.be link, else null. */
internal fun youtubeVideoId(url: String): String? {
    val uri = Uri.parse(url)
    val host = uri.host?.lowercase() ?: return null
    val path = uri.path.orEmpty()

    val id: String? = when {
        host in YOUTUBE_HOSTS -> when {
            path == "/watch" -> uri.getQueryParameter("v")
            path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
            else -> null
        }
        host in YOUTU_BE_HOSTS -> path.trimStart('/').substringBefore('/')
        else -> null
    }
    return id?.takeIf { VIDEO_ID.matches(it) }
}

/**
 * Fallback for a TV without the YouTube app. Regular youtube.com pages send
 * X-Frame-Options / frame-ancestors headers that refuse to load inside the
 * receiver's iframe, and /embed/ is the path YouTube designed to be framed.
 * Some uploaders disable embedding, so this cannot play every video, which
 * is why the YouTube app is tried first. autoplay=1 because the player
 * otherwise waits on a big play button that nothing on a TV can press.
 */
internal fun youtubeEmbedUrl(url: String): String {
    val id = youtubeVideoId(url) ?: return url
    return "https://www.youtube.com/embed/$id?autoplay=1"
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)

/**
 * The Kotlin twin of pi/server.py: same routes, same JSON, same WebSocket
 * messages, so the phone app cannot tell whether it is talking to this or to
 * a Pi. The kiosk page (receiver.html) is served from assets and connects
 * back to /ws on this same server.
 */
class ReceiverServer(private val context: Context, port: Int) : NanoWSD(port) {

    private val screens = CopyOnWriteArraySet<NanoWSD.WebSocket>()

    // Casts are transient, and a TV has little free storage, so anything left
    // over from a previous run is dropped rather than accumulated forever.
    private val uploadDir = File(context.filesDir, "uploads").apply {
        mkdirs()
        listFiles()?.forEach { it.delete() }
    }

    override fun openWebSocket(handshake: NanoHTTPD.IHTTPSession): NanoWSD.WebSocket =
        Screen(handshake)

    override fun serveHttp(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri
        return try {
            when {
                session.method == NanoHTTPD.Method.GET && uri == "/" -> asset("receiver.html")
                session.method == NanoHTTPD.Method.GET && uri == "/health" -> json(status())
                session.method == NanoHTTPD.Method.GET && uri.startsWith("/uploads/") ->
                    upload(uri.removePrefix("/uploads/"))
                session.method == NanoHTTPD.Method.POST && uri == "/cast" -> cast(session)
                session.method == NanoHTTPD.Method.POST && uri == "/cast-image" -> castImage(session)
                session.method == NanoHTTPD.Method.POST && uri == "/clear" -> {
                    broadcast(JSONObject().put("type", "clear"))
                    json(status())
                }
                else -> plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "request failed: $uri", e)
            plain(NanoHTTPD.Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    private fun cast(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val receivedAtMs = System.currentTimeMillis()
        val parsed = HashMap<String, String>()
        session.parseBody(parsed)
        val body = JSONObject(parsed["postData"] ?: "{}")

        val original = body.stringOrNull("url")
        val text = body.stringOrNull("text")
        val title = body.stringOrNull("title")
        val kind = body.optString("kind", "auto")
        // Meaningless until Stage 4 syncs the phone's and the TV's clocks.
        // Recorded on every cast anyway so there is real data to calibrate
        // against later.
        val sentAtMs: Long? = if (body.isNull("sent_at_ms")) null else body.getLong("sent_at_ms")

        val videoId = original?.let { youtubeVideoId(it) }
        if (videoId != null && openInYouTubeApp(videoId)) {
            Log.i(TAG, "handed $original to the YouTube app")
            // Blank the page so a video already playing in its iframe does
            // not keep going under the YouTube app.
            broadcast(JSONObject().put("type", "clear"))
            return json(status())
        }

        bringToFront()

        var url = original
        if (original != null) {
            val embed = youtubeEmbedUrl(original)
            if (embed != original) {
                Log.i(TAG, "rewrote $original -> $embed")
                url = embed
            }
        }

        val deltaMs = sentAtMs?.let { receivedAtMs - it }
        Log.i(TAG, "cast url=$url text=$text title=$title delta_ms=$deltaMs screens=${screens.size}")

        broadcast(
            JSONObject()
                .put("type", "cast")
                .put("url", url ?: JSONObject.NULL)
                .put("text", text ?: JSONObject.NULL)
                .put("title", title ?: JSONObject.NULL)
                .put("kind", kind)
                .put("sent_at_ms", sentAtMs ?: JSONObject.NULL)
                .put("received_at_ms", receivedAtMs),
        )
        return json(status())
    }

    private fun castImage(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val receivedAtMs = System.currentTimeMillis()
        val parsed = HashMap<String, String>()
        session.parseBody(parsed)

        val tempPath = parsed["file"]
            ?: return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "missing file field")
        // For a multipart file part NanoHTTPD maps the field name to the
        // client's original filename here. The phone derives that name's
        // extension from the real mime type, so it stands in for the
        // image/* check server.py does on Content-Type.
        val originalName = session.parameters["file"]?.firstOrNull().orEmpty()
        val ext = originalName.substringAfterLast('.', "").lowercase()
        if (ext !in IMAGE_MIME) {
            return plain(NanoHTTPD.Response.Status.BAD_REQUEST, "expected an image upload")
        }

        val name = "${UUID.randomUUID().toString().replace("-", "")}.$ext"
        File(tempPath).copyTo(File(uploadDir, name), overwrite = true)
        bringToFront()
        Log.i(TAG, "cast image=$name screens=${screens.size}")

        // Same message shape as a link. kind="image" is what tells the kiosk
        // page to show it in a fit-to-screen <img> rather than an iframe,
        // where a full-resolution photo would be drawn at full pixel size.
        broadcast(
            JSONObject()
                .put("type", "cast")
                .put("url", "/uploads/$name")
                .put("text", JSONObject.NULL)
                .put("title", JSONObject.NULL)
                .put("kind", "image")
                .put("sent_at_ms", JSONObject.NULL)
                .put("received_at_ms", receivedAtMs),
        )
        return json(status())
    }

    // A no-op when this app is already showing. When another app (the
    // YouTube app, after a video) is in front, this brings the kiosk page
    // back so the next cast is actually visible.
    private fun bringToFront() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
    }

    private fun openInYouTubeApp(videoId: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
            .setPackage(YOUTUBE_TV_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    private fun upload(name: String): NanoHTTPD.Response {
        // The strict pattern is also the path-traversal guard.
        if (!UPLOAD_NAME.matches(name)) return plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")
        val file = File(uploadDir, name)
        if (!file.isFile) return plain(NanoHTTPD.Response.Status.NOT_FOUND, "not found")

        val mime = IMAGE_MIME[file.extension.lowercase()] ?: "application/octet-stream"
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, mime, FileInputStream(file), file.length(),
        )
    }

    private fun asset(name: String): NanoHTTPD.Response {
        val bytes = context.assets.open(name).use { it.readBytes() }
        return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "text/html; charset=utf-8",
            ByteArrayInputStream(bytes), bytes.size.toLong(),
        )
    }

    private fun status(): JSONObject = JSONObject().put("ok", true).put("screens", screens.size)

    private fun json(body: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK, "application/json", body.toString(),
        )

    private fun plain(status: NanoHTTPD.Response.IStatus, message: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "text/plain", message)

    private fun broadcast(message: JSONObject) {
        val text = message.toString()
        for (screen in screens) {
            try {
                screen.send(text)
            } catch (e: IOException) {
                screens.remove(screen)
            }
        }
    }

    private inner class Screen(handshake: NanoHTTPD.IHTTPSession) : NanoWSD.WebSocket(handshake) {
        override fun onOpen() {
            screens.add(this)
            Log.i(TAG, "screen connected, screens=${screens.size}")
        }

        override fun onClose(
            code: NanoWSD.WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean,
        ) {
            screens.remove(this)
            Log.i(TAG, "screen disconnected, screens=${screens.size}")
        }

        // The kiosk page never sends anything meaningful.
        override fun onMessage(message: NanoWSD.WebSocketFrame?) {}
        override fun onPong(pong: NanoWSD.WebSocketFrame?) {}
        override fun onException(exception: IOException?) {}
    }
}
