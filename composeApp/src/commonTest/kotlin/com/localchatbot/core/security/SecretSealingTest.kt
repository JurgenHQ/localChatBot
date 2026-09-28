package com.localchatbot.core.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretSealingTest {

    /** Cifrador de juguete reversible: basta para probar el formato, no la criptografía. */
    private object ReverseCipher : SecretCipher {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(encoded: String) = encoded.reversed()
    }

    private object BrokenCipher : SecretCipher {
        override fun encrypt(plain: String): String? = null
        override fun decrypt(encoded: String): String = error("llavero bloqueado")
    }

    @Test
    fun cifraYDescifraIdaYVuelta() {
        val sealed = SecretSealing.seal("sk-123", ReverseCipher)
        assertEquals("enc1:321-ks", sealed)
        assertEquals("sk-123", SecretSealing.unseal(sealed, ReverseCipher))
    }

    @Test
    fun loGuardadoEnClaroSeLeeTalCual() {
        // Secretos guardados antes de que existiera el cifrado.
        assertEquals("sk-legacy", SecretSealing.unseal("sk-legacy", ReverseCipher))
    }

    @Test
    fun sinCifradorQuedaEnClaro() {
        assertEquals("sk-123", SecretSealing.seal("sk-123", null))
    }

    @Test
    fun unFalloAlCifrarNoPierdeLaKey() {
        assertEquals("sk-123", SecretSealing.seal("sk-123", BrokenCipher))
    }

    @Test
    fun loQueNoSePuedeDescifrarSeConservaCifrado() {
        // Devolver "" lo borraría en la próxima escritura de settings.
        val sealed = "enc1:abc"
        assertEquals(sealed, SecretSealing.unseal(sealed, BrokenCipher))
        assertEquals(sealed, SecretSealing.unseal(sealed, null))
        // …y volver a sellarlo no lo cifra dos veces.
        assertEquals(sealed, SecretSealing.seal(sealed, ReverseCipher))
    }

    @Test
    fun vacioSigueVacio() {
        assertEquals("", SecretSealing.seal("", ReverseCipher))
    }

    @Test
    fun elPinTieneOchoCaracteresDelAlfabeto() {
        assertEquals(32, PIN_ALPHABET.length)
        val pin = generateRemotePin()
        assertEquals(PIN_LENGTH, pin.length)
        assertTrue(pin.all { it in PIN_ALPHABET }, pin)
    }

    @Test
    fun elPinSaleDeLosBytesSinSesgo() {
        // 0..255 repartido módulo 32: cada símbolo aparece exactamente 8 veces.
        val allBytes = ByteArray(256) { it.toByte() }
        var offset = 0
        val counts = mutableMapOf<Char, Int>()
        repeat(32) {
            val pin = generateRemotePin { n -> allBytes.copyOfRange(offset, offset + n).also { offset += n } }
            pin.forEach { c -> counts[c] = (counts[c] ?: 0) + 1 }
        }
        assertEquals(32, counts.size)
        assertTrue(counts.values.all { it == 8 }, counts.toString())
    }

    @Test
    fun dosPinsSeguidosSonDistintos() {
        assertNotEquals(generateRemotePin(), generateRemotePin())
    }
}
