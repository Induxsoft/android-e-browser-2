package app.induxsoft.ebrowser.bridge

import android.app.Activity
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import app.induxsoft.ebrowser.Constants
import app.induxsoft.ebrowser.apps.AppInstaller
import app.induxsoft.ebrowser.apps.AppRegistry
import app.induxsoft.ebrowser.launcher.LauncherStore
import app.induxsoft.ebrowser.launcher.LauncherUpdater
import app.induxsoft.ebrowser.security.LockManager
import app.induxsoft.ebrowser.settings.Settings
import app.induxsoft.ebrowser.util.Installation
import app.induxsoft.ebrowser.util.Net
import org.json.JSONArray
import org.json.JSONObject

/**
 * Puente nativo unico expuesto al JavaScript como `__ebNative`.
 *
 * Deliberadamente tiene una superficie minima: `info()` sincrono y `call()`
 * asincrono. Toda la API amigable (promesas, espacios de nombre) la construye
 * el shim `ebrowser.js` encima de esto. Asi se pueden agregar metodos sin
 * cambiar la forma del puente, y el codigo nativo no vuelve a tocarse.
 *
 * Las paginas normales NO deben poder cambiar la configuracion ni desbloquear
 * el candado, asi que esos metodos solo se aceptan desde el origen del
 * lanzador. Esto no es restringir la navegacion: es impedir que un sitio
 * cualquiera apague el candado del duenno.
 */
