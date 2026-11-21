package de.mm20.launcher2.searchable

/**
 * Launch statistics of a saved searchable.
 */
data class UsageStats(
    val launchCount: Int,
    val weight: Double,
    /**
     * Unix timestamp (ms) of the last launch, 0 if unknown
     */
    val lastLaunch: Long,
)
