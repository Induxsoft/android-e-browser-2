package app.induxsoft.ebrowser.settings

import android.content.Context
import app.induxsoft.ebrowser.Constants
import org.json.JSONObject

/**
 * Configuracion de e-browser. La UI vive en el lanzador (HTML); aqui solo esta
 * el estado y su validacion.
 */
class Settings(ctx: Context) {

    private val p = ctx.getSharedPreferences("ebrowser_settings", Context.MODE_PRIVATE)

    /**
     * Modo cortina: oculta la barra de direccion del lanzador.
     * Es ergonomia de caja, no contencion: el engrane sigue visible y la
     * navegacion no esta restringida. Ver manual de usuario.
     */
    var hideAddressBar: Boolean
        get() = p.getBoolean("hide_address_bar", false)
        set(v) = p.edit().putBoolean("hide_address_bar", v).apply()

    /** Abrir automaticamente una app al arrancar (app_id), o vacio. */
    var autoOpenAppId: String
        get() = p.getString("auto_open", "") ?: ""
        set(v) = p.edit().putString("auto_open", v).apply()

    var channel: String
        get() = p.getString("channel", Constants.DEFAULT_CHANNEL) ?: Constants.DEFAULT_CHANNEL
        set(v) = p.edit().putString("channel", v.ifBlank { Constants.DEFAULT_CHANNEL }).apply()

    var autoUpdateLauncher: Boolean
        get() = p.getBoolean("auto_update", true)
        set(v) = p.edit().putBoolean("auto_update", v).apply()

    var sendAck: Boolean
        get() = p.getBoolean("send_ack", true)
        set(v) = p.edit().putBoolean("send_ack", v).apply()

    var keepScreenOn: Boolean
        get() = p.getBoolean("keep_screen_on", true)
        set(v) = p.edit().putBoolean("keep_screen_on", v).apply()

    var userAgent: String
        get() = p.getString("user_agent", Constants.DEFAULT_USER_AGENT)
            ?: Constants.DEFAULT_USER_AGENT
        set(v) = p.edit().putString("user_agent", v.ifBlank { Constants.DEFAULT_USER_AGENT }).apply()

    /** Base de actualizacion. Configurable para poder apuntar a un servidor de pruebas. */
    var updateBase: String
        get() = p.getString("update_base", Constants.UPDATE_BASE) ?: Constants.UPDATE_BASE
        set(v) = p.edit().putString("update_base", v.ifBlank { Constants.UPDATE_BASE }).apply()

    fun toJson(): JSONObject = JSONObject().apply {
        put("hide_address_bar", hideAddressBar)
        put("auto_open", autoOpenAppId)
        put("channel", channel)
        put("auto_update", autoUpdateLauncher)
        put("send_ack", sendAck)
        put("keep_screen_on", keepScreenOn)
        put("user_agent", userAgent)
        put("update_base", updateBase)
    }

    /** Aplica solo las claves presentes en el JSON. */
    fun applyPatch(o: JSONObject) {
        if (o.has("hide_address_bar")) hideAddressBar = o.getBoolean("hide_address_bar")
        if (o.has("auto_open")) autoOpenAppId = o.optString("auto_open", "")
        if (o.has("channel")) channel = o.optString("channel")
        if (o.has("auto_update")) autoUpdateLauncher = o.getBoolean("auto_update")
        if (o.has("send_ack")) sendAck = o.getBoolean("send_ack")
        if (o.has("keep_screen_on")) keepScreenOn = o.getBoolean("keep_screen_on")
        if (o.has("user_agent")) userAgent = o.optString("user_agent")
        if (o.has("update_base")) updateBase = o.optString("update_base")
    }
}
