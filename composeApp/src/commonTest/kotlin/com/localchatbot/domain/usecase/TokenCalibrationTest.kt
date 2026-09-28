package com.localchatbot.domain.usecase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenCalibrationTest {

    @Test
    fun laPrimeraMedidaFijaElFactor() {
        assertEquals(1.3, TokenCalibration.update(null, measuredTokens = 13_000, estimatedTokens = 10_000)!!, 1e-9)
    }

    @Test
    fun lasSiguientesSePromedianConElAnterior() {
        assertEquals(1.4, TokenCalibration.update(1.3, measuredTokens = 15_000, estimatedTokens = 10_000)!!, 1e-9)
    }

    @Test
    fun unaMuestraChicaNoCambiaNada() {
        assertNull(TokenCalibration.update(null, measuredTokens = 300, estimatedTokens = 100))
        assertEquals(1.2, TokenCalibration.update(1.2, measuredTokens = 300, estimatedTokens = 100))
        assertEquals(1.2, TokenCalibration.update(1.2, measuredTokens = 0, estimatedTokens = 10_000))
    }

    @Test
    fun elFactorQuedaAcotado() {
        assertEquals(TokenCalibration.MAX_FACTOR, TokenCalibration.update(null, 100_000, 1_000))
        assertEquals(TokenCalibration.MIN_FACTOR, TokenCalibration.update(null, 100, 1_000))
    }

    @Test
    fun elPresupuestoSePasaAUnidadesEstimadas() {
        assertEquals(8_000, TokenCalibration.estimatedBudget(10_000, 1.25))
        assertEquals(10_000, TokenCalibration.estimatedBudget(10_000, null))
    }
}
