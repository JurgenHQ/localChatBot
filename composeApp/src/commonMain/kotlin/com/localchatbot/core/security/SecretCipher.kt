package com.localchatbot.core.security

/**
 * Cifra los secretos (API keys, headers y env de MCP, PIN del acceso remoto) antes de
 * guardarlos en settings, con una clave que no vive en el mismo archivo: DPAPI en Windows,
 * el Keychain en macOS, el Secret Service (`secret-tool`) en Linux y el Android Keystore.
 *
 * Antes iban en texto plano en `settings.xml` / SharedPreferences: cualquier proceso del
 * usuario, una copia de seguridad o una carpeta sincronizada con la nube se los llevaba.
 *
 * Los textos cifrados que devuelve [encrypt] ya vienen codificados (base64) y listos para
 * guardarse; [SecretSealing] les añade el prefijo que los distingue del texto plano.
 */
interface SecretCipher {
    fun encrypt(plain: String): String?
    fun decrypt(encoded: String): String?
}

/**
 * Cifrador de la plataforma, o null si no hay almacén seguro disponible: iOS (NSUserDefaults
 * ya vive en el sandbox de la app, cifrado por Data Protection) y Linux sin `secret-tool`.
 * Con null los secretos se guardan como hasta ahora, en texto plano.
 */
expect fun createSecretCipher(): SecretCipher?

/** Bytes de un generador criptográficamente seguro (SecureRandom / SecRandomCopyBytes). */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * Formato en disco de un secreto: `enc1:<cifrado>` o el valor tal cual. Lo que no lleva el
 * prefijo se lee como texto plano, que es como quedaron los secretos guardados antes de
 * cifrarlos (y como se guardan donde no hay [SecretCipher]).
 */
object SecretSealing {
    const val PREFIX = "enc1:"

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /** Cifra [value]. Si no hay cifrador o falla, lo deja en claro: perder la key sería peor. */
    fun seal(value: String, cipher: SecretCipher?): String {
        if (value.isEmpty() || cipher == null || isSealed(value)) return value
        val encrypted = runCatching { cipher.encrypt(value) }.getOrNull() ?: return value
        return PREFIX + encrypted
    }

    /**
     * Descifra [stored]. Uno que no se puede descifrar (llavero bloqueado en este arranque,
     * settings copiados de otra máquina) se devuelve **tal cual, cifrado**: [seal] no lo
     * vuelve a tocar, así que el próximo guardado lo conserva y un arranque con el llavero
     * disponible lo recupera. Devolver "" lo borraría en la primera escritura de settings.
     * El precio es que la key se ve como `enc1:…` en Ajustes y el servidor la rechaza, que
     * es justo la pista de que hay que volver a escribirla.
     */
    fun unseal(stored: String, cipher: SecretCipher?): String {
        if (!isSealed(stored)) return stored
        val body = stored.removePrefix(PREFIX)
        return cipher?.let { runCatching { it.decrypt(body) }.getOrNull() } ?: stored
    }
}

/**
 * PIN del acceso remoto: 8 caracteres en mayúsculas de un alfabeto sin ambiguos (ni 0/O
 * ni 1/I), 40 bits, frente a los ~20 de los 6 dígitos de antes. Sale de un generador
 * seguro: `kotlin.random.Random` es predecible.
 */
fun generateRemotePin(randomBytes: (Int) -> ByteArray = ::secureRandomBytes): String {
    // 32 símbolos: `byte % 32` no tiene sesgo porque 256 es múltiplo de 32.
    val bytes = randomBytes(PIN_LENGTH)
    return buildString { bytes.forEach { append(PIN_ALPHABET[(it.toInt() and 0xFF) % PIN_ALPHABET.length]) } }
}

const val PIN_LENGTH = 8
const val PIN_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
