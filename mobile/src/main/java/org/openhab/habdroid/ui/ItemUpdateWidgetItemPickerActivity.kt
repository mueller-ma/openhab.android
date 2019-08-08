package org.openhab.habdroid.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import org.openhab.habdroid.model.Item
import org.openhab.habdroid.ui.homescreenwidget.ItemUpdateWidget

class ItemUpdateWidgetItemPickerActivity(override var disabledMessageId: Int = 0) : AbstractItemPickerActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retryButton.setOnClickListener {
            loadItems()
        }

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    override fun finish(item: Item, state: String, mappedState: String) {
        val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(this)
        val label = if (item.label.isNullOrEmpty()) item.name else item.label
        val data = ItemUpdateWidget.ItemUpdateWidgetData(item.name, state, label, mappedState, item.category.orEmpty())
        ItemUpdateWidget.setupWidget(this, data, appWidgetId, appWidgetManager)
        ItemUpdateWidget.saveInfoForWidget(this, data, appWidgetId)
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
