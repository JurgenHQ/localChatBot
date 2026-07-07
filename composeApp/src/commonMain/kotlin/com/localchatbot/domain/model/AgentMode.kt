package com.localchatbot.domain.model

/**
 * Modo de operación del agente.
 *
 * - [Build]: comportamiento normal — el modelo puede crear/editar/borrar archivos.
 * - [Plan]: solo lectura — las tools que mutan el proyecto (create/edit/multi_edit/
 *   delete/create_dir/save_image) se desactivan (no se envían al modelo). El modelo
 *   investiga y propone un plan; `run_command` sigue disponible pero el prompt le pide
 *   usarlo solo para inspección de lectura.
 */
enum class AgentMode {
    Plan,
    Build
}
