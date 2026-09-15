package app.induxsoft.ebrowser.bridge

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import app.induxsoft.ebrowser.Constants
import com.dantsu.escposprinter.EscPosCharsetEncoding
import com.dantsu.escposprinter.EscPosPrinter
import com.dantsu.escposprinter.connection.bluetooth.BluetoothPrintersConnections
import com.dantsu.escposprinter.connection.tcp.TcpConnection
import com.dantsu.escposprinter.connection.usb.UsbConnection
import com.dantsu.escposprinter.connection.usb.UsbPrintersConnections
import com.dantsu.escposprinter.textparser.PrinterTextParserImg
import org.json.JSONObject

/**
 * Puente de impresion ESC/POS.
 *
 * Se conserva el nombre historico "dsEscPrn" y la firma de todos sus metodos
 * para no romper las paginas que ya lo usan (epos_prn_dantsu.js). Lo que cambia
 * es la implementacion:
 *
 *  - USB: el flujo continua DENTRO del receptor de permiso, que es asincrono.
 *    Antes se leia usbReady inmediatamente despues de requestPermission() y el
 *    dispositivo siempre era null, asi que la conexion USB nunca funciono.
 *  - Todos los Toast se emiten en el hilo de UI. Antes habia uno fuera de el
 *    que tumbaba la aplicacion cuando la impresora no estaba conectada.
 *  - El charset se aplica a todas las conexiones, no solo a TCP.
 *  - Se agregan callbacks opcionales para que la pagina sepa el resultado.
 */
