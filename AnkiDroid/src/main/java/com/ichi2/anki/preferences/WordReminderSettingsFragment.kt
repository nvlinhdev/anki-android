// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.preferences

import androidx.preference.Preference
import com.ichi2.anki.R
import com.ichi2.anki.worker.WordReminderWorker
import com.ichi2.utils.Permissions

/**
 * Settings of [WordReminderWorker]: periodic notifications showing a word from a deck.
 */
class WordReminderSettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_word_reminder
    override val analyticsScreenNameConstant: String
        get() = "prefs.wordReminder"

    override fun initSubscreen() {
        listOf(
            R.string.pref_word_reminder_enabled_key,
            R.string.pref_word_reminder_deck_key,
            R.string.pref_word_reminder_interval_key,
            R.string.pref_word_reminder_quiet_start_key,
            R.string.pref_word_reminder_quiet_end_key,
        ).forEach { key ->
            requirePreference<Preference>(key).setOnPreferenceChangeListener { _, _ ->
                // the listener runs before the value is saved
                view?.post { WordReminderWorker.schedule(requireContext()) }
                true
            }
        }
        requirePreference<Preference>(R.string.pref_word_reminder_enabled_key).setOnPreferenceClickListener {
            Permissions.showNotificationsPermissionBottomSheetIfNeeded(requireActivity(), parentFragmentManager) {}
            false
        }
    }
}
