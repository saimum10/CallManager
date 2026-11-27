package com.saimum.callmanager.recording

enum class AutoDeleteInterval {
    OFF,
    AFTER_3_DAYS,
    AFTER_7_DAYS,
    AFTER_15_DAYS,
    AFTER_30_DAYS;

    /** Null means "never auto-delete." */
    fun toMaxAgeMillisOrNull(): Long? {
        val days = when (this) {
            OFF -> return null
            AFTER_3_DAYS -> 3
            AFTER_7_DAYS -> 7
            AFTER_15_DAYS -> 15
            AFTER_30_DAYS -> 30
        }
        return days * 24L * 60L * 60L * 1000L
    }
}
