// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.IntentHandler
import com.ichi2.anki.NotificationChannel
import com.ichi2.anki.R
import com.ichi2.anki.backend.stripHTMLAndSpecialFields
import com.ichi2.anki.common.permissions.canPostNotifications
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.notifications.NotificationId
import com.ichi2.anki.provider.pureAnswer
import com.ichi2.anki.settings.Prefs
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Periodically shows a word from [Prefs.wordReminderDeck] as a notification, outside the quiet hours.
 * Due cards are preferred, otherwise a random card of the deck is shown.
 */
class WordReminderWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val hour = TimeManager.time.calendar().get(Calendar.HOUR_OF_DAY)
        if (!Prefs.wordReminderEnabled ||
            inQuietHours(hour, Prefs.wordReminderQuietStartHour, Prefs.wordReminderQuietEndHour) ||
            !canPostNotifications(applicationContext)
        ) {
            return Result.success()
        }
        val deckName = Prefs.wordReminderDeck ?: return Result.success()
        val reminder =
            withCol {
                val did = decks.idForName(deckName) ?: return@withCol null
                val cids = findCards("did:$did is:due").ifEmpty { findCards("did:$did") }
                if (cids.isEmpty()) return@withCol null
                val card = getCard(cids.random())
                Triple(
                    did,
                    stripHTMLAndSpecialFields(card.renderOutput(this).questionText).trim(),
                    stripHTMLAndSpecialFields(card.pureAnswer(this)).trim(),
                )
            } ?: return Result.success()
        val (did, word, meaning) = reminder
        Timber.i("WordReminderWorker: showing a reminder")

        val intent = IntentHandler.getReviewDeckIntent(applicationContext, did)
        val notification =
            NotificationCompat
                .Builder(applicationContext, NotificationChannel.REVIEW_REMINDERS.id)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setSmallIcon(R.drawable.ic_star_notify)
                .setContentTitle(word)
                .setContentText(meaning)
                .setStyle(NotificationCompat.BigTextStyle().bigText(meaning))
                .setContentIntent(PendingIntentCompat.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false))
                .setAutoCancel(true)
                .build()
        applicationContext.getSystemService<NotificationManager>()?.notify(NotificationId.WORD_REMINDER, notification)
        return Result.success()
    }

    companion object {
        /** Whether [hour] is in [start, end); the range may cross midnight. `start == end` disables it. */
        fun inQuietHours(
            hour: Int,
            start: Int,
            end: Int,
        ) = when {
            start == end -> false
            start < end -> hour in start until end
            else -> hour >= start || hour < end
        }

        /** Applies the current preferences: (re)schedules the periodic work, or cancels it. */
        fun schedule(context: Context) {
            val workManager = WorkManager.getInstance(context)
            if (!Prefs.wordReminderEnabled) {
                workManager.cancelUniqueWork(UniqueWorkNames.WORD_REMINDER)
                return
            }
            // 15 minutes is the minimum interval allowed by WorkManager
            val minutes = Prefs.wordReminderIntervalMinutes.coerceAtLeast(15).toLong()
            val request = PeriodicWorkRequestBuilder<WordReminderWorker>(minutes, TimeUnit.MINUTES).build()
            workManager.enqueueUniquePeriodicWork(UniqueWorkNames.WORD_REMINDER, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
