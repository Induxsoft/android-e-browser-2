package app.induxsoft.ebrowser.apps

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import app.induxsoft.ebrowser.Constants
import app.induxsoft.ebrowser.cache.ResourceCache
import app.induxsoft.ebrowser.util.Files
import app.induxsoft.ebrowser.util.Mime
import app.induxsoft.ebrowser.util.Net
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Peticion de instalacion tal como la manda la pagina.
 * Solo app_id es obligatorio; todo lo demas tiene valor por defecto razonable.
 */
data class InstallRequest(
    val appId: String,
    val manifestUrl: String?,
    val name: String?,
    val version: String,
    val scope: String?,
    val startUrl: String?,
    val precache: List<String>,
    val userAgent: String?
) {
    companion object {
        fun parse(json: String, pageUrl: String): InstallRequest {
            val o = JSONObject(json)
            val appId = o.optString("app_id").trim()
            require(appId.isNotBlank()) { "app_id es obligatorio" }
            require(appId.length <= 120 && appId.matches(Regex("[A-Za-z0-9._-]+"))) {
                "app_id solo admite letras, numeros, punto, guion y guion bajo"
            }
            val pre = ArrayList<String>()
            o.optJSONArray("precache")?.let { a: JSONArray ->
                for (i in 0 until a.length()) {
                    val r = a.optString(i)
                    if (r.isNotBlank()) pre.add(Net.resolve(pageUrl, r))
                }
            }
            return InstallRequest(
                appId = appId,
                manifestUrl = o.optString("manifest").ifBlank { null }
                    ?.let { Net.resolve(pageUrl, it) },
                name = o.optString("name").ifBlank { null },
                version = o.optString("version", "0").ifBlank { "0" },
                scope = o.optString("scope").ifBlank { null }?.let { Net.resolve(pageUrl, it) },
                startUrl = o.optString("start_url").ifBlank { null }
                    ?.let { Net.resolve(pageUrl, it) },
                precache = pre.distinct(),
                userAgent = o.optString("user_agent").ifBlank { null }
            )
        }
    }
}

/** Resultado del dialogo de consentimiento. */
enum class Consent { ACCEPT, REJECT }

/** El Activity implementa esto para mostrar el dialogo nativo. */
interface ConsentProvider {
    /**
     * @param existing app ya instalada con el mismo app_id, o null
     * @return decision del usuario (bloquea el hilo llamador)
     */
    fun askInstall(req: InstallRequest, name: String, origin: String,
                   existing: InstalledApp?): Consent
}

