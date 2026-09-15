package app.induxsoft.ebrowser.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

object Hashing {

    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }

    /** Bucket estable 0..99 derivado de un texto. Usado para el rollout gradual. */
    fun bucket(text: String): Int {
        val h = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        val v = ((h[0].toInt() and 0xff) shl 8) or (h[1].toInt() and 0xff)
        return v % 100
    }
}

/**
 * Identificador aleatorio y estable de esta instalacion.
 * Se genera en el primer arranque. No contiene nada del dispositivo ni del usuario.
 * Sirve para el rollout gradual y para el ping de confirmacion.
 */
object Installation {
    private const val PREFS = "ebrowser_install"
    private const val KEY = "iid"

    fun id(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var v = p.getString(KEY, null)
        if (v == null) {
            v = UUID.randomUUID().toString()
            p.edit().putString(KEY, v).apply()
        }
        return v
    }
}

object Mime {

    private val MAP = mapOf(
        "html" to "text/html", "htm" to "text/html",
        "js" to "text/javascript", "mjs" to "text/javascript",
        "css" to "text/css",
        "json" to "application/json", "webmanifest" to "application/manifest+json",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "svg" to "image/svg+xml",
        "ico" to "image/x-icon", "bmp" to "image/bmp",
        "woff" to "font/woff", "woff2" to "font/woff2",
        "ttf" to "font/ttf", "otf" to "font/otf", "eot" to "application/vnd.ms-fontobject",
        "txt" to "text/plain", "csv" to "text/csv", "xml" to "application/xml",
        "pdf" to "application/pdf",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg",
        "mp4" to "video/mp4", "webm" to "video/webm",
        "wasm" to "application/wasm"
    )

    fun fromUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val ext = clean.substringAfterLast('.', "").lowercase()
        return MAP[ext] ?: "application/octet-stream"
    }

    /** Normaliza un Content-Type de servidor a un MIME limpio (sin charset). */
    fun normalize(contentType: String?, url: String): String {
        if (contentType.isNullOrBlank()) return fromUrl(url)
        return contentType.substringBefore(';').trim().ifBlank { fromUrl(url) }
    }

    fun charsetOf(contentType: String?): String {
        val ct = contentType ?: return "utf-8"
        val i = ct.indexOf("charset=", ignoreCase = true)
        if (i < 0) return "utf-8"
        return ct.substring(i + 8).substringBefore(';').trim().trim('"').ifBlank { "utf-8" }
    }

    fun isText(mime: String): Boolean =
        mime.startsWith("text/") || mime == "application/json" ||
        mime == "application/javascript" || mime == "application/xml" ||
        mime == "application/manifest+json"
}

object Net {

    fun isOnline(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val n = cm.activeNetwork ?: return false
            val c = cm.getNetworkCapabilities(n) ?: return false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    fun open(url: String, etag: String? = null, timeoutMs: Int = 15000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("User-Agent", "e-browser-android")
        if (etag != null) c.setRequestProperty("If-None-Match", etag)
        return c
    }

    fun readText(url: String, timeoutMs: Int = 15000): String {
        val c = open(url, timeoutMs = timeoutMs)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} en $url")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Descarga a archivo. Devuelve el Content-Type del servidor.
     * Lanza excepcion si excede maxBytes.
     */
    fun download(url: String, dest: File, maxBytes: Long): String? {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode} en $url")
            dest.parentFile?.mkdirs()
            dest.outputStream().use { out -> copyLimited(c.inputStream, out, maxBytes) }
            return c.contentType
        } finally {
            c.disconnect()
        }
    }

    fun copyLimited(input: InputStream, out: OutputStream, maxBytes: Long): Long {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) error("El contenido excede el limite de $maxBytes bytes")
            out.write(buf, 0, n)
        }
        out.flush()
        return total
    }

    /** Resuelve una ruta relativa contra una base. */
    fun resolve(base: String, ref: String): String =
        try { URL(URL(base), ref).toString() } catch (e: Exception) { ref }

    fun originOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            val u = URL(url)
            val port = if (u.port == -1 || u.port == u.defaultPort) "" else ":${u.port}"
            "${u.protocol}://${u.host}$port"
        } catch (e: Exception) { "" }
    }
}

object Files {
    fun deleteTree(f: File) {
        if (!f.exists()) return
        if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }
}
