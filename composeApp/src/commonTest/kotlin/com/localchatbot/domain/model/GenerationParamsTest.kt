package com.localchatbot.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class GenerationParamsTest {

    private val global = GenerationParams(temperature = 0.7, topP = 0.9, maxTokens = 2048, minP = 0.05)

    @Test
    fun elPerfilGanaCampoACampo() {
        val profile = GenerationParams(temperature = 0.2, repeatPenalty = 1.1)
        val effective = profile.orElse(global)
        assertEquals(0.2, effective.temperature)
        assertEquals(1.1, effective.repeatPenalty)
        // Lo que el perfil no fija se hereda.
        assertEquals(0.9, effective.topP)
        assertEquals(2048, effective.maxTokens)
        assertEquals(0.05, effective.minP)
    }

    @Test
    fun unPerfilVacioEsElGlobal() {
        assertEquals(global, GenerationParams().orElse(global))
    }
}