class AppInstaller(
    private val ctx: Context,
    private val registry: AppRegistry,
    private val cache: ResourceCache,
    private val consent: ConsentProvider
) {

    /** Progreso de descarga: (hechos, total). */
    fun interface Progress { fun on(done: Int, total: Int) }

    /**
     * Instala o actualiza. Debe llamarse SIEMPRE en hilo secundario.
     *
     * Reglas de consentimiento:
     *  - app_id nuevo                        -> se pregunta
     *  - app_id existente, mismo origen      -> actualizacion silenciosa
     *  - app_id existente, origen distinto   -> se pregunta, con aviso de reemplazo
     */
    fun install(pageUrl: String, requestJson: String, progress: Progress?): JSONObject {
        val req = InstallRequest.parse(requestJson, pageUrl)
        val origin = Net.originOf(pageUrl)
        require(origin.isNotBlank()) { "No se pudo determinar el origen de la pagina" }

        // 1. Manifest (opcional). Si falla, se sigue con los datos de la peticion.
        var name = req.name ?: req.appId
        var startUrl = req.startUrl ?: pageUrl.substringBefore('#')
        var scope = req.scope ?: startUrl.substringBeforeLast('/') + "/"
        var theme = "#CC0000"
        var iconUrl: String? = null

        if (req.manifestUrl != null) {
            try {
                val m = JSONObject(Net.readText(req.manifestUrl))
                m.optString("name").ifBlank { m.optString("short_name") }
                    .takeIf { it.isNotBlank() }?.let { name = it }
                if (req.startUrl == null)
                    m.optString("start_url").takeIf { it.isNotBlank() }
                        ?.let { startUrl = Net.resolve(req.manifestUrl, it) }
                if (req.scope == null)
                    m.optString("scope").takeIf { it.isNotBlank() }
                        ?.let { scope = Net.resolve(req.manifestUrl, it) }
                m.optString("theme_color").takeIf { it.isNotBlank() }?.let { theme = it }
                iconUrl = bestIcon(m, req.manifestUrl)
            } catch (e: Exception) {
                Log.w(Constants.TAG, "manifest no disponible: ${e.message}")
            }
        }
        if (req.name != null) name = req.name
        name = name.take(60)

        // El scope no puede salirse del origen: evita que una app reclame recursos
        // de otro sitio dentro del interceptor.
        if (Net.originOf(scope) != origin) scope = "$origin/"
        if (Net.originOf(startUrl) != origin) startUrl = pageUrl.substringBefore('#')

        // 2. Consentimiento
        val existing = registry.find(req.appId)
        val needsAsk = existing == null || existing.origin != origin
        if (needsAsk) {
            if (consent.askInstall(req, name, origin, existing) != Consent.ACCEPT) {
                return JSONObject().put("ok", false).put("error", "user_rejected")
            }
        }

        // 3. Descarga en carpeta temporal; nada toca la instalacion vigente
        //    hasta que todo termine bien.
        val finalDir = registry.dirOf(req.appId)
        val tmpDir = File(registry.root, "${registry.slug(req.appId)}.tmp")
        Files.deleteTree(tmpDir)
        tmpDir.mkdirs()

        try {
            // 3a. Icono (opcional, nunca bloquea la instalacion)
            iconUrl?.let { u ->
                try {

                    val f = File(tmpDir, "icon.png")
                    Net.download(u, f, 2L * 1024 * 1024)

                    if (BitmapFactory.decodeFile(f.absolutePath) == null)
                    {
                        f.delete()
                    }else{}

                } catch (e: Exception) {
                    Log.w(Constants.TAG, "icono no descargado: ${e.message}")
                }
            }

            // 3b. Precache
            var bytes = 0L
            var count = 0
            if (req.precache.isNotEmpty()) {
                val resDir = File(tmpDir, "res")
                resDir.mkdirs()
                val index = JSONObject()
                val total = req.precache.size
                req.precache.forEachIndexed { i, url ->
                    try {
                        val fname = ResourceCache.fileNameFor(url.substringBefore('#'))
                        val dest = File(resDir, fname)
                        val ct = Net.download(url, dest, Constants.MAX_RESOURCE_BYTES)
                        bytes += dest.length()

                        if (bytes > Constants.MAX_APP_BYTES)
                            error("El precache excede el limite permitido")

                        index.put(url.substringBefore('#'), JSONObject().apply {
                            put("f", fname)
                            put("m", Mime.normalize(ct, url))
                            put("e", Mime.charsetOf(ct))
                        })
                        count++
                    } catch (e: Exception) {
                        // Un recurso caido no tumba la instalacion: se registra y
                        // esa URL simplemente ira a la red cuando se pida.
                        Log.w(Constants.TAG, "precache omitido $url: ${e.message}")
                    }
                    progress?.on(i + 1, total)
                }
                File(resDir, "index.json").writeText(index.toString())
            }

            // 4. Conmutacion
            val now = System.currentTimeMillis()
            val app = InstalledApp(
                appId = req.appId,
                name = name,
                origin = origin,
                startUrl = startUrl,
                scope = scope,
                version = req.version,
                kind = if (count > 0) AppKind.LOCAL else AppKind.SHORTCUT,
                themeColor = theme,
                userAgent = req.userAgent ?: existing?.userAgent,
                installedAt = existing?.installedAt ?: now,
                updatedAt = now,
                resourceCount = count,
                bytes = bytes
            )
            Files.deleteTree(finalDir)
            if (!tmpDir.renameTo(finalDir)) {
                tmpDir.copyRecursively(finalDir, overwrite = true)
                Files.deleteTree(tmpDir)
            }
            registry.save(app)
            cache.reload()

            return JSONObject().put("ok", true).put("app", app.toJson())

        } catch (e: Exception) {
            Files.deleteTree(tmpDir)
            throw e
        }
    }

    /** Alta manual desde el engrane: solo acceso directo, sin consentimiento. */
    fun addShortcut(appId: String, name: String, url: String, iconUrl: String?): JSONObject {
        val origin = Net.originOf(url)
        require(origin.isNotBlank()) { "La direccion no es valida" }
        val dir = registry.dirOf(appId)
        dir.mkdirs()
        iconUrl?.let {
            try { Net.download(it, File(dir, "icon.png"), 2L * 1024 * 1024) }
            catch (e: Exception) { /* opcional */ }
        }
        val existing = registry.find(appId)
        val now = System.currentTimeMillis()
        val app = InstalledApp(
            appId = appId,
            name = name.take(60),
            origin = origin,
            startUrl = url,
            scope = "$origin/",
            version = "0",
            kind = AppKind.SHORTCUT,
            themeColor = "#CC0000",
            userAgent = existing?.userAgent,
            installedAt = existing?.installedAt ?: now,
            updatedAt = now,
            resourceCount = 0,
            bytes = 0
        )
        registry.save(app)
        cache.reload()
        return JSONObject().put("ok", true).put("app", app.toJson())
    }

    fun uninstall(appId: String): JSONObject {
        registry.remove(appId)
        cache.reload()
        return JSONObject().put("ok", true)
    }

    /** Elige el icono mas grande del manifest, con tope razonable. */
    private fun bestIcon(manifest: JSONObject, manifestUrl: String): String? {
        val arr = manifest.optJSONArray("icons") ?: return null
        var bestUrl: String? = null
        var bestSize = -1
        for (i in 0 until arr.length()) {
            val ic = arr.optJSONObject(i) ?: continue
            val src = ic.optString("src")
            if (src.isBlank()) continue
            val sizes = ic.optString("sizes", "")
            val size = sizes.split(" ")
                .mapNotNull { it.substringBefore('x').toIntOrNull() }
                .maxOrNull() ?: 0
            val score = if (size in 1..512) size else if (size > 512) 512 else 1
            if (score > bestSize) { bestSize = score; bestUrl = Net.resolve(manifestUrl, src) }
        }
        return bestUrl
    }
}
