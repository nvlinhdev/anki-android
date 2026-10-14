// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.PendingIntentCompat
import com.ichi2.anki.IntentHandler
import com.ichi2.anki.R
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.worker.WordReminderWorker

/**
 * Shows the words picked by [WordReminderWorker] one after another, switching every
 * [Prefs.wordReminderSlideSeconds]. The launcher does the switching, so it costs nothing
 * while the screen is off. Tapping a word opens the review of its deck.
 */
class WordReminderWidget : AnalyticsWidgetProvider() {
    override fun performUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: AppWidgetIds,
    ) {
        appWidgetManager.updateAppWidget(appWidgetIds.ids, buildViews(context))
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        super.onReceive(context, intent)
        val views = buildViews(context)
        // the replacement, setDisplayedChild, needs the current slide, which only the launcher knows.
        // A full update: the system merges a partial one into the last views, keeping both showNext and showPrevious
        @Suppress("DEPRECATION")
        when (intent.action) {
            ACTION_PREVIOUS -> views.showPrevious(R.id.word_reminder_flipper)
            ACTION_NEXT -> views.showNext(R.id.word_reminder_flipper)
            else -> return
        }
        val manager = getAppWidgetManager(context) ?: return
        manager.updateAppWidget(widgetIds(context, manager), views)
    }

    companion object {
        const val ACTION_PREVIOUS = "com.ichi2.widget.WordReminderWidget.PREVIOUS"
        const val ACTION_NEXT = "com.ichi2.widget.WordReminderWidget.NEXT"

        /** Shows the current words in all the widgets. Returns whether there is one */
        fun updateAll(context: Context): Boolean {
            val manager = getAppWidgetManager(context) ?: return false
            val ids = widgetIds(context, manager)
            if (ids.isEmpty()) return false
            manager.updateAppWidget(ids, buildViews(context))
            return true
        }

        private fun widgetIds(
            context: Context,
            manager: AppWidgetManager,
        ) = manager.getAppWidgetIds(ComponentName(context, WordReminderWidget::class.java))

        fun buildViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_word_reminder)
            val words = WordReminderWorker.savedWords()
            val hasWords = words.isNotEmpty()
            views.setViewVisibility(R.id.word_reminder_empty, if (hasWords) View.GONE else View.VISIBLE)
            views.setViewVisibility(R.id.word_reminder_flipper, if (hasWords) View.VISIBLE else View.GONE)
            // before Android 12, reapplying the views goes back to the first slide
            val buttons = hasWords && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            for (id in listOf(R.id.word_reminder_previous, R.id.word_reminder_next)) {
                views.setViewVisibility(id, if (buttons) View.VISIBLE else View.GONE)
            }
            if (!hasWords) return views

            // launchers reapply the views to the shown widget: reuse its slides, so it keeps the current one
            views.removeAllViews(R.id.word_reminder_flipper)
            words.forEachIndexed { i, (did, title, body) ->
                // one request code per word: intents differing only by their extras share a PendingIntent
                val review =
                    PendingIntentCompat.getActivity(
                        context,
                        i,
                        IntentHandler.getReviewDeckIntent(context, did),
                        PendingIntent.FLAG_UPDATE_CURRENT,
                        false,
                    )
                val item = RemoteViews(context.packageName, R.layout.widget_word_reminder_item)
                item.setTextViewText(R.id.word_reminder_title, title)
                item.setTextViewText(R.id.word_reminder_body, body)
                item.setTextViewText(R.id.word_reminder_counter, "${i + 1}/${words.size}")
                item.setOnClickPendingIntent(R.id.word_reminder_item, review)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    views.addStableView(R.id.word_reminder_flipper, item, i)
                } else {
                    views.addView(R.id.word_reminder_flipper, item)
                }
            }
            views.setInt(R.id.word_reminder_flipper, "setFlipInterval", Prefs.wordReminderSlideSeconds * 1000)
            views.setOnClickPendingIntent(R.id.word_reminder_previous, broadcast(context, ACTION_PREVIOUS))
            views.setOnClickPendingIntent(R.id.word_reminder_next, broadcast(context, ACTION_NEXT))
            return views
        }

        private fun broadcast(
            context: Context,
            action: String,
        ) = PendingIntentCompat.getBroadcast(
            context,
            0,
            Intent(context, WordReminderWidget::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )
    }
}
