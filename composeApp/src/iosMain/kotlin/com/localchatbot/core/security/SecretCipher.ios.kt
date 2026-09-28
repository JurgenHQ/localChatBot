package com.localchatbot.core.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(size: Int): ByteArray {
    val out = ByteArray(size)
    if (size == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, size.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes falló ($status)" }
    return out
}

/**
 * Sin cifrador en iOS, a sabiendas: NSUserDefaults vive dentro del sandbox de la app,
 * que ningún otro proceso puede leer y que iOS cifra en disco con Data Protection. Lo que
 * [SecretCipher] resuelve en desktop (un `settings.xml` legible por cualquier proceso del
 * usuario) aquí no existe. Si hiciera falta, el paso siguiente es guardar cada secreto en
 * el Keychain en vez de cifrarlo.
 */
actual fun createSecretCipher(): SecretCipher? = null
