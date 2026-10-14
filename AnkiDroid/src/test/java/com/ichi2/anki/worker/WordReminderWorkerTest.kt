// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.worker

import android.app.Notification
import android.app.NotificationManager
import androidx.core.content.getSystemService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.CardId
import com.ichi2.anki.settings.Prefs
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.empty
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.hasSize
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.util.Calendar

@RunWith(AndroidJUnit4::class)
class WordReminderWorkerTest : RobolectricTest() {
    @Test
    fun `shows a word from the deck`() =
        runTest {
            addWord("apple", "<b>quả</b> táo")
            Prefs.wordReminderEnabled = true
            setHour(12)

            assertThat(runWorker(), equalTo(Result.success()))

            val notification = notifications().single()
            assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(), equalTo("apple"))
            assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString(), containsString("quả táo"))
        }

    @Test
    fun `quiet hours suppress the reminder`() =
        runTest {
            addWord("apple", "quả táo")
            Prefs.wordReminderEnabled = true
            setHour(2) // default quiet hours: 23 -> 7

            runWorker()

            assertThat(notifications(), empty())
        }

    @Test
    fun `disabled reminder shows nothing`() =
        runTest {
            addWord("apple", "quả táo")
            setHour(12)

            runWorker()

            assertThat(notifications(), empty())
        }

    @Test
    fun `missing deck shows nothing`() =
        runTest {
            Prefs.wordReminderEnabled = true
            setHour(12)

            assertThat(runWorker(), equalTo(Result.success()))
            assertThat(notifications(), hasSize(0))
        }

    @Test
    fun `prefers recently added words and skips the last reminders`() =
        runTest {
            ageCard(addWord("old", "cũ"), days = 10)
            addWord("new", "mới")
            Prefs.wordReminderEnabled = true
            setHour(12)

            runWorker()
            assertThat(shownWord(), equalTo("new"))
            runWorker()
            assertThat(shownWord(), equalTo("old")) // "new" was just shown
            // only recently shown cards left: repeat one rather than show nothing
            assertThat(runWorker(), equalTo(Result.success()))
            assertThat(notifications(), hasSize(1))
        }

    @Test
    fun `quiet hours across midnight`() {
        assertThat(WordReminderWorker.inQuietHours(23, 23, 7), equalTo(true))
        assertThat(WordReminderWorker.inQuietHours(3, 23, 7), equalTo(true))
        assertThat(WordReminderWorker.inQuietHours(7, 23, 7), equalTo(false))
        assertThat(WordReminderWorker.inQuietHours(12, 23, 7), equalTo(false))
        assertThat(WordReminderWorker.inQuietHours(13, 12, 14), equalTo(true))
        assertThat(WordReminderWorker.inQuietHours(14, 12, 14), equalTo(false))
        assertThat(WordReminderWorker.inQuietHours(5, 5, 5), equalTo(false))
    }

    @Test
    fun `summarizes a dictionary article without examples`() {
        // Back field as GoldenDict stores it (innerText of the first article, "\n" -> "<br>")
        val conflict =
            "conflict<br>con·flict — [UK] /ˈ‍kɒnflɪkt/ [US] /ˈ‍kɑːnflɪkt/<br><br>CEFR: B2" +
                "<br>■ danh từ<br>1.  sự xung đột; cuộc xung đột" +
                "<br>‣  soldiers involved in armed conflict ↔ những người lính tham dự vào một cuộc xung đột vũ trang" +
                "<br>≈  CÙNG CHỦ ĐỀ<br>‣  aggression — arms — army — attack" +
                "<br>■ động từ — /kənˈflɪkt/<br>1.  (to conflict with something) đối lập; trái ngược; mâu thuẫn" +
                "<br>‣  their thoughts conflict with ours ↔ suy nghĩ của họ trái ngược với suy nghĩ của chúng tôi" +
                "<br>■ đồng nghĩa/liên quan<br>•  conflicted — conflicting — confliction" +
                "<br>■ nguồn gốc từ<br>•  late Middle English: from Latin conflict- ‘struck together, fought’"
        assertThat(
            WordReminderWorker.summarize("conflict", conflict),
            equalTo("conflict /ˈkɒnflɪkt/" to "danh từ: sự xung đột; cuộc xung đột\nđộng từ: đối lập; trái ngược; mâu thuẫn"),
        )
        // the first senses are the most common ones
        val restored =
            "restored<br>re·store — [UK] /rɪˈ‍stɔː(r)/<br>■ động từ<br>1.  hoàn lại, trả lại" +
                "<br>‣  to restore something to someone ↔ trả lại vật gì cho ai<br>2.  phục hồi lại<br>3.  phục (chức)"
        assertThat(
            WordReminderWorker.summarize("restored", restored),
            equalTo("restored /rɪˈstɔː(r)/" to "động từ: hoàn lại, trả lại · phục hồi lại"),
        )
        // other dictionaries: first line that is not the word itself
        assertThat(WordReminderWorker.summarize("apple", "<b>apple</b><br>quả táo<br>more"), equalTo("apple" to "quả táo"))
        assertThat(WordReminderWorker.summarize("x", ""), equalTo("x" to ""))
        val long = WordReminderWorker.summarize("w", "■ noun<br>1. " + "a".repeat(300)).second
        assertThat(long.length <= 100 && long.endsWith("…"), equalTo(true))
    }

    private fun addWord(
        word: String,
        meaning: String,
    ): CardId {
        val did = addDeck("GoldenDict")
        val cids = addBasicNote(word, meaning).cardIds(col)
        col.setDeck(cids, did)
        return cids.single()
    }

    /** Pretends the card was added [days] days ago (`added:` compares card ids). */
    private fun ageCard(
        cid: CardId,
        days: Int,
    ) = col.db.execute("update cards set id = ? where id = ?", cid - days * 86_400_000L, cid)

    private fun shownWord() =
        notifications()
            .single()
            .extras
            .getCharSequence(Notification.EXTRA_TITLE)
            .toString()

    private fun setHour(hour: Int) {
        val millis =
            MockTime(2024, 0, 1, 0, 0, 0, 0, 0)
                .calendar()
                .apply { set(Calendar.HOUR_OF_DAY, hour) }
                .timeInMillis
        TimeManager.resetWith(MockTime(initTime = millis, step = 0))
    }

    private suspend fun runWorker() = TestListenableWorkerBuilder<WordReminderWorker>(targetContext).build().doWork()

    private fun notifications() = shadowOf(targetContext.getSystemService<NotificationManager>()).allNotifications
}
