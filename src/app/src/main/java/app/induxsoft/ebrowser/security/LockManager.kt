package app.induxsoft.ebrowser.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Candado de la configuracion.
 *
 * Alcance real, dicho sin adornos: esto impide que el operador de caja cambie
 * la configuracion desde la interfaz. NO es proteccion contra alguien con
 * acceso fisico, adb o root, que puede borrar los datos de la aplicacion y
 * quedarse sin candado. Es una cortina con seguro, no una caja fuerte, y asi
 * esta documentado en el manual de usuario.
 *
 * Se guarda PBKDF2-SHA1 con sal aleatoria. No se usa EncryptedSharedPreferences
 * a proposito: agrega una dependencia y no cambia el modelo de amenaza real.
 *
 * Si se pierde la contrasena, la unica salida es desinstalar e-browser. Esto es
 * deliberado: no hay puerta trasera.
 */
class LockManager(ctx: Context) {

    private val p = ctx.getSharedPreferences("ebrowser_lock", Context.MODE_PRIVATE)

    /** Desbloqueo valido solo mientras el proceso vive. */
    @Volatile private var sessionUnlocked = false

    val isLocked: Boolean get() = p.contains("hash")

    /** true si se puede cambiar la configuracion ahora mismo. */
    val isUnlocked: Boolean get() = !isLocked || sessionUnlocked

    fun unlock(password: String): Boolean {
        if (!isLocked) return true
        val salt = Base64.decode(p.getString("salt", "") ?: "", Base64.NO_WRAP)
        val iter = p.getInt("iter", ITERATIONS)
        val want = p.getString("hash", "") ?: ""
        val got = derive(password, salt, iter)
        val ok = constantTimeEquals(want, got)
        if (ok) sessionUnlocked = true
        return ok
    }

    /**
     * Establece o cambia la contrasena. Si ya hay una, exige la anterior.
     * @return true si se aplico
     */
    fun setPassword(newPassword: String, oldPassword: String?): Boolean {
        if (isLocked && !sessionUnlocked) {
            if (oldPassword == null || !unlock(oldPassword)) return false
        }
        if (newPassword.length < 4) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        p.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putInt("iter", ITERATIONS)
            .putString("hash", derive(newPassword, salt, ITERATIONS))
            .apply()
        sessionUnlocked = true
        return true
    }

    fun clearPassword(currentPassword: String?): Boolean {
        if (isLocked && !sessionUnlocked) {
            if (currentPassword == null || !unlock(currentPassword)) return false
        }
        p.edit().remove("salt").remove("iter").remove("hash").apply()
        sessionUnlocked = true
        return true
    }

    fun lockSession() { sessionUnlocked = false }

    private fun derive(pwd: String, salt: ByteArray, iter: Int): String {
        val spec = PBEKeySpec(pwd.toCharArray(), salt, iter, 256)
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        return Base64.encodeToString(f.generateSecret(spec).encoded, Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    companion object { private const val ITERATIONS = 20000 }
}
