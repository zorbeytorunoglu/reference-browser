/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.action.SystemAction
import mozilla.components.concept.engine.webextension.isUnsupported
import mozilla.components.concept.push.PushProcessor
import mozilla.components.feature.addons.update.GlobalAddonDependencyProvider
import mozilla.components.support.base.log.Log
import mozilla.components.support.base.log.sink.AndroidLogSink
import mozilla.components.support.ktx.android.content.isMainProcess
import mozilla.components.support.ktx.android.content.runOnlyInMainProcess
import mozilla.components.support.rusthttp.RustHttpConfig
import mozilla.components.support.rustlog.RustLog
import mozilla.components.support.webextensions.WebExtensionSupport
import org.mozilla.reference.browser.ext.isCrashReportActive
import org.mozilla.reference.browser.performance.PerformanceLogger
import org.mozilla.reference.browser.push.PushFxaIntegration
import org.mozilla.reference.browser.push.WebPushEngineIntegration
import java.util.concurrent.TimeUnit

open class BrowserApplication : Application() {

    // it would be better to keep the scope and the dispatchers in a DI to have more control over them
    private val applicationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val components by lazy { Components(this) }

    private fun initializeCriticalComponents() {
        setupLogging()

        RustHttpConfig.setClient(lazy { components.core.client })

        applicationScope.launch(Dispatchers.Main) {
            // warmup requires main thread
            components.core.engine.warmUp()
        }

    }

    private fun initializeNonCriticalComponents() {

        applicationScope.launch(Dispatchers.Main) {
            // they require main thread
            launch { initializeAddons() }
            launch { initializePushFeatures() }
        }

        applicationScope.launch(Dispatchers.IO) {
            components.core.fileUploadsDirCleaner.cleanUploadsDirectory()
        }
    }

    private fun initializeAddons() {
        GlobalAddonDependencyProvider.initialize(
            components.core.addonManager,
            components.core.addonUpdater
        )

        WebExtensionSupport.initialize(
            runtime = components.core.engine,
            store = components.core.store,
            onNewTabOverride = { _, engineSession, url ->
                val tabId = components.useCases.tabsUseCases.addTab(
                    url = url,
                    selectTab = true,
                    engineSession = engineSession
                )
                tabId
            },
            onCloseTabOverride = { _, sessionId ->
                components.useCases.tabsUseCases.removeTab(sessionId)
            },
            onSelectTabOverride = { _, sessionId ->
                components.useCases.tabsUseCases.selectTab(sessionId)
            },
            onExtensionsLoaded = { extensions ->
                components.core.addonUpdater.registerForFutureUpdates(extensions)

                val checker = components.core.supportedAddonsChecker
                val hasUnsupportedAddons = extensions.any { it.isUnsupported() }
                if (hasUnsupportedAddons) {
                    checker.registerForChecks()
                } else {
                    checker.unregisterForChecks()
                }
            },
            onUpdatePermissionRequest = components.core.addonUpdater::onUpdatePermissionRequest
        )
    }

    private fun initializePushFeatures() {
        components.push.feature?.let { pushFeature ->

            PushProcessor.install(pushFeature)

            WebPushEngineIntegration(components.core.engine, pushFeature).start()

            PushFxaIntegration(pushFeature, lazy { components.backgroundServices.accountManager }).launch()

            pushFeature.initialize()
        }
    }

    private suspend fun initializeStoreAndState() {
        withContext(Dispatchers.Main) {
            val store = components.core.store
            val sessionStorage = components.core.sessionStorage

            components.useCases.tabsUseCases.restore(sessionStorage)

            sessionStorage.autoSave(store)
                .periodicallyInForeground(interval = 30, unit = TimeUnit.SECONDS)
                .whenGoingToBackground()
                .whenSessionsChange()
        }
    }

    override fun onCreate() {
        PerformanceLogger.startMeasuring(PerformanceLogger.Tags.BROWSER_APPLICATION_CREATION)
        super.onCreate()

        if (!isMainProcess()) {
            setupCrashReporting(this)
            return
        }

        initializeCriticalComponents()

        applicationScope.launch(Dispatchers.Default) {
            initializeStoreAndState()
            initializeNonCriticalComponents()
        }

        PerformanceLogger.stopMeasuring(PerformanceLogger.Tags.BROWSER_APPLICATION_CREATION)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        runOnlyInMainProcess {
            components.core.store.dispatch(SystemAction.LowMemoryAction(level))
            components.core.icons.onTrimMemory(level)
        }
    }

    companion object {
        const val NON_FATAL_CRASH_BROADCAST = "org.mozilla.reference.browser"
    }
}

private fun setupLogging() {
    Log.addSink(AndroidLogSink())
    RustLog.enable()
}

private fun setupCrashReporting(application: BrowserApplication) {
    if (isCrashReportActive) {
        application
            .components
            .analytics
            .crashReporter.install(application)
    }
}
