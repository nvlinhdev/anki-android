// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.widget

import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.IntentHandler
import com.ichi2.anki.R
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.notifications.NotificationId
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.worker.WordReminderWorker
import kotlinx.coroutines.launch
import java.util.Locale

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
        if (intent.action == ACTION_KNOWN) {
            val pendingResult = goAsync()
            applicationScope.launch {
                try {
                    markKnown(context, intent.getLongExtra(EXTRA_CARD_ID, 0))
                } finally {
                    pendingResult?.finish()
                }
            }
            return
        }
        if (intent.action == ACTION_NOTIFY_NEXT) {
            WordReminderWorker.notify(context, intent.getIntExtra(EXTRA_INDEX, 0))
            return
        }
        if (intent.action == ACTION_SPEAK) {
            speak(context, intent.getStringExtra(EXTRA_WORD).orEmpty(), goAsync())
            return
        }
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
        const val ACTION_KNOWN = "com.ichi2.widget.WordReminderWidget.KNOWN"
        const val EXTRA_CARD_ID = "cardId"
        const val ACTION_SPEAK = "com.ichi2.widget.WordReminderWidget.SPEAK"
        const val EXTRA_WORD = "word"
        const val ACTION_NOTIFY_NEXT = "com.ichi2.widget.WordReminderWidget.NOTIFY_NEXT"
        const val EXTRA_INDEX = "index"

        /** Reads [word] aloud with the system voice, then releases the engine and [pendingResult] */
        private fun speak(
            context: Context,
            word: String,
            pendingResult: PendingResult?,
        ) {
            lateinit var tts: TextToSpeech
            val done: () -> Unit = {
                tts.shutdown()
                pendingResult?.finish()
            }
            tts =
                TextToSpeech(context.applicationContext) { status ->
                    if (status != TextToSpeech.SUCCESS) return@TextToSpeech done()
                    // ponytail: English voice only, like the desktop add-on
                    tts.language = Locale.US
                    tts.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}

                            override fun onDone(utteranceId: String?) = done()

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) = done()
                        },
                    )
                    if (tts.speak(word, TextToSpeech.QUEUE_FLUSH, null, word) != TextToSpeech.SUCCESS) done()
                }
        }

        /** Suspends the card of a word the user knows, so it isn't reminded any more, and removes it from the list */
        suspend fun markKnown(
            context: Context,
            cardId: CardId,
        ) {
            withCol { sched.suspendCards(listOf(cardId)) }
            WordReminderWorker.removeWord(cardId)
            context.getSystemService<NotificationManager>()?.cancel(NotificationId.WORD_REMINDER)
            if (updateAll(context) && WordReminderWorker.savedWords().isEmpty()) {
                // the last word of the widget: pick a new list
                WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WordReminderWorker>().build())
            }
        }

        /** Marks the word of [cardId] as known; one request code per card, as the intents only differ by their extras */
        fun knownIntent(
            context: Context,
            cardId: CardId,
        ) = PendingIntentCompat.getBroadcast(
            context,
            cardId.toInt(),
            Intent(context, WordReminderWidget::class.java).setAction(ACTION_KNOWN).putExtra(EXTRA_CARD_ID, cardId),
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )

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
            words.forEachIndexed { i, word ->
                val (did, title, body) = word
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
                item.setOnClickPendingIntent(R.id.word_reminder_known, knownIntent(context, word.cardId))
                val speak = Intent(context, WordReminderWidget::class.java).setAction(ACTION_SPEAK)
                // the title is the word and its IPA
                speak.putExtra(EXTRA_WORD, title.substringBefore(" /"))
                item.setOnClickPendingIntent(
                    R.id.word_reminder_speak,
                    PendingIntentCompat.getBroadcast(context, i, speak, PendingIntent.FLAG_UPDATE_CURRENT, false),
                )
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
