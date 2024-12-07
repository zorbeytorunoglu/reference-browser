/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.awesomebar.AwesomeBarFeature
import mozilla.components.feature.awesomebar.provider.SearchSuggestionProvider
import mozilla.components.feature.readerview.view.ReaderViewControlsBar
import mozilla.components.feature.syncedtabs.SyncedTabsStorageSuggestionProvider
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.feature.tabs.toolbar.TabsToolbarFeature
import mozilla.components.feature.toolbar.WebExtensionToolbarFeature
import mozilla.components.lib.state.ext.flowScoped
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.performance.PerformanceLogger
import org.mozilla.reference.browser.search.AwesomeBarWrapper
import org.mozilla.reference.browser.tabs.TabsTrayFragment

/**
 * Fragment used for browsing the web within the main app.
 */
class BrowserFragment : BaseBrowserFragment(), UserInteractionHandler {
    private val thumbnailsFeature = ViewBoundFeatureWrapper<BrowserThumbnails>()
    private val readerViewFeature = ViewBoundFeatureWrapper<ReaderViewIntegration>()
    private val webExtToolbarFeature = ViewBoundFeatureWrapper<WebExtensionToolbarFeature>()
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()

    private val awesomeBar by lazy { requireView().findViewById<AwesomeBarWrapper>(R.id.awesomeBar) }
    private val toolbar by lazy { requireView().findViewById<BrowserToolbar>(R.id.toolbar) }
    private val engineView by lazy { requireView().findViewById<View>(R.id.engineView) as EngineView }
    private val readerViewBar by lazy { requireView().findViewById<ReaderViewControlsBar>(R.id.readerViewBar) }
    private val readerViewAppearanceButton by lazy {
        requireView().findViewById<FloatingActionButton>(R.id.readerViewAppearanceButton)
    }

    private fun initializeAwesomeBar() {
        AwesomeBarFeature(awesomeBar, toolbar, engineView)
            .addSearchProvider(
                requireContext(),
                requireComponents.core.store,
                requireComponents.useCases.searchUseCases.defaultSearch,
                fetchClient = requireComponents.core.client,
                mode = SearchSuggestionProvider.Mode.MULTIPLE_SUGGESTIONS,
                engine = requireComponents.core.engine,
                limit = 5,
                filterExactMatch = true
            )
            .addSessionProvider(
                resources,
                requireComponents.core.store,
                requireComponents.useCases.tabsUseCases.selectTab
            )
            .addHistoryProvider(
                requireComponents.core.historyStorage,
                requireComponents.useCases.sessionUseCases.loadUrl
            )
            .addClipboardProvider(
                requireContext(),
                requireComponents.useCases.sessionUseCases.loadUrl
            )

        awesomeBar.addProviders(
            SyncedTabsStorageSuggestionProvider(
                requireComponents.backgroundServices.syncedTabsStorage,
                requireComponents.useCases.tabsUseCases.addTab,
                requireComponents.core.icons
            )
        )
    }

    private fun initializeToolbar() {
        TabsToolbarFeature(
            toolbar = toolbar,
            sessionId = sessionId,
            store = requireComponents.core.store,
            showTabs = ::showTabs,
            lifecycleOwner = viewLifecycleOwner
        )
    }

    private fun initializeFeatures() {
        thumbnailsFeature.set(
            feature = BrowserThumbnails(
                requireContext(),
                engineView,
                requireComponents.core.store
            ),
            owner = viewLifecycleOwner,
            view = requireView()
        )

        readerViewFeature.set(
            feature = ReaderViewIntegration(
                requireContext(),
                requireComponents.core.engine,
                requireComponents.core.store,
                toolbar,
                readerViewBar,
                readerViewAppearanceButton
            ),
            owner = viewLifecycleOwner,
            view = requireView()
        )

        webExtToolbarFeature.set(
            feature = WebExtensionToolbarFeature(
                toolbar,
                requireContext().components.core.store
            ),
            owner = viewLifecycleOwner,
            view = requireView()
        )

        windowFeature.set(
            feature = WindowFeature(
                store = requireComponents.core.store,
                tabsUseCases = requireComponents.useCases.tabsUseCases
            ),
            owner = viewLifecycleOwner,
            view = requireView()
        )
    }

    private fun initializePageLoadMonitoring() {
        requireComponents.core.store.flowScoped(viewLifecycleOwner) { flow ->
            flow.map { it.selectedTab }
                .distinctUntilChanged()
                .collect { tab ->
                    when (tab?.content?.progress) {
                        0 -> PerformanceLogger.startMeasuring(PerformanceLogger.Tags.PAGE_LOAD_SPEED)
                        100 -> PerformanceLogger.stopMeasuring(PerformanceLogger.Tags.PAGE_LOAD_SPEED)
                    }
                }
        }
    }

    private suspend fun initializeComponents() = withContext(Dispatchers.Main) {
        initializeAwesomeBar()

        initializeToolbar()

        initializeFeatures()
    }

    override val shouldUseComposeUI: Boolean
        get() = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(
            getString(R.string.pref_key_compose_ui),
            false,
        )

    @Suppress("LongMethod")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        PerformanceLogger.startMeasuring(PerformanceLogger.Tags.BROWSER_FRAGMENT_DRAW)

        // Zorbey Torunoğlu:
        // listener to get the closest data to see how long does it take to become visible.
        view.viewTreeObserver.addOnGlobalLayoutListener {
            PerformanceLogger.stopMeasuring(PerformanceLogger.Tags.BROWSER_FRAGMENT_DRAW)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            initializeComponents()
        }

        // Zorbey Torunoğlu:
        // Measuring the page load speed.
        initializePageLoadMonitoring()

        engineView.setDynamicToolbarMaxHeight(resources.getDimensionPixelSize(R.dimen.browser_toolbar_height))
        PerformanceLogger.stopMeasuring(PerformanceLogger.Tags.BROWSER_FRAGMENT_CREATION)
    }

    private fun showTabs() {
        // For now we are performing manual fragment transactions here. Once we can use the new
        // navigation support library we may want to pass navigation graphs around.
        activity?.supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, TabsTrayFragment())
            commit()
        }
    }

    override fun onBackPressed(): Boolean =
        readerViewFeature.onBackPressed() || super.onBackPressed()

    companion object {
        fun create(sessionId: String? = null) = BrowserFragment().apply {
            arguments = Bundle().apply {
                putSessionId(sessionId)
            }
        }
    }
}
