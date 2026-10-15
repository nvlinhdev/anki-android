// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.IntentHandler
import com.ichi2.anki.NotificationChannel
import com.ichi2.anki.R
import com.ichi2.anki.backend.stripHTMLAndSpecialFields
import com.ichi2.anki.common.permissions.canPostNotifications
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.libanki.DeckId
import com.ichi2.anki.libanki.SortOrder
import com.ichi2.anki.notifications.NotificationId
import com.ichi2.anki.provider.pureAnswer
import com.ichi2.anki.settings.Prefs
import com.ichi2.widget.WordReminderWidget
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Every [Prefs.wordReminderIntervalMinutes] picks [Prefs.wordReminderListSize] words from [Prefs.wordReminderDecks]
 * for the [WordReminderWidget], and shows the first one as a notification, outside the quiet hours,
 * if there is no widget (or [Prefs.wordReminderNotifyWithWidget]).
 * Random cards are picked from the due cards, then from the cards added in the last [RECENT_DAYS] days,
 * then from the whole deck. Cards of the previous list come last, the first one at the end.
 * Words show a [summarize]d meaning; the card keeps the whole article.
 */
class WordReminderWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!Prefs.wordReminderEnabled) return Result.success()
        // first, so that the chain goes on whatever happens next
        if (CHAIN_TAG in tags) enqueueRun(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        val deckNames = Prefs.wordReminderDecks
        val previous =
            Prefs.wordReminderRecentCardIds
                ?.split(",")
                ?.mapNotNull { it.toLongOrNull() }
                .orEmpty()
        val words =
            withCol {
                val dids = deckNames.mapNotNull { decks.idForName(it) }
                if (dids.isEmpty()) return@withCol emptyList()
                // suspended cards are the words not to remind
                val inDecks = dids.joinToString(" OR ", "(", ")") { "did:$it" } + " -is:suspended"
                // due cards most likely forgotten first (lowest FSRS retrievability), the others at random
                val byRetrievability = SortOrder.BuiltinColumnSortKind(getBrowserColumn("retrievability")!!, reverse = false)
                val tiers =
                    listOf("is:due" to byRetrievability, "added:$RECENT_DAYS" to SortOrder.NoOrdering, "" to SortOrder.NoOrdering)
                        .map { (search, order) -> findCards("$inDecks $search", order) }
                val size = Prefs.wordReminderListSize
                val picked = mutableListOf<CardId>()
                tiers.forEachIndexed { i, tier ->
                    val pool = tier.filter { it !in previous && it !in picked }
                    picked += (if (i == 0) pool else pool.shuffled()).take(size - picked.size)
                }
                // then the previous list, in turn: a deck smaller than the list must not keep showing the same word first
                picked += (previous.drop(1) + previous.take(1)).filter { it in tiers.last() }.take(size - picked.size)
                picked.map { cid ->
                    val card = getCard(cid)
                    val word = stripHTMLAndSpecialFields(card.renderOutput(this).questionText).trim()
                    val (title, body) = summarize(word, card.pureAnswer(this))
                    cid to SavedWord(card.currentDeckId(), title, body, cid)
                }
            }
        if (words.isEmpty()) return Result.success()
        Prefs.wordReminderRecentCardIds = words.joinToString(",") { it.first.toString() }
        saveWords(words.map { it.second })
        // the widget already shows the words
        if (WordReminderWidget.updateAll(applicationContext) && !Prefs.wordReminderNotifyWithWidget) return Result.success()

        val now = TimeManager.time.calendar().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val quietStart = Prefs.wordReminderQuietStartHour * 60 + Prefs.wordReminderQuietStartMinute
        val quietEnd = Prefs.wordReminderQuietEndHour * 60 + Prefs.wordReminderQuietEndMinute
        if (inQuietHours(now, quietStart, quietEnd) ||
            !canPostNotifications(applicationContext)
        ) {
            return Result.success()
        }
        Timber.i("WordReminderWorker: showing a reminder")
        notify(applicationContext, 0)
        return Result.success()
    }

    companion object {
        /** Words looked up in the last [RECENT_DAYS] days come right after the due cards */
        private const val RECENT_DAYS = 3

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

        /** The current list, empty before the first run */
        fun savedWords(): List<SavedWord> {
            val json = JSONObject(Prefs.wordReminderWidgetWords ?: return emptyList())
            val words = json.getJSONArray("words")
            return (0 until words.length()).map { i ->
                // lists saved before several decks could be picked have one "deckId" for all words
                words.getJSONArray(i).let {
                    SavedWord(it.optLong(2, json.optLong("deckId")), it.getString(0), it.getString(1), it.optLong(3))
                }
            }
        }

        /** Shows the word at [index] of the current list (wrapping around) as a notification */
        fun notify(
            context: Context,
            index: Int,
        ) {
            val words = savedWords()
            if (words.isEmpty()) return
            val (did, title, body, cardId) = words[index % words.size]
            val intent = IntentHandler.getReviewDeckIntent(context, did)
            val builder =
                NotificationCompat
                    .Builder(context, NotificationChannel.REVIEW_REMINDERS.id)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setSmallIcon(R.drawable.ic_star_notify)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setContentIntent(PendingIntentCompat.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false))
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true) // "Next word" replaces it silently
                    .addAction(
                        R.drawable.ic_done,
                        context.getString(R.string.word_reminder_remembered),
                        WordReminderWidget.rememberedIntent(context, cardId),
                    )
            if (words.size > 1) {
                val next =
                    Intent(context, WordReminderWidget::class.java)
                        .setAction(WordReminderWidget.ACTION_NOTIFY_NEXT)
                        .putExtra(WordReminderWidget.EXTRA_INDEX, index + 1)
                builder.addAction(
                    R.drawable.ic_baseline_chevron_right_24,
                    context.getString(R.string.word_reminder_widget_next),
                    PendingIntentCompat.getBroadcast(context, 0, next, PendingIntent.FLAG_UPDATE_CURRENT, false),
                )
            }
            context.getSystemService<NotificationManager>()?.notify(NotificationId.WORD_REMINDER, builder.build())
        }

        /** Removes the word of [cardId] from the current list */
        fun removeWord(cardId: CardId) = saveWords(savedWords().filter { it.cardId != cardId })

        private fun saveWords(words: List<SavedWord>) {
            Prefs.wordReminderWidgetWords =
                JSONObject()
                    .put("words", JSONArray(words.map { JSONArray(listOf(it.title, it.body, it.deckId, it.cardId)) }))
                    .toString()
        }

        /** Whether [time] is in [start, end), in the same unit (hours, minutes of the day); the range may cross midnight. `start == end` disables it. */
        fun inQuietHours(
            time: Int,
            start: Int,
            end: Int,
        ) = when {
            start == end -> false
            start < end -> time in start until end
            else -> time >= start || time < end
        }

        /** Tag of the runs of the reminder chain, each one enqueues the next */
        const val CHAIN_TAG = "wordReminderChain"

        /**
         * Applies the current preferences: (re)starts the chain of reminders, or cancels it.
         * Also replaces the periodic work of older versions.
         */
        fun schedule(context: Context) {
            if (!Prefs.wordReminderEnabled) {
                WorkManager.getInstance(context).cancelUniqueWork(UniqueWorkNames.WORD_REMINDER)
                return
            }
            enqueueRun(context, ExistingWorkPolicy.REPLACE)
        }

        /**
         * Runs the worker in [Prefs.wordReminderIntervalMinutes]: a chain of one-time works, as periodic work
         * can't run more often than every 15 minutes. From a run of the chain, [ExistingWorkPolicy.APPEND_OR_REPLACE]
         * waits for it to end, rather than cancelling it
         */
        private fun enqueueRun(
            context: Context,
            policy: ExistingWorkPolicy,
        ) {
            val request =
                OneTimeWorkRequestBuilder<WordReminderWorker>()
                    .setInitialDelay(Prefs.wordReminderIntervalMinutes.toLong(), TimeUnit.MINUTES)
                    .addTag(CHAIN_TAG)
                    .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UniqueWorkNames.WORD_REMINDER, policy, request)
        }
    }
}

/** A word of the current list: the deck to review when it's tapped, and its [WordReminderWorker.summarize]d text */
data class SavedWord(
    val deckId: DeckId,
    val title: String,
    val body: String,
    /** 0 in lists saved before words could be marked as remembered */
    val cardId: CardId = 0,
)
