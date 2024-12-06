package org.mozilla.reference.browser.performance

import android.os.SystemClock
import mozilla.components.support.base.log.logger.Logger

private const val LOGGER_TAG = "PerformanceMetrics"

object PerformanceLogger {

    private val logger = Logger(LOGGER_TAG)
    private val timeMap = mutableMapOf<String, Long>()

    fun startMeasuring(tag: String) {
        timeMap[tag] = SystemClock.elapsedRealtime()
    }

    fun stopMeasuring(tag: String) {
        timeMap[tag]?.let { startTime ->
            val duration = SystemClock.elapsedRealtime() - startTime
            logger.debug("$tag took ${duration}ms")
            timeMap.remove(tag)
        }
    }

    fun logMemoryUsage(tag: String) {
        val runtime = Runtime.getRuntime()
        val usedMemory = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        logger.debug("$tag - Used Memory: ${usedMemory}MB")
    }

    object Tags {
        const val BROWSER_APPLICATION_CREATION = "BrowserApplication_Creation"
        const val BROWSER_ACTIVITY_CREATION = "BrowserActivity_Creation"
        const val BROWSER_FRAGMENT_CREATION = "BrowserFragment_Creation"
        const val MEMORY_INITIAL = "Memory_Initial"
        const val BROWSER_FRAGMENT_DRAW = "BrowserFragment_Draw"
        const val PAGE_LOAD_SPEED = "Page_LoadSpeed"
    }

}