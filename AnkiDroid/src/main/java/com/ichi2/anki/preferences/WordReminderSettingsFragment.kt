// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.settings.Prefs
import com.ichi2.anki.worker.WordReminderWorker
import com.ichi2.utils.Permissions
import com.ichi2.widget.WordReminderWidget
import kotlin.reflect.KMutableProperty0

/**
 * Settings of [WordReminderWorker]: periodic notifications showing a word from some decks.
 */
class WordReminderSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_word_reminder
    override val analyticsScreenNameConstant: String
        get() = "prefs.wordReminder"

    override fun initSubscreen() {
        requirePreference<MultiSelectListPreference>(R.string.pref_word_reminder_decks_key).apply {
            // also saves the deck of the single-deck setting, if any
            values = Prefs.wordReminderDecks
            entries = values.sorted().toTypedArray() // until the decks are loaded
            entryValues = entries
            setSummaryProvider { values.sorted().joinToString(", ") }
            launchCatchingTask {
                val names = withCol { decks.allNamesAndIds(skipEmptyDefault = true, includeFiltered = false) }.map { it.name }
                entries = names.toTypedArray()
                entryValues = entries
            }
        }
        listOf(
            R.string.pref_word_reminder_enabled_key,
            R.string.pref_word_reminder_decks_key,
            R.string.pref_word_reminder_interval_key,
            R.string.pref_word_reminder_list_size_key,
            R.string.pref_word_reminder_slide_seconds_key,
        ).forEach { key ->
            requirePreference<Preference>(key).setOnPreferenceChangeListener { _, _ ->
                // the listener runs before the value is saved
                view?.post {
                    WordReminderWorker.schedule(requireContext())
                    WordReminderWidget.updateAll(requireContext())
                }
                true
            }
        }
        quietTime(R.string.pref_word_reminder_quiet_start_key, Prefs::wordReminderQuietStartHour, Prefs::wordReminderQuietStartMinute)
        quietTime(R.string.pref_word_reminder_quiet_end_key, Prefs::wordReminderQuietEndHour, Prefs::wordReminderQuietEndMinute)
        requirePreference<Preference>(R.string.pref_word_reminder_enabled_key).setOnPreferenceClickListener {
            Permissions.showNotificationsPermissionBottomSheetIfNeeded(requireActivity(), parentFragmentManager) {}
            false
        }
    }

    /** A start or end of the quiet hours, picked with a clock. The worker reads it on each run: nothing to reschedule */
    private fun quietTime(
        key: Int,
        hour: KMutableProperty0<Int>,
        minute: KMutableProperty0<Int>,
    ) = requirePreference<Preference>(key).apply {
        val showSummary = { summary = "${hour.get()}:${minute.get().toString().padStart(2, '0')}" }
        showSummary()
        setOnPreferenceClickListener {
            // ponytail: the picker loses its listener on rotation (see AddEditReminderDialog.onResume); pick again
            val picker =
                MaterialTimePicker
                    .Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setHour(hour.get())
                    .setMinute(minute.get())
                    .setTitleText(title)
                    .build()
            picker.addOnPositiveButtonClickListener {
                hour.set(picker.hour)
                minute.set(picker.minute)
                showSummary()
            }
            picker.show(parentFragmentManager, "wordReminderQuietTime")
            true
        }
    }
}
