package com.localchatbot.domain.usecase

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LooksLikeQuestionTest {

    private fun q(text: String) = SendMessageUseCase.looksLikeQuestionToUser(text)

    @Test
    fun cierraConUnaPregunta() {
        assertTrue(q("He revisado el código.\n¿Quieres que aplique el cambio?"))
    }

    @Test
    fun ignoraElFormatoAlFinalDeLaLinea() {
        assertTrue(q("Listo.\n**¿Continúo con el siguiente paso?**"))
        assertTrue(q("`¿Lo borro?`"))
    }

    @Test
    fun unCuestionarioCuentaAunqueNoTermineEnPregunta() {
        assertTrue(q("1. ¿Qué lenguaje usas?\n2. ¿Qué framework?\n\nCon eso empiezo."))
    }

    @Test
    fun unaPreguntaRetoricaEnMedioNoCuenta() {
        assertFalse(q("¿Por qué falla? Porque el índice empieza en 1.\nYa lo corregí."))
    }

    @Test
    fun textoVacioNoEsPregunta() {
        assertFalse(q(""))
        assertFalse(q("\n  \n"))
    }
}
