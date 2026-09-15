package app.induxsoft.ebrowser.apps

import android.content.Context
import app.induxsoft.ebrowser.util.Files
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tipo de entrada del lanzador.
 *
 * LOCAL    - tiene recursos precacheados; abre sin Internet.
 * SHORTCUT - solo un acceso directo; requiere red para funcionar.
 */
enum class AppKind { LOCAL, SHORTCUT;
    companion object {
        fun parse(s: String?) = if (s == "local") LOCAL else SHORTCUT
    }
    fun asText() = if (this == LOCAL) "local" else "shortcut"
}

data class InstalledApp(
    val appId: String,
    var name: String,
    var origin: String,
    var startUrl: String,
    var scope: String,
    var version: String,
    var kind: AppKind,
    var themeColor: String,
    var userAgent: String?,      // null = usa el global
    var installedAt: Long,
    var updatedAt: Long,
    var resourceCount: Int,
    var bytes: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("app_id", appId)
        put("name", name)
        put("origin", origin)
        put("start_url", startUrl)
        put("scope", scope)
        put("version", version)
        put("kind", kind.asText())
        put("theme_color", themeColor)
        put("user_agent", userAgent ?: JSONObject.NULL)
        put("installed_at", installedAt)
        put("updated_at", updatedAt)
        put("resource_count", resourceCount)
        put("bytes", bytes)
    }

    companion object {
        fun fromJson(o: JSONObject) = InstalledApp(
            appId = o.getString("app_id"),
            name = o.optString("name", o.getString("app_id")),
            origin = o.optString("origin", ""),
            startUrl = o.optString("start_url", ""),
            scope = o.optString("scope", ""),
            version = o.optString("version", "0"),
            kind = AppKind.parse(o.optString("kind", "shortcut")),
            themeColor = o.optString("theme_color", "#CC0000"),
            userAgent = o.optString("user_agent", "").ifBlank { null },
            installedAt = o.optLong("installed_at", 0),
            updatedAt = o.optLong("updated_at", 0),
            resourceCount = o.optInt("resource_count", 0),
            bytes = o.optLong("bytes", 0)
        )
    }
}

/**
 * Registro persistente de aplicaciones instaladas.
 *
 * Disposicion en disco:
 *   filesDir/apps/index.json          orden de presentacion en el lanzador
 *   filesDir/apps/<slug>/meta.json    metadatos de la app
 *   filesDir/apps/<slug>/icon.png     icono
 *   filesDir/apps/<slug>/res/         recursos precacheados (solo LOCAL)
 *
 * El <slug> se deriva del app_id, no lo elige la pagina, para que un app_id
 * no pueda escribir fuera de su carpeta.
 */
class AppRegistry(private val ctx: Context) {

    val root: File get() = File(ctx.filesDir, "apps")

    fun slug(appId: String): String =
        appId.lowercase().replace(Regex("[^a-z0-9._-]"), "_").take(80)

    fun dirOf(appId: String): File = File(root, slug(appId))

    fun iconFile(appId: String): File = File(dirOf(appId), "icon.png")

    @Volatile private var cached: List<InstalledApp>? = null

    /** Invalida el cache en memoria. */
    @Synchronized
    fun invalidate() { cached = null }

    @Synchronized
    fun list(): List<InstalledApp> {
        cached?.let { return it }
        val order = readOrder()
        val found = LinkedHashMap<String, InstalledApp>()
        root.listFiles()?.forEach { d ->
            val meta = File(d, "meta.json")
            if (d.isDirectory && meta.exists()) {
                try {
                    val a = InstalledApp.fromJson(JSONObject(meta.readText()))
                    found[a.appId] = a
                } catch (e: Exception) { /* carpeta corrupta: se ignora */ }
            }
        }
        val out = ArrayList<InstalledApp>()
        order.forEach { id -> found.remove(id)?.let { out.add(it) } }
        out.addAll(found.values.sortedBy { it.installedAt })
        cached = out
        return out
    }

    fun find(appId: String): InstalledApp? {
        val f = File(dirOf(appId), "meta.json")
        if (!f.exists()) return null
        return try { InstalledApp.fromJson(JSONObject(f.readText())) } catch (e: Exception) { null }
    }

    /** Busca la app cuyo scope contiene la URL dada. */
    fun findByUrl(url: String?): InstalledApp? {
        if (url.isNullOrBlank()) return null
        return list().firstOrNull { url.startsWith(it.scope) }
    }

    @Synchronized
    fun save(app: InstalledApp) {
        val d = dirOf(app.appId)
        d.mkdirs()
        File(d, "meta.json").writeText(app.toJson().toString())
        val order = readOrder().toMutableList()
        if (!order.contains(app.appId)) { order.add(app.appId); writeOrder(order) }
        cached = null
    }

    @Synchronized
    fun remove(appId: String) {
        Files.deleteTree(dirOf(appId))
        writeOrder(readOrder().filter { it != appId })
        cached = null
    }

    @Synchronized
    fun reorder(ids: List<String>) { writeOrder(ids); cached = null }

    private fun indexFile() = File(root, "index.json")

    private fun readOrder(): List<String> {
        val f = indexFile()
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONObject(f.readText()).optJSONArray("order") ?: JSONArray()
            (0 until a.length()).map { a.getString(it) }
        } catch (e: Exception) { emptyList() }
    }

    private fun writeOrder(ids: List<String>) {
        root.mkdirs()
        indexFile().writeText(
            JSONObject().put("order", JSONArray(ids)).toString()
        )
    }
}
