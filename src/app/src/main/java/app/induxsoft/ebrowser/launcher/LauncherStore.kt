package app.induxsoft.ebrowser.launcher

import android.content.Context
import android.util.Log
import app.induxsoft.ebrowser.Constants
import app.induxsoft.ebrowser.util.Files
import org.json.JSONObject
import java.io.File
import java.io.InputStream

/**
 * Almacen del lanzador con commit en dos fases.
 *
 * Disposicion:
 *   filesDir/launcher/current/    el que se esta sirviendo y ya se confirmo
 *   filesDir/launcher/pending/    descargado, aun sin confirmar
 *   filesDir/launcher/previous/   el anterior a current (para diagnostico)
 *
 * Maquina de estados de pending:
 *   NONE   -> no hay nada pendiente
 *   READY  -> descargado y extraido, se servira en el proximo arranque
 *   TRYING -> se esta sirviendo ahora; espera launcherOk()
 *
 * Si al arrancar el estado ya es TRYING significa que el arranque anterior
 * sirvio el pendiente y nunca confirmo: se descarta y se vuelve a current.
 * Esto es lo que impide que un browser.html con un error de JavaScript deje
 * sin lanzador a todo el parque.
 */
class LauncherStore(private val ctx: Context) {

    companion object {
        private const val PREFS = "ebrowser_launcher"
        private const val K_STATE = "pending_state"
        private const val K_PENDING_VER = "pending_version"
        private const val K_CURRENT_VER = "current_version"
        private const val K_ETAG = "manifest_etag"
        private const val K_LAST_CHECK = "last_check"

        const val NONE = "none"
        const val READY = "ready"
        const val TRYING = "trying"
    }

    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val root = File(ctx.filesDir, "launcher")

    val currentDir get() = File(root, "current")
    val pendingDir get() = File(root, "pending")
    val previousDir get() = File(root, "previous")
    val tmpDir get() = File(root, "tmp")

    /** Directorio que se sirve en esta sesion. null = usar los assets del APK. */
    private var servingDir: File? = null
    private var servingVersion: Int = 0

    var etag: String?
        get() = prefs.getString(K_ETAG, null)
        set(v) = prefs.edit().putString(K_ETAG, v).apply()

    var lastCheck: Long
        get() = prefs.getLong(K_LAST_CHECK, 0)
        set(v) = prefs.edit().putLong(K_LAST_CHECK, v).apply()

    val currentVersion: Int get() = prefs.getInt(K_CURRENT_VER, 0)
    val pendingVersion: Int get() = prefs.getInt(K_PENDING_VER, 0)
    val state: String get() = prefs.getString(K_STATE, NONE) ?: NONE

    /**
     * Resuelve que lanzador servir. Llamar UNA VEZ al arrancar, antes de cargar
     * el WebView.
     */
    fun resolveOnStartup() {
        when (state) {
            TRYING -> {
                // El arranque anterior no confirmo. Se descarta.
                Log.w(Constants.TAG, "lanzador pendiente v$pendingVersion no confirmo; se revierte")
                Files.deleteTree(pendingDir)
                prefs.edit().putString(K_STATE, NONE).putInt(K_PENDING_VER, 0).apply()
                serveCurrent()
            }
            READY -> {
                if (entryOf(pendingDir) != null) {
                    prefs.edit().putString(K_STATE, TRYING).apply()
                    servingDir = pendingDir
                    servingVersion = pendingVersion
                    Log.i(Constants.TAG, "sirviendo lanzador pendiente v$pendingVersion")
                } else {
                    Files.deleteTree(pendingDir)
                    prefs.edit().putString(K_STATE, NONE).apply()
                    serveCurrent()
                }
            }
            else -> serveCurrent()
        }
    }

    private fun serveCurrent() {
        servingDir = if (entryOf(currentDir) != null) currentDir else null
        servingVersion = if (servingDir != null) currentVersion else 0
    }

    /** Version del lanzador que se esta sirviendo. 0 = el del APK. */
    fun servedVersion(): Int = servingVersion

    fun servedSource(): String = when (servingDir) {
        null -> "asset"
        pendingDir -> "pending"
        else -> "current"
    }

    /**
     * Confirma que el lanzador arranco bien. Si estabamos probando un pendiente,
     * se promueve a current.
     */
    @Synchronized
    fun confirm(): Boolean {
        if (state != TRYING) return false
        Files.deleteTree(previousDir)
        if (currentDir.exists() && !currentDir.renameTo(previousDir)) Files.deleteTree(currentDir)
        Files.deleteTree(currentDir)
        val ok = pendingDir.renameTo(currentDir)
        if (!ok) {
            pendingDir.copyRecursively(currentDir, overwrite = true)
            Files.deleteTree(pendingDir)
        }
        prefs.edit()
            .putString(K_STATE, NONE)
            .putInt(K_CURRENT_VER, pendingVersion)
            .putInt(K_PENDING_VER, 0)
            .apply()
        servingDir = currentDir
        Log.i(Constants.TAG, "lanzador v${currentVersion} confirmado")
        return true
    }

    /** Marca un bundle ya extraido en pending/ como listo para el proximo arranque. */
    @Synchronized
    fun markPending(version: Int) {
        prefs.edit().putString(K_STATE, READY).putInt(K_PENDING_VER, version).apply()
    }

    @Synchronized
    fun discardPending() {
        Files.deleteTree(pendingDir)
        Files.deleteTree(tmpDir)
        prefs.edit().putString(K_STATE, NONE).putInt(K_PENDING_VER, 0).apply()
    }

    /**
     * Abre un archivo del lanzador que se esta sirviendo.
     * Cae a los assets del APK si no existe en el directorio activo, de modo que
     * un bundle incompleto no rompe la pantalla.
     */
    fun open(relPath: String): Pair<InputStream, String>? {
        val clean = relPath.trimStart('/')
        if (clean.contains("..")) return null
        servingDir?.let { d ->
            val f = File(d, clean)
            if (f.exists() && f.isFile && f.canonicalPath.startsWith(d.canonicalPath)) {
                return f.inputStream() to f.name
            }
        }
        return try {
            ctx.assets.open("launcher/$clean") to clean.substringAfterLast('/')
        } catch (e: Exception) { null }
    }

    /** Lee bundle.json de un directorio de lanzador. */
    fun entryOf(dir: File): String? {
        val bj = File(dir, "bundle.json")
        if (!bj.exists()) return null
        return try {
            val o = JSONObject(bj.readText())
            val entry = o.optString("entry", "browser.html")
            if (File(dir, entry).exists()) entry else null
        } catch (e: Exception) { null }
    }

    fun info(): JSONObject = JSONObject().apply {
        put("served_version", servedVersion())
        put("source", servedSource())
        put("current_version", currentVersion)
        put("pending_version", pendingVersion)
        put("state", state)
        put("last_check", lastCheck)
    }
}
