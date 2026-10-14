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
 * A random card is picked from the due cards, else from the cards added in the last [RECENT_DAYS] days,
 * else from the whole deck. The last [AVOID_LAST] reminders are skipped unless nothing else is left.
 * The notification shows a [summarize]d meaning; the card keeps the whole article.
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
        val recent =
            Prefs.wordReminderRecentCardIds
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                .orEmpty()
        val reminder =
            withCol {
                val did = decks.idForName(deckName) ?: return@withCol null
                val tiers = listOf("is:due", "added:$RECENT_DAYS", "").map { findCards("did:$did $it") }
                if (tiers.last().isEmpty()) return@withCol null
                val cids = tiers.firstNotNullOfOrNull { tier -> (tier - recent.toSet()).ifEmpty { null } } ?: tiers.last()
                val card = getCard(cids.random())
                Prefs.wordReminderRecentCardIds = (recent + card.id).takeLast(AVOID_LAST).joinToString(",")
                val word = stripHTMLAndSpecialFields(card.renderOutput(this).questionText).trim()
                did to summarize(word, card.pureAnswer(this))
            } ?: return Result.success()
        val (did, text) = reminder
        val (title, body) = text
        Timber.i("WordReminderWorker: showing a reminder")

        val intent = IntentHandler.getReviewDeckIntent(applicationContext, did)
        val notification =
            NotificationCompat
                .Builder(applicationContext, NotificationChannel.REVIEW_REMINDERS.id)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setSmallIcon(R.drawable.ic_star_notify)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(PendingIntentCompat.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false))
                .setAutoCancel(true)
                .build()
        applicationContext.getSystemService<NotificationManager>()?.notify(NotificationId.WORD_REMINDER, notification)
        return Result.success()
    }

    companion object {
        /** Words looked up in the last [RECENT_DAYS] days come right after the due cards */
        private const val RECENT_DAYS = 3

        /** Number of last reminders that are not repeated if another card is left */
        private const val AVOID_LAST = 5

        /** Dictionaries list the most common senses first */
        private const val SENSES_PER_POS = 2

        /** Parts of speech shown in a reminder */
        private const val MAX_POS = 3

        private const val LINE_MAX_CHARS = 100

        private val LINE_BREAK = Regex("""<br\s*/?>|</div>|</p>|\n""", RegexOption.IGNORE_CASE)

        // \s doesn't match the no-break spaces dictionaries put after "1."
        private val SENSE = Regex("""\d+\.[\s ]*(.+)""")
        private val USAGE_PATTERN = Regex("""^\([^)]*\)[\s ]*""")
        private val IPA = Regex("/[^/]+/")

        /**
         * Short reminder text for a dictionary article, without examples: (title, body).
         * The title is the word and its first IPA; the body has one line per part of speech
         * ("■ danh từ") with its first senses ("1. ..."). Articles without that structure
         * fall back to their first line that is not the word itself.
         * Same rules as `summarize` in the anki-word-reminder desktop add-on.
         */
        fun summarize(
            word: String,
            html: String,
        ): Pair<String, String> {
            val lines =
                html
                    .split(LINE_BREAK)
                    .map { stripHTMLAndSpecialFields(it).replace("‍", "").trim() } // zero-width joiners in IPA
                    .filter { it.isNotEmpty() }
            var ipa: String? = null
            val sections = mutableListOf<Pair<String, MutableList<String>>>() // (part of speech, senses)
            for (line in lines) {
                val sense = SENSE.matchEntire(line)?.groupValues?.get(1)
                when {
                    line.startsWith("■") -> sections += line.drop(1).substringBefore("—").trim() to mutableListOf()
                    sense != null -> {
                        if (sections.isEmpty()) sections += "" to mutableListOf()
                        val senses = sections.last().second
                        // drop a leading usage pattern like "(to conflict with something)"
                        if (senses.size < SENSES_PER_POS) senses += sense.replace(USAGE_PATTERN, "")
                    }
                    sections.isEmpty() && ipa == null -> ipa = IPA.find(line)?.value
                }
            }
            val body =
                sections
                    .filter { it.second.isNotEmpty() }
                    .take(MAX_POS)
                    .map { (pos, senses) -> clip(if (pos.isEmpty()) senses.joinToString(" · ") else "$pos: ${senses.joinToString(" · ")}") }
                    .ifEmpty { lines.filterNot { it.equals(word, ignoreCase = true) }.take(1).map(::clip) }
            return (if (ipa != null) "$word $ipa" else word) to body.joinToString("\n")
        }

        private fun clip(text: String) = if (text.length <= LINE_MAX_CHARS) text else text.take(LINE_MAX_CHARS - 1).trimEnd() + "…"

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
