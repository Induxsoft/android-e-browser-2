package app.induxsoft.ebrowser

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.induxsoft.ebrowser.apps.*
import app.induxsoft.ebrowser.bridge.EscPosBridge
import app.induxsoft.ebrowser.bridge.NativeBridge
import app.induxsoft.ebrowser.cache.ResourceCache
import app.induxsoft.ebrowser.launcher.LauncherStore
import app.induxsoft.ebrowser.launcher.LauncherUpdater
import app.induxsoft.ebrowser.security.LockManager
import app.induxsoft.ebrowser.settings.Settings
import app.induxsoft.ebrowser.util.Net
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity(), ConsentProvider, NativeBridge.Host {

    private lateinit var web: WebView
    private lateinit var registry: AppRegistry
    private lateinit var cache: ResourceCache
    private lateinit var installer: AppInstaller
    private lateinit var settings: Settings
    private lateinit var lock: LockManager
    private lateinit var store: LauncherStore
    private lateinit var updater: LauncherUpdater
    private lateinit var bridge: NativeBridge
    private lateinit var escpos: EscPosBridge
    private lateinit var assetLoader: WebViewAssetLoader

    /** Hosts cuyo error de certificado el usuario ya acepto en esta instalacion. */
    private val acceptedSslHosts = HashSet<String>()

    /**
     * URL de la pagina actual, cacheada.
     * WebView.getUrl() solo puede llamarse en el hilo de UI y el puente la
     * consulta desde el hilo de JavaScript, asi que no se puede leer directo.
     */
    @Volatile private var currentUrl: String = ""

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = fileCallback
        fileCallback = null
        if (cb == null) return@registerForActivityResult
        val data = result.data
        var uris: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val clip = data.clipData
            uris = when {
                clip != null -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data.data != null -> arrayOf(data.data!!)
                else -> null
            }
        }
        cb.onReceiveValue(uris)
    }

    // ------------------------------------------------------------------ ciclo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        web = findViewById(R.id.myWebView)

        settings = Settings(this)
        lock = LockManager(this)
        registry = AppRegistry(this)
        cache = ResourceCache(this, registry)
        installer = AppInstaller(this, registry, cache, this)
        store = LauncherStore(this)
        updater = LauncherUpdater(this, store, settings)

        // Decide que lanzador se sirve en esta sesion (commit en dos fases).
        store.resolveOnStartup()
        cache.reload()

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(Constants.LAUNCHER_HOST)
            .addPathHandler(Constants.LAUNCHER_PATH, LauncherPathHandler())
            .addPathHandler("/appicon/", IconPathHandler())
            .build()

        configureWebView()
        requestBluetoothPermissions()

        escpos = EscPosBridge(this, web)
        bridge = NativeBridge(this, web, registry, installer, settings, lock, store, updater, this)
        web.addJavascriptInterface(escpos, "dsEscPrn")
        web.addJavascriptInterface(bridge, "__ebNative")

        installDocumentStartScript()
        applySettings()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        // Arranque: app configurada para abrirse sola, o el lanzador.
        val auto = settings.autoOpenAppId
        val target = if (auto.isNotBlank()) registry.find(auto)?.startUrl else null
        currentUrl = target ?: Constants.LAUNCHER_URL
        applyUserAgentFor(currentUrl)
        web.loadUrl(currentUrl)

        // Actualizacion del lanzador en segundo plano. Nunca bloquea el arranque
        // y nunca recarga en caliente: se aplica en el siguiente inicio.
        updater.checkInBackground()
    }

    override fun onDestroy() {
        if (this::escpos.isInitialized) escpos.dispose()
        super.onDestroy()
    }

    // ------------------------------------------------------------- WebView

    private fun configureWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowContentAccess = true
        s.allowFileAccess = false              // ya no hace falta: el lanzador no usa file://
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.blockNetworkImage = false
        s.blockNetworkLoads = false
        s.defaultTextEncodingName = "UTF-8"
        s.loadsImagesAutomatically = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false)
        s.saveFormData = false
        s.useWideViewPort = true
        s.displayZoomControls = true
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.cacheMode = WebSettings.LOAD_DEFAULT

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)

        web.webViewClient = Client()
        web.webChromeClient = Chrome()
    }

    /**
     * Inyecta ebrowser.js antes de que corra el codigo de la pagina.
     * Si el WebView del dispositivo no soporta la API, se cae a inyectar en
     * onPageStarted, que llega un poco despues pero funciona igual.
     */
    private var startScript: String? = null

    private fun installDocumentStartScript() {
        startScript = try {
            assets.open("ebrowser.js").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(Constants.TAG, "no se pudo leer ebrowser.js", e); null
        }
        val script = startScript ?: return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
                startScript = null   // ya no hace falta el respaldo
            } catch (e: Exception) {
                Log.w(Constants.TAG, "documentStart no disponible: ${e.message}")
            }
        }
    }

    override fun applySettings() {
        web.settings.userAgentString = settings.userAgent
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private inner class Client : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView, request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url.toString()

            // 1. Lanzador e iconos, bajo el origen sintetico.
            assetLoader.shouldInterceptRequest(request.url)?.let { return it }

            // 2. Precache de aplicaciones locales, conservando el origen real.
            if (request.method.equals("GET", true)) {
                cache.respond(url)?.let { return it }
            }
            return null
        }

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            val uri = request.url
            val scheme = uri.scheme?.lowercase() ?: return false
            // http/https los maneja el propio WebView: asi se conservan los POST,
            // las redirecciones y el historial.
            if (scheme == "http" || scheme == "https") return false
            return handleExternalScheme(uri)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            currentUrl = url
            bridge.currentOrigin = Net.originOf(url)
            startScript?.let { view.evaluateJavascript(it, null) }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            currentUrl = url
            bridge.currentOrigin = Net.originOf(url)
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            if (!request.isForMainFrame) return
            val failed = request.url.toString()
            if (failed.startsWith(Constants.LAUNCHER_ORIGIN)) return
            val app = registry.findByUrl(failed)
            val q = Uri.parse(Constants.OFFLINE_URL).buildUpon()
                .appendQueryParameter("url", failed)
                .appendQueryParameter("name", app?.name ?: "")
                .appendQueryParameter("app", app?.appId ?: "")
                .build().toString()
            view.loadUrl(q)
        }

        /**
         * Certificados no validos: en LAN es normal (autofirmados). Se pregunta
         * una vez por host y se recuerda mientras dure la instalacion.
         */
        override fun onReceivedSslError(
            view: WebView, handler: SslErrorHandler, error: SslError
        ) {
            val host = Uri.parse(error.url ?: "").host ?: ""
            if (host.isNotBlank() && acceptedSslHosts.contains(host)) {
                handler.proceed(); return
            }
            val motivo = when (error.primaryError) {
                SslError.SSL_UNTRUSTED -> "El certificado no lo emitio una autoridad conocida."
                SslError.SSL_EXPIRED -> "El certificado ya vencio."
                SslError.SSL_IDMISMATCH -> "El certificado es de otro dominio."
                SslError.SSL_NOTYETVALID -> "El certificado aun no es valido."
                SslError.SSL_DATE_INVALID -> "La fecha del certificado no es valida."
                else -> "El certificado no se pudo verificar."
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Conexion no verificada")
                .setMessage("$motivo\n\nServidor: $host\n\n" +
                        "Si es un servidor de tu propia red, esto es normal. " +
                        "Si no lo reconoces, no continues.")
                .setPositiveButton("Continuar") { _, _ ->
                    if (host.isNotBlank()) acceptedSslHosts.add(host)
                    handler.proceed()
                }
                .setNegativeButton("Cancelar") { _, _ -> handler.cancel() }
                .setCancelable(false)
                .show()
        }
    }

    private inner class Chrome : WebChromeClient() {

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                val types = params.acceptTypes.filter { it.isNotBlank() }
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (types.size == 1) types[0] else "*/*"
                    if (types.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                        params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                filePicker.launch(Intent.createChooser(intent, "Selecciona un archivo"))
                true
            } catch (e: Exception) {
                fileCallback = null
                false
            }
        }

        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) {
                Log.d(Constants.TAG, "[js] ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            // Conceder solo lo que Android ya tiene autorizado. Si se concede
            // ciegamente (grant() de un recurso sin permiso de Android real),
            // getUserMedia() se queda colgado esperando en vez de rechazar de
            // inmediato, y la pagina que espera esa promesa antes de pintar
            // se queda a medias. Al negar rapido cuando falta el permiso, la
            // pagina sigue su curso normal.
            val mapped = request.resources.mapNotNull {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    else -> null
                }
            }
            val concedido = mapped.isNotEmpty() && mapped.all {
                ContextCompat.checkSelfPermission(this@MainActivity, it) ==
                    PackageManager.PERMISSION_GRANTED
            }
            runOnUiThread {
                if (concedido) request.grant(request.resources) else request.deny()
            }
        }
    }

    /** Abre esquemas que no son web con la aplicacion correspondiente del sistema. */
    private fun handleExternalScheme(uri: Uri): Boolean {
        return try {
            val intent = if (uri.scheme == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setMessage("No hay una aplicacion instalada que pueda abrir esto.")
                .setPositiveButton("Entendido", null).show()
            true
        } catch (e: Exception) {
            true
        }
    }

    // --------------------------------------------------- servidores de assets

    /** Sirve el lanzador activo: pending, current o el del APK. */
    private inner class LauncherPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val rel = if (path.isBlank() || path.endsWith("/")) path + "browser.html" else path
            val opened = store.open(rel) ?: return notFound()
            val (stream, name) = opened
            return WebResourceResponse(
                app.induxsoft.ebrowser.util.Mime.fromUrl(name), "utf-8", stream
            ).apply {
                responseHeaders = mapOf("Cache-Control" to "no-store")
            }
        }
    }

    /** Sirve los iconos de las apps instaladas: /appicon/<app_id>.png */
    private inner class IconPathHandler : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val appId = path.substringAfterLast('/').substringBeforeLast(".png")
            val f: File = registry.iconFile(appId)
            if (!f.exists()) return notFound()
            return WebResourceResponse("image/png", null, f.inputStream())
        }
    }

    private fun notFound() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", emptyMap(),
        ByteArrayInputStream(ByteArray(0))
    )

    // ------------------------------------------------------ ConsentProvider

    /**
     * Dialogo de autorizacion. Es NATIVO a proposito: si lo dibujara la propia
     * pagina, cualquier sitio podria falsificarlo.
     *
     * Bloquea el hilo llamador (que siempre es secundario) hasta que el usuario
     * decide.
     */
    override fun askInstall(
        req: InstallRequest, name: String, origin: String, existing: InstalledApp?
    ): Consent {
        val latch = CountDownLatch(1)
        val answer = AtomicReference(Consent.REJECT)

        runOnUiThread {
            val esReemplazo = existing != null && existing.origin != origin
            val titulo = if (esReemplazo) "Reemplazar aplicacion" else "Instalar aplicacion"
            val cuerpo = if (esReemplazo) {
                "Ya tienes «${existing!!.name}» instalada desde:\n${existing.origin}\n\n" +
                "Esta pagina es:\n$origin\n\ny quiere reemplazarla.\n\n" +
                "Si cambiaste de servidor, esto es normal. Si no lo reconoces, cancela."
            } else {
                "«$name» quiere instalarse como aplicacion.\n\nOrigen:\n$origin\n\n" +
                (if (req.precache.isNotEmpty())
                    "Se guardaran ${req.precache.size} archivos para que abra sin Internet."
                 else
                    "Quedara como acceso directo. Necesitara Internet para funcionar.")
            }
            AlertDialog.Builder(this)
                .setTitle(titulo)
                .setMessage(cuerpo)
                .setPositiveButton(if (esReemplazo) "Reemplazar" else "Instalar") { _, _ ->
                    answer.set(Consent.ACCEPT); latch.countDown()
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    answer.set(Consent.REJECT); latch.countDown()
                }
                .setOnCancelListener { answer.set(Consent.REJECT); latch.countDown() }
                .show()
        }
        latch.await()
        return answer.get()
    }

    // -------------------------------------------------- NativeBridge.Host

    override fun currentPageUrl(): String = currentUrl

    override fun openUrl(url: String) {
        applyUserAgentFor(url)
        currentUrl = url
        web.loadUrl(url)
    }

    /**
     * Resuelve el User-Agent ANTES de navegar. No se hace en onPageStarted
     * porque cambiar WebSettings a mitad de navegacion puede provocar una
     * recarga, y para entonces la peticion ya salio con el valor anterior.
     */
    private fun applyUserAgentFor(url: String) {
        val ua = registry.findByUrl(url)?.userAgent ?: settings.userAgent
        if (web.settings.userAgentString != ua) web.settings.userAgentString = ua
    }

    override fun goLauncher() {
        web.clearHistory()
        currentUrl = Constants.LAUNCHER_URL
        web.loadUrl(Constants.LAUNCHER_URL)
    }

    override fun reloadLauncher() { web.loadUrl(Constants.LAUNCHER_URL) }

    override fun appVersionName(): String = BuildConfig.VERSION_NAME

    override fun promptText(
        title: String, message: String, hint: String, password: Boolean
    ): String? = null   // la UI de captura vive en el lanzador (HTML)

    override fun confirm(title: String, message: String): Boolean {
        val latch = CountDownLatch(1)
        val r = AtomicReference(false)
        runOnUiThread {
            AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton("Aceptar") { _, _ -> r.set(true); latch.countDown() }
                .setNegativeButton("Cancelar") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        return r.get()
    }

    // ------------------------------------------------------------- permisos

    private fun requestBluetoothPermissions() {
        val want = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            want.add(Manifest.permission.BLUETOOTH_CONNECT)
            want.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            want.add(Manifest.permission.BLUETOOTH)
            want.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        val missing = want.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }
}
