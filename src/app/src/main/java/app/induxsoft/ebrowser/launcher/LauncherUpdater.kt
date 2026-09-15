package app.induxsoft.ebrowser.launcher

import android.content.Context
import android.util.Log
import app.induxsoft.ebrowser.Constants
import app.induxsoft.ebrowser.settings.Settings
import app.induxsoft.ebrowser.util.Files
import app.induxsoft.ebrowser.util.Hashing
import app.induxsoft.ebrowser.util.Installation
import app.induxsoft.ebrowser.util.Net
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Actualizacion automatica del lanzador.
 *
 * Es oportunista: si algo falla, se registra en el log, se limpia lo temporal y
 * se reintenta en el proximo arranque. El usuario nunca ve un error, porque no
 * hay nada que pueda hacer al respecto y la caja sigue operando con el lanzador
 * que ya tiene.
 *
 * Nunca recarga el lanzador en caliente: lo deja en pending y se aplica en el
 * arranque siguiente. Que una caja se reinicie sola a media operacion es peor
 * que quedarse un dia en la version anterior.
 */
class LauncherUpdater(
    private val ctx: Context,
    private val store: LauncherStore,
    private val settings: Settings
) {

    /** Lanza la comprobacion en segundo plano. No bloquea el arranque. */
    fun checkInBackground(force: Boolean = false, done: ((JSONObject) -> Unit)? = null) {
        Thread {
            val r = try { check(force) } catch (e: Exception) {
                Log.w(Constants.TAG, "actualizacion de lanzador fallida: ${e.message}")
                JSONObject().put("ok", false).put("error", e.message ?: "error")
            }
            done?.invoke(r)
        }.apply { isDaemon = true; name = "launcher-update" }.start()
    }

    fun check(force: Boolean): JSONObject {
        if (!settings.autoUpdateLauncher && !force)
            return result("disabled")
        if (!Net.isOnline(ctx))
            return result("offline")

        val channel = settings.channel
        val base = settings.updateBase.trimEnd('/')
        val url = "$base/$channel/latest.json"

        val conn = Net.open(url, etag = if (force) null else store.etag)
        val manifest: JSONObject
        try {
            val code = conn.responseCode
            if (code == 304) { store.lastCheck = System.currentTimeMillis(); return result("up_to_date") }
            if (code !in 200..299) return result("http_$code")
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            manifest = JSONObject(text)
            conn.getHeaderField("ETag")?.let { store.etag = it }
        } finally {
            conn.disconnect()
        }
        store.lastCheck = System.currentTimeMillis()

        val version = manifest.optInt("version", 0)
        val minBridge = manifest.optInt("min_bridge", 0)
        val rollout = manifest.optInt("rollout", 100).coerceIn(0, 100)
        val bundleUrl = manifest.optString("bundle_url").ifBlank {
            return result("bad_manifest")
        }

        // Ya lo tenemos, o es mas viejo.
        if (version <= store.currentVersion) return result("up_to_date")
        if (store.state == LauncherStore.READY && version <= store.pendingVersion)
            return result("already_pending")

        // Este APK no expone el puente que el lanzador necesita.
        if (minBridge > Constants.BRIDGE_VERSION) {
            Log.i(Constants.TAG,
                "lanzador v$version requiere puente $minBridge; este APK expone ${Constants.BRIDGE_VERSION}")
            return result("bridge_too_old")
        }

        // Rollout gradual: bucket estable por instalacion.
        val bucket = Hashing.bucket(Installation.id(ctx))
        if (bucket >= rollout) return result("not_in_rollout")

        // Descarga y extraccion. Cualquier fallo se traga en silencio (para el
        // usuario) y deja el estado exactamente como estaba.
        val tmpZip = File(store.tmpDir, "bundle.zip")
        val stage = File(store.tmpDir, "stage")
        try {
            Files.deleteTree(store.tmpDir)
            store.tmpDir.mkdirs()
            Net.download(bundleUrl, tmpZip, Constants.MAX_BUNDLE_BYTES)
            extract(tmpZip, stage)

            if (store.entryOf(stage) == null) error("bundle sin entry valido")

            Files.deleteTree(store.pendingDir)
            store.pendingDir.parentFile?.mkdirs()
            if (!stage.renameTo(store.pendingDir)) {
                stage.copyRecursively(store.pendingDir, overwrite = true)
            }
            store.markPending(version)
            Log.i(Constants.TAG, "lanzador v$version listo; se aplicara en el proximo arranque")
            return JSONObject()
                .put("ok", true).put("status", "pending").put("version", version)
        } catch (e: Exception) {
            Log.w(Constants.TAG, "bundle descartado: ${e.message}")
            return result("download_failed")
        } finally {
            Files.deleteTree(store.tmpDir)
        }
    }

    /**
     * Extraccion defensiva:
     *  - rechaza rutas fuera del destino (zip-slip)
     *  - rechaza enlaces y directorios sospechosos
     *  - corta si el descomprimido excede el limite (zip-bomb)
     */
    private fun extract(zip: File, dest: File) {
        Files.deleteTree(dest)
        dest.mkdirs()
        val destPath = dest.canonicalPath
        var total = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val name = e.name.replace('\\', '/')
                if (name.startsWith("/") || name.contains("..")) {
                    error("entrada de zip no permitida: $name")
                }
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(destPath)) {
                    error("entrada de zip fuera del destino: $name")
                }
                if (e.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        total += Net.copyLimited(zin, os, Constants.MAX_BUNDLE_BYTES - total)
                    }
                }
                zin.closeEntry()
            }
        }
        if (total <= 0L) error("bundle vacio")
    }

    /**
     * Ping de confirmacion. Best-effort: si falla, no pasa nada.
     * No lleva nada del negocio del cliente, solo versiones y un id aleatorio.
     */
    fun sendAck(appVersion: String) {
        if (!settings.sendAck) return
        Thread {
            try {
                val body = JSONObject()
                    .put("iid", Installation.id(ctx))
                    .put("launcher", store.servedVersion())
                    .put("bridge", Constants.BRIDGE_VERSION)
                    .put("app", appVersion)
                    .put("channel", settings.channel)
                    .toString()
                val c = Net.open(Constants.ACK_URL, timeoutMs = 8000)
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                c.responseCode
                c.disconnect()
            } catch (e: Exception) {
                Log.d(Constants.TAG, "ack no enviado: ${e.message}")
            }
        }.apply { isDaemon = true; name = "launcher-ack" }.start()
    }

    private fun result(status: String) =
        JSONObject().put("ok", true).put("status", status)
}
