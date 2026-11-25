package com.meenai.mina.cap.phone.shell

import android.content.Intent

/** Text sharing: Android ACTION_SEND, type text/plain, text is placed in EXTRA_TEXT, and is only delivered to one package. */
object ShareIntent {
    const val TYPE = "text/plain"

    fun of(packageName: String, text: String): Intent = Intent(Intent.ACTION_SEND).setType(TYPE).putExtra(Intent.EXTRA_TEXT, text)
        .setPackage(packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The parameter of `am start` when the system interface cannot be used (without shell
     * interpretation, the text will be used as a parameter as it is). [component] is the short name of
     * this package when there is only one sharing page, and is specified directly; when there are
     * several packages, only the package is limited, and the system asks which one to use.
     */
    fun amArgs(displayId: Int, packageName: String, text: String, component: String?): List<String> =
        listOf("am", "start", "--display", "$displayId", "-a", Intent.ACTION_SEND, "-t", TYPE, "--es", Intent.EXTRA_TEXT, text) +
            (component?.let { listOf("-n", it) } ?: listOf("-p", packageName))
}
