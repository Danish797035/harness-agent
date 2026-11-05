package com.meenai.mina.about

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import java.net.URLEncoder

/** All of Harness Agent's public links live here: repo, issues, and update-check sources. Change addresses in this one place. */
object HarnessLinks {
    const val REPO = "https://github.com/Danish797035/harness-agent"
    const val REPO_LABEL = "github.com/Danish797035/harness-agent"
    const val README = "$REPO#readme"
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"

    /** Update checks only ask the GitHub releases page (no sign-in, hourly rate limit). */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/Danish797035/harness-agent/releases/latest"

    /** Device and version info pre-filled into the issue template for feedback. */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** Pre-filled issue body: device model, Android version, and Harness version on three lines, blank paragraphs below for the user. */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** New-issue URL with an empty title and the pre-filled body; parameters are URL-encoded (spaces become %20). */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
