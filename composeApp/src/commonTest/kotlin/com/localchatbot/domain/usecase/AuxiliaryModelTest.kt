package com.localchatbot.domain.usecase

import com.localchatbot.domain.model.AppPreferences
import com.localchatbot.domain.model.ConnectionConfig
import com.localchatbot.domain.model.ConnectionProfile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuxiliaryModelTest {

    private val main = ConnectionConfig(ip = "gpu", model = "qwen-32b")
    private val aux = ConnectionConfig(ip = "gpu", model = "qwen-1.5b")

    @Test
    fun usaElAuxiliarSiResponde() = runTest {
        val used = mutableListOf<String>()
        val out = withAuxiliaryModel(aux, main) { c -> used += c.model; "resumen de ${c.model}" }
        assertEquals("resumen de qwen-1.5b", out)
        assertEquals(listOf("qwen-1.5b"), used)
    }

    @Test
    fun caeAlPrincipalSiElAuxiliarFallaONoDevuelveNada() = runTest {
        assertEquals("qwen-32b", withAuxiliaryModel(aux, main) { c -> c.model.takeIf { it == "qwen-32b" } })
        assertEquals("qwen-32b", withAuxiliaryModel(aux, main) { c ->
            if (c == aux) error("modelo no cargado") else c.model
        })
    }

    @Test
    fun sinAuxiliarVaDirectoAlPrincipal() = runTest {
        val used = mutableListOf<String>()
        withAuxiliaryModel(null, main) { c -> used += c.model; "ok" }
        assertEquals(listOf("qwen-32b"), used)
    }

    @Test
    fun elPerfilAuxiliarDebeExistirYEstarCompleto() {
        val profiles = listOf(
            ConnectionProfile("p1", "Grande", main),
            ConnectionProfile("p2", "Chico", aux),
            ConnectionProfile("p3", "A medias", ConnectionConfig(ip = "x", model = ""))
        )
        val prefs = AppPreferences.Default.copy(connectionProfiles = profiles, activeConnectionProfileId = "p1")
        assertEquals(aux, prefs.copy(auxiliaryProfileId = "p2").auxiliaryConnection)
        assertNull(prefs.copy(auxiliaryProfileId = "p3").auxiliaryConnection)
        assertNull(prefs.copy(auxiliaryProfileId = "borrado").auxiliaryConnection)
        assertNull(prefs.auxiliaryConnection)
    }
}
