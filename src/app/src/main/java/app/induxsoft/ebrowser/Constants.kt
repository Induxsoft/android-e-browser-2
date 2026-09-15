package app.induxsoft.ebrowser

/**
 * Constantes globales de e-browser.
 *
 * BRIDGE_VERSION se incrementa CADA VEZ que se agrega, cambia o elimina un metodo
 * expuesto al JavaScript. El lanzador descargado declara en su bundle.json el
 * campo "min_bridge"; si este APK expone menos que eso, la actualizacion se ignora.
 * Ver docs/manual-actualizacion-lanzador.md
 */
object Constants {

    /** Version del puente JavaScript expuesto por este APK. */
    const val BRIDGE_VERSION = 1

    /** Origen sintetico bajo el que se sirve el lanzador (WebViewAssetLoader). */
    const val LAUNCHER_HOST = "appassets.androidplatform.net"
    const val LAUNCHER_ORIGIN = "https://$LAUNCHER_HOST"
    const val LAUNCHER_PATH = "/launcher/"
    const val LAUNCHER_URL = "$LAUNCHER_ORIGIN$LAUNCHER_PATH" + "browser.html"
    const val OFFLINE_URL = "$LAUNCHER_ORIGIN$LAUNCHER_PATH" + "offline.html"

    /** Base del canal de actualizacion del lanzador. */
    const val UPDATE_BASE = "https://ebrowser.induxsoft.net/android/launcher"

    /** Punto final de confirmacion de arranque (telemetria, best-effort). */
    const val ACK_URL = "https://ebrowser.api.induxsoft.net/v1/android/launcher/ack"

    /** Canal por defecto. */
    const val DEFAULT_CHANNEL = "stable"

    /**
     * User-Agent por defecto. Se conserva el valor historico (Chrome de escritorio)
     * para no romper las paginas que ya dependen de el. Es configurable desde el
     * engrane y por aplicacion instalada.
     */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** Limite de descomprimido de un bundle de lanzador (proteccion zip-bomb). */
    const val MAX_BUNDLE_BYTES = 20L * 1024 * 1024

    /** Limite de un recurso individual en precache. */
    const val MAX_RESOURCE_BYTES = 25L * 1024 * 1024

    /** Limite total del precache de una aplicacion. */
    const val MAX_APP_BYTES = 200L * 1024 * 1024

    const val TAG = "e-browser"
}