class EscPosBridge(
    private val ctx: Context,
    private val webView: WebView
) {

    private val activity get() = ctx as Activity

    @Volatile private var printer: EscPosPrinter? = null
    @Volatile private var charsetEncoding: String = "windows-1252"
    @Volatile private var charsetId: Int = 16

    // Parametros pendientes mientras se resuelve el permiso USB.
    private data class UsbPending(val dpi: Int, val width: Float, val cpl: Int, val cb: String?)
    @Volatile private var usbPending: UsbPending? = null
    @Volatile private var usbReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val pending = usbPending ?: return
            usbPending = null
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (!granted || device == null) {
                fail(pending.cb, "Permiso USB denegado")
                return
            }
            val usbManager = c.getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usbManager == null) { fail(pending.cb, "USB no disponible"); return }
            connectAsync(pending.cb) {
                EscPosPrinter(
                    UsbConnection(usbManager, device),
                    pending.dpi, pending.width, pending.cpl,
                    EscPosCharsetEncoding(charsetEncoding, charsetId)
                )
            }
        }
    }

    // ---------------------------------------------------------------- charset

    @JavascriptInterface
    fun setCharsetEncoding(charsetencoding: String) { charsetEncoding = charsetencoding }

    @JavascriptInterface
    fun setCharsetId(id: Int) { charsetId = id }

    // ------------------------------------------------------------- conexiones

    @JavascriptInterface
    fun openPrinterTCP(
        address: String, port: Int, timeout: Int,
        prnDpi: Int, prnWidth: Float, prnCharPerLine: Int
    ) = openPrinterTCP(address, port, timeout, prnDpi, prnWidth, prnCharPerLine, null)

    @JavascriptInterface
    fun openPrinterTCP(
        address: String, port: Int, timeout: Int,
        prnDpi: Int, prnWidth: Float, prnCharPerLine: Int,
        callbackName: String?
    ) {
        connectAsync(callbackName) {
            EscPosPrinter(
                TcpConnection(address, port, timeout),
                prnDpi, prnWidth, prnCharPerLine,
                EscPosCharsetEncoding(charsetEncoding, charsetId)
            )
        }
    }

    @JavascriptInterface
    fun openPrinterBluetooth(prnDpi: Int, prnWidth: Float, prnCharPerLine: Int) =
        openPrinterBluetooth(prnDpi, prnWidth, prnCharPerLine, null)

    @JavascriptInterface
    fun openPrinterBluetooth(
        prnDpi: Int, prnWidth: Float, prnCharPerLine: Int,
        callbackName: String?
    ) {
        connectAsync(callbackName) {
            val conn = BluetoothPrintersConnections.selectFirstPaired()
                ?: error("No hay impresora Bluetooth emparejada")
            EscPosPrinter(
                conn, prnDpi, prnWidth, prnCharPerLine,
                EscPosCharsetEncoding(charsetEncoding, charsetId)
            )
        }
    }

    @JavascriptInterface
    fun openPrinterUSB(prnDpi: Int, prnWidth: Float, prnCharPerLine: Int) =
        openPrinterUSB(prnDpi, prnWidth, prnCharPerLine, null)

    @JavascriptInterface
    fun openPrinterUSB(
        prnDpi: Int, prnWidth: Float, prnCharPerLine: Int,
        callbackName: String?
    ) {
        activity.runOnUiThread {
            try {
                val usbConnection = UsbPrintersConnections.selectFirstConnected(ctx)
                val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
                if (usbConnection == null || usbManager == null) {
                    fail(callbackName, "No se encontro impresora USB conectada")
                    return@runOnUiThread
                }
                val device = usbConnection.device

                // Si ya tenemos permiso, conectamos directo.
                if (usbManager.hasPermission(device)) {
                    connectAsync(callbackName) {
                        EscPosPrinter(
                            UsbConnection(usbManager, device),
                            prnDpi, prnWidth, prnCharPerLine,
                            EscPosCharsetEncoding(charsetEncoding, charsetId)
                        )
                    }
                    return@runOnUiThread
                }

                // Si no, pedimos permiso y CONTINUAMOS EN EL RECEPTOR.
                usbPending = UsbPending(prnDpi, prnWidth, prnCharPerLine, callbackName)
                registerUsbReceiver()
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    ctx, 0, Intent(ACTION_USB_PERMISSION).setPackage(ctx.packageName), flags
                )
                usbManager.requestPermission(device, pi)
            } catch (e: Exception) {
                fail(callbackName, e.message ?: "Error al abrir USB")
            }
        }
    }

    @JavascriptInterface
    fun isConnected(): Boolean = printer != null

    @JavascriptInterface
    fun closePrinter() {
        val p = printer
        printer = null
        Thread { try { p?.disconnectPrinter() } catch (e: Exception) { } }.start()
    }

    @JavascriptInterface
    fun disconnectPrinter() {
        try {
            printer?.disconnectPrinter()
        } catch (e: Exception) {
            Log.w(Constants.TAG, "error al desconectar la impresora", e)
        }
    }

    // -------------------------------------------------------------- impresion

    @JavascriptInterface
    fun printFormattedText(text: String) = printFormattedText(text, null)

    @JavascriptInterface
    fun printFormattedText(text: String, callbackName: String?) =
        printAsync(callbackName) { it.printFormattedText(text) }

    @JavascriptInterface
    fun printFormattedTextAndCut(text: String) = printFormattedTextAndCut(text, null)

    @JavascriptInterface
    fun printFormattedTextAndCut(text: String, callbackName: String?) =
        printAsync(callbackName) { it.printFormattedTextAndCut(text) }

    @JavascriptInterface
    fun printFormattedTextAndOpenCashBox(text: String, feedPaper: Float) =
        printFormattedTextAndOpenCashBox(text, feedPaper, null)

    @JavascriptInterface
    fun printFormattedTextAndOpenCashBox(
        text: String, feedPaper: Float, callbackName: String?
    ) = printAsync(callbackName) { it.printFormattedTextAndOpenCashBox(text, feedPaper) }

    /**
     * Convierte una imagen (base64 o URL) al formato hexadecimal que entiende
     * el parser de texto, y la entrega al callback indicado.
     */
    @JavascriptInterface
    fun printImage(base64OrUrl: String, callbackName: String) =
        printImage(base64OrUrl, callbackName, 384, 0)

    @JavascriptInterface
    fun printImage(base64OrUrl: String, callbackName: String, maxWidth: Int) =
        printImage(base64OrUrl, callbackName, maxWidth, 0)

    @JavascriptInterface
    fun printImage(
        base64OrUrl: String, callbackName: String,
        maxWidth: Int, height: Int
    ) {
        val p = printer
        if (p == null) { fail(callbackName, "Impresora no conectada"); return }
        Thread {
            try {
                val bitmap = if (base64OrUrl.startsWith("http")) bitmapFromUrl(base64OrUrl)
                             else bitmapFromBase64(base64OrUrl)
                if (bitmap.width == 0 || bitmap.height == 0) error("Imagen invalida")
                val finalHeight = if (height <= 0)
                    (bitmap.height * maxWidth) / bitmap.width else height
                val resized = Bitmap.createScaledBitmap(bitmap, maxWidth, finalHeight, true)
                val hex = PrinterTextParserImg.bitmapToHexadecimalString(p, resized)
                if (resized != bitmap) bitmap.recycle()
                callback(callbackName, JSONObject.quote(hex))
            } catch (e: Exception) {
                fail(callbackName, e.message ?: "Error al procesar la imagen")
            }
        }.start()
    }

    // ----------------------------------------------------------------- helper

    private fun connectAsync(cb: String?, factory: () -> EscPosPrinter) {
        Thread {
            try {
                try { printer?.disconnectPrinter() } catch (e: Exception) { }
                printer = factory()
                toast("Impresora conectada")
                ok(cb, JSONObject().put("connected", true))
            } catch (e: Exception) {
                printer = null
                Log.w(Constants.TAG, "conexion de impresora fallida", e)
                toast("Error: ${e.message}")
                fail(cb, e.message ?: "No se pudo conectar")
            }
        }.start()
    }

    private fun printAsync(cb: String?, action: (EscPosPrinter) -> Unit) {
        val p = printer
        if (p == null) {
            toast("Impresora no conectada")
            fail(cb, "Impresora no conectada")
            return
        }
        Thread {
            try {
                action(p)
                ok(cb, JSONObject().put("printed", true))
            } catch (e: Exception) {
                Log.w(Constants.TAG, "impresion fallida", e)
                toast("Error: ${e.message}")
                fail(cb, e.message ?: "Error al imprimir")
            }
        }.start()
    }

    private fun bitmapFromBase64(b64: String): Bitmap {
        val clean = b64.substringAfter("base64,", b64).replace("\n", "").replace("\r", "")
        val bytes = android.util.Base64.decode(clean, android.util.Base64.NO_WRAP)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("No se pudo decodificar la imagen")
    }

    private fun bitmapFromUrl(url: String): Bitmap {
        val c = java.net.URL(url).openConnection()
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.doInput = true
        c.connect()
        return c.getInputStream().use { BitmapFactory.decodeStream(it) }
            ?: error("No se pudo decodificar la imagen")
    }

    private fun registerUsbReceiver() {
        if (usbReceiverRegistered) return
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(usbReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    fun dispose() {
        if (usbReceiverRegistered) {
            try { ctx.unregisterReceiver(usbReceiver) } catch (e: Exception) { }
            usbReceiverRegistered = false
        }
        closePrinter()
    }

    private fun toast(msg: String) = activity.runOnUiThread {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun ok(cb: String?, payload: JSONObject) =
        callback(cb, payload.put("ok", true).toString())

    private fun fail(cb: String?, error: String) {
        toast(error)
        callback(cb, JSONObject().put("ok", false).put("error", error).toString())
    }

    private fun callback(cb: String?, jsValue: String) {
        if (cb.isNullOrBlank()) return
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "try{ if(typeof $cb==='function') $cb($jsValue); }catch(e){}", null
            )
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "app.induxsoft.ebrowser.USB_PERMISSION"
    }
}
