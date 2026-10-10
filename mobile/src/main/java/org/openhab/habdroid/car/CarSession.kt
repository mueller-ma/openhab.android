/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.car

import android.content.Intent
import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.openhab.habdroid.R
import org.openhab.habdroid.model.LinkedPage
import org.openhab.habdroid.model.Sitemap
import org.openhab.habdroid.model.Widget
import org.openhab.habdroid.util.buildSitemapSourceId
import org.openhab.habdroid.util.getConnectionFactory
import org.openhab.habdroid.util.getDefaultCarSitemapName
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.onDestroy

class CarSession(
    sitemapsFlow: Flow<Result<List<Sitemap>>?>,
    private val onPageListChanged: () -> Unit,
    private val onSendWidgetCommand: (widget: Widget, command: String, sourceId: String) -> Unit
) : Session() {
    private var latestSitemapResult: Result<SitemapLookupResult>? = null
    private var rootScreen: WidgetGridScreen? = null
    private val pageStack = mutableListOf<WidgetGridScreen>()
    val pageUrls get() = pageStack.map { it.url }.filter { it.isNotEmpty() }

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                sitemapsFlow.collect { sitemapsResult ->
                    val selectedSitemap = carContext.getPrefs().getDefaultCarSitemapName()
                    val sitemapResult = sitemapsResult
                        ?.map { SitemapLookupResult(it, selectedSitemap) }

                    if (sitemapResult != latestSitemapResult) {
                        Log.d(TAG, "Got new sitemap result $sitemapResult")
                        latestSitemapResult = sitemapResult
                        showSitemapResult(sitemapResult)
                    }
                }
            }
        }
    }

    fun handlePageUpdate(pageUrl: String, widgets: List<Widget>) {
        pageStack.firstOrNull { it.url == pageUrl }
            ?.updateWidgets(widgets)
    }

    fun handleWidgetUpdate(pageUrl: String, widget: Widget) {
        pageStack.firstOrNull { it.url == pageUrl }
            ?.updateWidget(widget)
    }

    fun handleLoadFailure(reason: Throwable?) {
        val screenManager = carContext.getCarService(ScreenManager::class.java)
        screenManager.popToRoot()
        screenManager.push(createErrorScreen(null, reason, true))
        // Make sure the next sitemap result is applied even if it didn't change, so the error screen goes away
        latestSitemapResult = null
    }

    override fun onCreateScreen(intent: Intent): Screen {
        val root = createWidgetListScreen("", "", carContext.getString(R.string.app_name), 0)
        rootScreen = root
        return root
    }

    // The host only hands back template steps when screens are popped, so the root screen is never replaced:
    // its content is updated in place and errors are shown on top of it.
    private fun showSitemapResult(result: Result<SitemapLookupResult>?) {
        val root = rootScreen ?: return
        val screenManager = carContext.getCarService(ScreenManager::class.java)
        screenManager.popToRoot()

        val selectedSitemap = result?.getOrNull()?.let { (sitemaps, selectedSitemapName) ->
            sitemaps.firstOrNull { it.name == selectedSitemapName }
        }
        // Drop the connection of the previous page first, so the page is reloaded even if its URL didn't change
        root.showPage("", "", carContext.getString(R.string.app_name))
        onPageListChanged()
        if (selectedSitemap != null) {
            root.showPage(selectedSitemap.homepageLink, selectedSitemap.name, cleanTitle(selectedSitemap.label))
            onPageListChanged()
        }

        when {
            result == null || selectedSitemap != null -> {}

            result.isSuccess -> screenManager.push(
                createErrorScreen(carContext.getString(R.string.car_error_sitemap_not_found), null, false)
            )

            else -> screenManager.push(createErrorScreen(null, result.exceptionOrNull(), true))
        }
    }

    private fun createErrorScreen(message: CharSequence?, reason: Throwable?, allowRetry: Boolean): ErrorScreen {
        val actionLabel = carContext.getString(
            if (allowRetry) R.string.car_error_retry_button else R.string.car_error_close_button
        )
        return ErrorScreen(carContext, message, reason, actionLabel) {
            if (allowRetry) {
                carContext.getConnectionFactory().restartNetworkCheck()
            } else {
                carContext.finishCarApp()
            }
        }
    }

    private fun createWidgetListScreen(url: String, id: String, title: String, nestingDepth: Int): WidgetGridScreen {
        lateinit var screen: WidgetGridScreen
        screen = WidgetGridScreen(
            carContext,
            url,
            id,
            nestingDepth,
            cleanTitle(title),
            onPageSelected = { page -> openWidgetListScreen(page, nestingDepth + 1) },
            // The root screen's page can change, so look up its ID when sending the command
            onWidgetCommand = { widget, command -> onSendWidgetCommand(widget, command, buildSourceId(screen.id)) }
        )
        screen.lifecycle.onDestroy {
            if (pageStack.remove(screen)) {
                onPageListChanged()
            }
        }
        pageStack += screen
        onPageListChanged()
        return screen
    }

    private fun buildSourceId(id: String): String {
        val sitemapName = pageStack.getOrNull(0)?.id ?: id
        val pageId = if (sitemapName == id) null else id
        return carContext.buildSitemapSourceId(sitemapName, pageId, "org.openhab.android.car")
    }

    private fun openWidgetListScreen(page: LinkedPage, nestingDepth: Int) {
        Log.d(TAG, "Open widget list for page $page")
        val screen = createWidgetListScreen(page.link, page.id, page.title, nestingDepth)
        screen.screenManager.push(screen)
    }

    // Omit state portion of the label, as we can't update it anyway without it counting against the step limit
    private fun cleanTitle(title: String) = title.substringBefore("[").trim()

    private data class SitemapLookupResult(val sitemaps: List<Sitemap>, val selectedSitemapName: String?)

    companion object {
        const val TAG = "CarSession"
    }
}
