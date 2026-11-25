package com.meenai.mina.core.selfcheck

import java.net.URLEncoder
import java.util.Locale

/**
 * Write a self-inspection as a plain text report: the header is the version and equipment facts, and
 * each item below has one line "Number Name: Conclusion - Facts", in English key, can understand it.
 * Only write [DeviceFacts] and [CheckResult.detail] for each item, and do not write the account number,
 * App list, user content, and serial number; finally, the entire paragraph is given to [sanitize] (App
 * Here is the desensitization of "copying diagnostic information"), and the facts of each item are first
 * pressed into one line and truncated.
 */
object SelfCheckReport {
    const val HEADER = "Mina self-check"
    private const val MAX_DETAIL = 200

    fun render(state: SelfCheckState, sanitize: (String) -> String = { it }): String = sanitize(buildString {
        val f = state.facts
        appendLine("$HEADER ${f?.appVersion ?: "?"} (build ${f?.appBuild ?: "?"})")
        if (f != null) {
            appendLine("Device: ${f.modelName}, Android ${f.androidRelease} (SDK ${f.sdk}), ${f.abis.firstOrNull() ?: "unknown ABI"}, RAM ${gb(f.ramBytes)}, ${f.screenWidth}x${f.screenHeight}")
            appendLine("Wireless debugging: ${when (f.wirelessDebugging) { true -> "present"; false -> "missing"; null -> "unknown" }}")
            f.developerOptionsPage?.let { appendLine("Developer options page: ${if (it) "present" else "missing"}") }
            if (f.phoneControlUnavailable) appendLine("Phone control: unavailable in this environment")
        } else {
            appendLine("Device: unknown")
        }
        appendLine(summary(state))
        appendLine()
        for (r in state.results) appendLine(line(r))
    }.trimEnd() + "\n")

    /** One item per line: `8 stays alive: FAIL — frames stopped after 6 s`. */
    fun line(r: CheckResult): String {
        val detail = oneLine(r.detail)
        return "${r.check.number} ${r.check.key}: ${label(r.status)}" + if (detail.isEmpty()) "" else " — $detail"
    }

    fun label(s: CheckStatus): String = when (s) {
        CheckStatus.Pending -> "PENDING"
        CheckStatus.Running -> "RUNNING"
        CheckStatus.Pass -> "PASS"
        CheckStatus.Partial -> "PARTIAL"
        CheckStatus.Fail -> "FAIL"
        CheckStatus.Skipped -> "SKIPPED"
    }

    private fun summary(s: SelfCheckState): String {
        val n = s.results.groupingBy { it.status }.eachCount()
        fun c(st: CheckStatus) = n[st] ?: 0
        val counts = "${c(CheckStatus.Pass)} pass, ${c(CheckStatus.Partial)} partial, ${c(CheckStatus.Fail)} fail, ${c(CheckStatus.Skipped)} skipped"
        val secs = (s.elapsedMs + 500) / 1000
        return if (s.phase == SelfCheckState.Phase.Cancelled) "Result: cancelled after $secs s; $counts" else "Result: $counts in $secs s"
    }

    private fun oneLine(s: String): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= MAX_DETAIL) flat else flat.take(MAX_DETAIL - 1) + "…"
    }

    private fun gb(bytes: Long): String =
        if (bytes <= 0) "unknown" else String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))

    /** The title of the feedback question: `[Compatibility] Android <version> <model>`, the model number is the one given by the system (without adding the manufacturer name). */
    fun issueTitle(f: DeviceFacts?): String =
        if (f == null) "[兼容性]" else "[兼容性] Android ${f.androidRelease} ${f.model.trim()}".trim()

    /**
     * Create a new question address: Select the device feedback template and prefill only the title. The
     * report is too long and will not be put into the address (the address has an upper limit on length)
     * and will be pasted by the user. [newIssue] is the new problem address of the warehouse.
     */
    fun issueUrl(newIssue: String, f: DeviceFacts?): String =
        "$newIssue?template=$ISSUE_TEMPLATE&title=${encode(issueTitle(f))}"

    const val ISSUE_TEMPLATE = "device-report.yml"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