class NativeBridge(
    private val activity: Activity,
    private val webView: WebView,
    private val registry: AppRegistry,
    private val installer: AppInstaller,
    private val settings: Settings,
    private val lock: LockManager,
    private val store: LauncherStore,
    private val updater: LauncherUpdater,
    private val host: Host
) {

    /** Lo que el puente necesita del Activity. */
    interface Host {
        fun currentPageUrl(): String
        fun openUrl(url: String)
        fun goLauncher()
        fun reloadLauncher()
        fun applySettings()
        fun appVersionName(): String
        fun promptText(title: String, message: String, hint: String, password: Boolean): String?
        fun confirm(title: String, message: String): Boolean
    }

    @Volatile var currentOrigin: String = ""

    private val isLauncher: Boolean get() = currentOrigin == Constants.LAUNCHER_ORIGIN

    /** Informacion inmediata, sin promesa. El lanzador la necesita al arrancar. */
    @JavascriptInterface
    fun info(): String {
        val app = registry.findByUrl(host.currentPageUrl())
        return JSONObject().apply {
            put("bridge", Constants.BRIDGE_VERSION)
            put("app_version", host.appVersionName())
            put("platform", "android")
            put("android_sdk", android.os.Build.VERSION.SDK_INT)
            put("is_launcher", isLauncher)
            put("origin", currentOrigin)
            put("installed", app?.toJson() ?: JSONObject.NULL)
            put("launcher", store.info())
        }.toString()
    }

    /**
     * Despacho asincrono. El resultado se entrega llamando en la pagina a
     * `window.__ebResolve(callId, jsonString)`.
     */
    @JavascriptInterface
    fun call(method: String, argsJson: String, callId: String) {
        Thread {
            val res = try {
                val args = if (argsJson.isBlank()) JSONObject() else JSONObject(argsJson)
                dispatch(method, args, callId)
            } catch (e: Exception) {
                Log.w(Constants.TAG, "fallo en $method: ${e.message}")
                JSONObject().put("ok", false).put("error", e.message ?: "error")
            }
            if (res != null) resolve(callId, res)
        }.apply { isDaemon = true; name = "eb-call" }.start()
    }

    /** Devuelve null cuando la respuesta se entregara mas tarde. */
    private fun dispatch(method: String, a: JSONObject, callId: String): JSONObject? =
        when (method) {

            // ------------------------------------------------------ apps
            "apps.install" -> {
                val pageUrl = host.currentPageUrl()
                installer.install(pageUrl, a.toString()) { done, total ->
                    progress(callId, done, total)
                }
            }

            "apps.list" -> JSONObject().put("ok", true).put(
                "apps", JSONArray().also { arr -> registry.list().forEach { arr.put(it.toJson()) } }
            )

            "apps.current" -> {
                val app = registry.findByUrl(host.currentPageUrl())
                JSONObject().put("ok", true).put("app", app?.toJson() ?: JSONObject.NULL)
            }

            "apps.get" -> {
                val app = registry.find(a.getString("app_id"))
                JSONObject().put("ok", true).put("app", app?.toJson() ?: JSONObject.NULL)
            }

            "apps.icon" -> {
                val f = registry.iconFile(a.getString("app_id"))
                JSONObject().put("ok", true).put("has_icon", f.exists())
            }

            "apps.uninstall" -> {
                requireLauncherOrOwner(a.optString("app_id"))
                installer.uninstall(a.getString("app_id"))
            }

            "apps.addShortcut" -> {
                requireLauncher(method)
                installer.addShortcut(
                    appId = a.getString("app_id"),
                    name = a.getString("name"),
                    url = a.getString("url"),
                    iconUrl = a.optString("icon").ifBlank { null }
                )
            }

            "apps.reorder" -> {
                requireLauncher(method)
                val arr = a.getJSONArray("order")
                registry.reorder((0 until arr.length()).map { arr.getString(it) })
                JSONObject().put("ok", true)
            }

            "apps.open" -> {
                val app = registry.find(a.getString("app_id"))
                    ?: error("La aplicacion no esta instalada")
                activity.runOnUiThread { host.openUrl(app.startUrl) }
                JSONObject().put("ok", true)
            }

            "apps.exit" -> {
                activity.runOnUiThread { host.goLauncher() }
                JSONObject().put("ok", true)
            }

            // ------------------------------------------------ navegacion
            "nav.open" -> {
                val url = a.getString("url")
                activity.runOnUiThread { host.openUrl(url) }
                JSONObject().put("ok", true)
            }

            // --------------------------------------------- configuracion
            "config.get" -> JSONObject().put("ok", true)
                .put("config", settings.toJson())
                .put("locked", lock.isLocked)
                .put("unlocked", lock.isUnlocked)

            "config.set" -> {
                requireLauncher(method)
                if (!lock.isUnlocked) error("locked")
                settings.applyPatch(a)
                activity.runOnUiThread { host.applySettings() }
                JSONObject().put("ok", true).put("config", settings.toJson())
            }

            // -------------------------------------------------- candado
            "lock.status" -> JSONObject().put("ok", true)
                .put("locked", lock.isLocked).put("unlocked", lock.isUnlocked)

            "lock.unlock" -> {
                requireLauncher(method)
                val ok = lock.unlock(a.optString("password"))
                JSONObject().put("ok", ok).apply { if (!ok) put("error", "bad_password") }
            }

            "lock.set" -> {
                requireLauncher(method)
                val ok = lock.setPassword(
                    a.optString("password"),
                    a.optString("current").ifBlank { null }
                )
                JSONObject().put("ok", ok).apply { if (!ok) put("error", "bad_password") }
            }

            "lock.clear" -> {
                requireLauncher(method)
                val ok = lock.clearPassword(a.optString("password").ifBlank { null })
                JSONObject().put("ok", ok).apply { if (!ok) put("error", "bad_password") }
            }

            "lock.relock" -> {
                requireLauncher(method)
                lock.lockSession()
                JSONObject().put("ok", true)
            }

            // ------------------------------------------------- lanzador
            "launcher.ok" -> {
                val promoted = store.confirm()
                updater.sendAck(host.appVersionName())
                JSONObject().put("ok", true).put("promoted", promoted)
                    .put("info", store.info())
            }

            "launcher.info" -> JSONObject().put("ok", true).put("info", store.info())

            "launcher.check" -> {
                requireLauncher(method)
                updater.check(force = a.optBoolean("force", true))
            }

            "launcher.discardPending" -> {
                requireLauncher(method)
                store.discardPending()
                JSONObject().put("ok", true)
            }

            // --------------------------------------------------- sistema
            "system.info" -> JSONObject().put("ok", true).put("info", JSONObject().apply {
                put("iid", Installation.id(activity))
                put("bridge", Constants.BRIDGE_VERSION)
                put("app_version", host.appVersionName())
                put("android_sdk", android.os.Build.VERSION.SDK_INT)
                put("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                put("online", Net.isOnline(activity))
            })

            else -> error("Metodo desconocido: $method")
        }

    private fun requireLauncher(method: String) {
        if (!isLauncher) error("«$method» solo puede llamarse desde el lanzador")
    }

    /** Una app puede desinstalarse a si misma; el resto solo desde el lanzador. */
    private fun requireLauncherOrOwner(appId: String) {
        if (isLauncher) return
        val own = registry.findByUrl(host.currentPageUrl())
        if (own == null || own.appId != appId)
            error("Solo el lanzador puede desinstalar otras aplicaciones")
    }

    private fun progress(callId: String, done: Int, total: Int) {
        emit(callId, JSONObject().put("__progress", true)
            .put("done", done).put("total", total))
    }

    private fun resolve(callId: String, payload: JSONObject) = emit(callId, payload)

    private fun emit(callId: String, payload: JSONObject) {
        val json = JSONObject.quote(payload.toString())
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "try{window.__ebResolve && window.__ebResolve(${JSONObject.quote(callId)}, $json);}catch(e){}",
                null
            )
        }
    }
}
