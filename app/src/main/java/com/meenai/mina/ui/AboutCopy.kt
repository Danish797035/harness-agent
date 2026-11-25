package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.uiLocale
import com.meenai.mina.about.CheckStatus
import com.meenai.mina.about.HarnessLinks
import com.meenai.mina.about.UpdateState

/** How to display the text on the about page, the "Check for Updates" line, the "About Mina" line on the setting homepage, and the prompts on the sidebar entrance. */
object AboutCopy {
    val TITLE: String get() = tr(R.string.settings_about)
    val TAGLINE: String get() = tr(R.string.about_tagline)
    const val BETA = "Beta"

    val CHECK: String get() = tr(R.string.about_check)
    val CHECK_SUB: String get() = tr(R.string.about_check_sub)
    val CHECK_SUB_NEW: String get() = tr(R.string.about_check_sub_new)
    val UP_TO_DATE: String get() = tr(R.string.about_up_to_date)
    val UP_TO_DATE_TOAST: String get() = tr(R.string.about_up_to_date_toast)
    val OFFLINE: String get() = tr(R.string.about_offline)
    val OFFLINE_TOAST: String get() = tr(R.string.about_offline_toast)
    val CHANGELOG: String get() = tr(R.string.about_changelog)
    val CHANGELOG_SUB: String get() = tr(R.string.about_changelog_sub)
    val CHANGELOG_EMPTY: String get() = tr(R.string.about_changelog_empty)
    val CHANGELOG_ALL: String get() = tr(R.string.about_changelog_all)

    val FEEDBACK: String get() = tr(R.string.about_feedback)
    val FEEDBACK_SUB: String get() = tr(R.string.about_feedback_sub)
    val FEEDBACK_SHEET_SUB: String get() = tr(R.string.about_feedback_sheet_sub)
    val FEEDBACK_GITHUB: String get() = tr(R.string.about_feedback_github)
    val FEEDBACK_GITHUB_SUB: String get() = tr(R.string.about_feedback_github_sub)

    val DIAGNOSTICS: String get() = tr(R.string.diagnostics_copy)
    val DIAGNOSTICS_SUB: String get() = tr(R.string.diagnostics_copy_sub)
    val DIAGNOSTICS_TITLE: String get() = tr(R.string.diagnostics_title)
    val DIAGNOSTICS_NOTE: String get() = tr(R.string.diagnostics_note)
    val DIAGNOSTICS_COPY: String get() = tr(R.string.diagnostics_copy_button)
    val DIAGNOSTICS_SHARE: String get() = tr(R.string.diagnostics_share)
    val DIAGNOSTICS_COPIED: String get() = tr(R.string.diagnostics_copied)
    val DIAGNOSTICS_LOADING: String get() = tr(R.string.diagnostics_loading)

    /** When the interface is in English, the communication group panel will put the X group chat first and then the WeChat group code; in the Chinese interface, WeChat will come first. Calculate based on the language actually displayed by the App (ui_locale in the resources), regardless of the system language. */
    val X_GROUP_FIRST: Boolean get() = uiLocale().language != "zh"

    val FOLLOW: String get() = tr(R.string.about_follow)
    val FOLLOW_X: String get() = tr(R.string.about_follow_x)
    val FOLLOW_X_SUB: String get() = ""
    val FOLLOW_SUB: String get() = ""
    val REPO: String get() = tr(R.string.about_repo)
    val REPO_SUB: String get() = tr(R.string.about_repo_sub, HarnessLinks.REPO_LABEL)
    val GUIDE: String get() = tr(R.string.about_guide)
    val GUIDE_SUB: String get() = tr(R.string.about_guide_sub)

    val DATA_NOTE: String get() = tr(R.string.about_data_note)
    val LICENSE_LINK: String get() = tr(R.string.about_license_link)
    val AUTHOR: String get() = tr(R.string.about_author)
    val LICENSE_TITLE: String get() = tr(R.string.about_license_title)

    val UPDATE_DOWNLOAD: String get() = tr(R.string.about_update_download)
    val UPDATE_LATER: String get() = tr(R.string.about_update_later)
    val NO_BROWSER: String get() = tr(R.string.about_no_browser)

    fun version(versionName: String): String = tr(R.string.about_version, versionName)

    fun buildLabel(build: String): String = tr(R.string.about_build, build)

    fun available(version: String): String = tr(R.string.about_available, version)
    fun updateTitle(version: String): String = tr(R.string.about_update_title, version)

    /** "Current 0.1.0 Beta · Released on 2026-11-05"; if you don't know the release date, just write the current version. */
    fun updateSub(current: String, beta: Boolean, date: String?): String =
        tr(R.string.about_current, current + if (beta) " $BETA" else "") + (date?.let { tr(R.string.about_released_on, it) } ?: "")

    /** On the right side of the "Check for Updates" line: Checking circles, if there is a new version, write the version number with a red dot, check once and write the result; if you have never checked, write nothing. */
    data class CheckValue(val text: String?, val dot: Boolean = false, val spinner: Boolean = false, val highlight: Boolean = false)

    fun checkValue(s: UpdateState): CheckValue = when {
        s.status == CheckStatus.Checking -> CheckValue(null, spinner = true)
        s.update != null -> CheckValue(available(s.update.version), dot = true, highlight = true)
        s.status == CheckStatus.Failed -> CheckValue(OFFLINE)
        s.latest != null -> CheckValue(UP_TO_DATE)
        else -> CheckValue(null)
    }

    /** Set the right side of the "About Mina" line on the homepage: If there is a new version, write "x can be updated" with a red dot, otherwise write the current version number. */
    fun settingsValue(updateVersion: String?, versionName: String): CheckValue =
        if (updateVersion != null) CheckValue(available(updateVersion), dot = true, highlight = true)
        else CheckValue(versionName.takeIf { it.isNotEmpty() })

    /** The red dot on the "About Mina" sidebar. */
    fun drawerDot(s: UpdateState): Boolean = s.hasUpdate
}
