package com.localchatbot.core.security

import com.sun.jna.platform.win32.Crypt32Util
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private val secureRandom = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also(secureRandom::nextBytes)

actual fun createSecretCipher(): SecretCipher? {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        os.contains("win") -> DpapiCipher
        os.contains("mac") -> AesGcmCipher(MacKeychainKeyStore)
        else -> if (SecretToolKeyStore.isAvailable()) AesGcmCipher(SecretToolKeyStore) else null
    }
}

/**
 * Windows: DPAPI cifra con una clave derivada de las credenciales del usuario de Windows.
 * Sin clave que guardar: otro usuario de la máquina, o el mismo archivo copiado a otro
 * equipo, no puede descifrarlo.
 */
private object DpapiCipher : SecretCipher {
    override fun encrypt(plain: String): String =
        Base64.getEncoder().encodeToString(Crypt32Util.cryptProtectData(plain.encodeToByteArray()))

    override fun decrypt(encoded: String): String =
        Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(encoded)).decodeToString()
}

/** Dónde vive la clave AES de [AesGcmCipher]: fuera de settings, en el almacén del SO. */
private interface KeyStore {
    fun load(): String?

    /**
     * Guarda una clave nueva **sin pisar** una existente. Que [load] falle no significa que
     * no haya clave (llavero bloqueado, servicio caído), y reemplazarla dejaría todos los
     * secretos ya cifrados imposibles de descifrar.
     */
    fun create(base64Key: String): Boolean
}

/**
 * AES-256-GCM con una clave aleatoria guardada en el almacén del SO. La clave se resuelve
 * (y se crea la primera vez) de forma perezosa y una sola vez por proceso: son llamadas a
 * procesos externos, y cada secreto se cifra o descifra con la misma.
 */
private class AesGcmCipher(private val store: KeyStore) : SecretCipher {
    private val key: SecretKeySpec? by lazy {
        val existing = store.load()
        val base64 = existing ?: Base64.getEncoder().encodeToString(secureRandomBytes(32)).takeIf { fresh ->
            // Se relee tras guardar: si el almacén no la devuelve igual, mejor no cifrar
            // nada que no se pueda descifrar en el próximo arranque.
            store.create(fresh) && store.load() == fresh
        }
        base64?.let { runCatching { SecretKeySpec(Base64.getDecoder().decode(it), "AES") }.getOrNull() }
    }

    override fun encrypt(plain: String): String? {
        val k = key ?: return null
        val iv = secureRandomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.encodeToByteArray()))
    }

    override fun decrypt(encoded: String): String? {
        val k = key ?: return null
        val bytes = Base64.getDecoder().decode(encoded)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

private const val KEY_SERVICE = "LocalChatBot"
private const val KEY_ACCOUNT = "settings-secrets-key"

/**
 * macOS: la clave va al llavero de inicio de sesión vía `/usr/bin/security`. Al crear el
 * ítem con esa herramienta, su ACL ya la incluye, así que leerla después no dispara el
 * diálogo de "permitir acceso al llavero" en cada arranque.
 */
private object MacKeychainKeyStore : KeyStore {
    override fun load(): String? =
        exec("/usr/bin/security", "find-generic-password", "-s", KEY_SERVICE, "-a", KEY_ACCOUNT, "-w")
            ?.takeIf { it.isNotEmpty() }

    // `-w <valor>` deja la clave un instante en la lista de procesos; es del mismo
    // usuario que ya podría leer el llavero desbloqueado, así que no abre nada nuevo.
    // Sin `-U`: si el ítem ya existe falla en vez de reemplazarlo.
    override fun create(base64Key: String): Boolean =
        exec("/usr/bin/security", "add-generic-password", "-s", KEY_SERVICE, "-a", KEY_ACCOUNT,
            "-l", "LocalChatBot (claves de configuración)", "-w", base64Key) != null
}

/**
 * Linux: Secret Service (GNOME Keyring / KWallet) vía `secret-tool`, de libsecret. El
 * secreto entra por stdin, no por argumentos. Sin `secret-tool` no hay cifrador: una clave
 * en un archivo junto a settings no protegería nada.
 */
private object SecretToolKeyStore : KeyStore {
    fun isAvailable(): Boolean = exec("which", "secret-tool") != null

    override fun load(): String? =
        exec("secret-tool", "lookup", "service", KEY_SERVICE, "account", KEY_ACCOUNT)
            ?.takeIf { it.isNotEmpty() }

    // `secret-tool store` reemplaza sin preguntar, así que antes se busca el ítem.
    override fun create(base64Key: String): Boolean =
        exec("secret-tool", "search", "service", KEY_SERVICE, "account", KEY_ACCOUNT).isNullOrEmpty() && exec(
            "secret-tool", "store", "--label=LocalChatBot (claves de configuración)",
            "service", KEY_SERVICE, "account", KEY_ACCOUNT,
            stdin = base64Key
        ) != null
}

/**
 * Ejecuta un comando y devuelve su stdout recortado (quizá vacío: `add-generic-password`
 * no imprime nada) si sale con 0; null si no. Espera ANTES de leer para que el timeout
 * sirva de algo (la salida es una línea, no llena el buffer del pipe): un llavero
 * bloqueado puede dejar el comando esperando.
 */
private fun exec(vararg command: String, stdin: String? = null): String? = runCatching {
    val process = ProcessBuilder(*command).start()
    process.outputStream.use { out -> stdin?.let { out.write(it.encodeToByteArray()) } }
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return@runCatching null
    }
    if (process.exitValue() != 0) return@runCatching null
    process.inputStream.bufferedReader().readText().trim()
}.getOrNull()
