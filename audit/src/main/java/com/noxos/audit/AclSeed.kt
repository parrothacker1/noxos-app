package com.noxos.audit

internal object AclSeed {
    const val REASON = "seeded: well-known"

    val WELL_KNOWN_SAFE = setOf(
        "8.8.8.8",
        "8.8.4.4",
        "1.1.1.1",
        "1.0.0.1"
    )
}
