package com.widglet.app.data

/** Rough, honest estimate of how much a widget's timing choice costs in battery. */
object BatteryImpact {
    enum class Level { LOW, MEDIUM, HIGH }

    /** Times per day Widglet itself wakes up to change the photo (null = depends on the user). */
    fun wakeupsPerDay(cfg: WidgetConfig): Int? = when (cfg.timing) {
        TimingMode.INTERVAL -> 1440 / cfg.intervalMinutes.coerceAtLeast(15)
        TimingMode.DAILY -> 1
        TimingMode.SLIDESHOW -> 0
        TimingMode.UNLOCK, TimingMode.SCREEN_ON, TimingMode.TAP, TimingMode.MANUAL -> null
    }

    fun of(cfg: WidgetConfig): Level {
        val cutoutCost = cfg.cutout && !cfg.lazyCutouts
        return when (cfg.timing) {
            TimingMode.INTERVAL -> when {
                cfg.intervalMinutes <= 15 -> if (cfg.cutout) Level.HIGH else Level.MEDIUM
                cfg.intervalMinutes < 60 -> Level.MEDIUM
                else -> if (cutoutCost) Level.MEDIUM else Level.LOW
            }
            TimingMode.DAILY, TimingMode.TAP, TimingMode.MANUAL -> if (cutoutCost) Level.MEDIUM else Level.LOW
            TimingMode.SLIDESHOW -> if (cfg.slideshowSeconds < 10) Level.HIGH else Level.MEDIUM
            TimingMode.UNLOCK, TimingMode.SCREEN_ON -> Level.MEDIUM
        }
    }
}
