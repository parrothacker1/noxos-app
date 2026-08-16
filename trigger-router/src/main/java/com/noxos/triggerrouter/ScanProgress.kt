package com.noxos.triggerrouter

enum class ScanStep { IDLE, BOOTING, EXECUTING, SANITIZING, DESTROYING, DONE }

data class ScanProgress(
    val step: ScanStep = ScanStep.IDLE,
    val filename: String? = null,
    val stepDurationsMillis: Map<ScanStep, Long> = emptyMap()
) {
    fun toCsv(): String = stepDurationsMillis.entries.joinToString(",") { "${it.key.name}:${it.value}" }
}
