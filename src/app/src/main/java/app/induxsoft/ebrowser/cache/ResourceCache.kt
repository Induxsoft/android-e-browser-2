package app.induxsoft.ebrowser.cache

import android.content.Context
import android.webkit.WebResourceResponse
import app.induxsoft.ebrowser.apps.AppRegistry
import app.induxsoft.ebrowser.util.Hashing
import app.induxsoft.ebrowser.util.Mime
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/**
 * Cache de recursos de las aplicaciones locales.
 *
 * Regla de oro: NO se cambia el origen. El recurso se sirve desde disco pero la
 * URL que ve la pagina sigue siendo la original, de modo que localStorage,
 * IndexedDB, cookies y las llamadas al backend siguen funcionando igual que
 * si viniera de la red.
 *
 * Indice por aplicacion en: filesDir/apps/<slug>/res/index.json
 *   { "https://host/app.js": { "f": "<sha256>", "m": "text/javascript", "e": "utf-8" } }
 */
class ResourceCache(private val ctx: Context, private val registry: AppRegistry) {

    data class Entry(val file: File, val mime: String, val encoding: String)

    @Volatile private var index: Map<String, Entry> = emptyMap()

    fun size(): Int = index.size

    /** Recarga el indice completo desde disco. Llamar tras instalar o desinstalar. */
    @Synchronized
    fun reload() {
        val map = HashMap<String, Entry>()
        registry.list().forEach { app ->
            val resDir = File(registry.dirOf(app.appId), "res")
            val idx = File(resDir, "index.json")
            if (!idx.exists()) return@forEach
            try {
                val o = JSONObject(idx.readText())
                val keys = o.keys()
                while (keys.hasNext()) {
                    val url = keys.next()
                    val e = o.getJSONObject(url)
                    val f = File(resDir, e.getString("f"))
                    if (f.exists()) {
                        map[normalize(url)] = Entry(
                            f,
                            e.optString("m", Mime.fromUrl(url)),
                            e.optString("e", "utf-8")
                        )
                    }
                }
            } catch (e: Exception) { /* indice corrupto: esa app queda sin cache */ }
        }
        index = map
    }

    fun lookup(url: String): Entry? = index[normalize(url)]

    /**
     * Respuesta para shouldInterceptRequest. Devuelve null si no hay nada
     * cacheado, con lo que el WebView sigue su curso normal hacia la red.
     */
    fun respond(url: String): WebResourceResponse? {
        val hit = lookup(url) ?: return null
        return try {
            WebResourceResponse(hit.mime, hit.encoding, FileInputStream(hit.file)).apply {
                responseHeaders = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "no-cache",
                    "X-EBrowser-Cache" to "hit"
                )
            }
        } catch (e: Exception) { null }
    }

    /**
     * Normalizacion conservadora: se quita el fragmento pero se conserva el
     * query string, porque muchos bundles versionan con ?v=123 y esa URL es
     * justamente la que se precacheo.
     */
    private fun normalize(url: String): String = url.substringBefore('#')

    companion object {
        /** Nombre de archivo en disco para una URL. */
        fun fileNameFor(url: String): String = Hashing.sha256(url)
    }
}
