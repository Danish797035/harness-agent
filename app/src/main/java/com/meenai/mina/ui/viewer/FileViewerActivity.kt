package com.meenai.mina.ui.viewer

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.AppLanguage
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.animationsOff
import com.meenai.mina.ui.theme.requestMaxRefreshRate
import com.meenai.mina.ui.theme.systemBarsFor

/**
 * File viewing page in the App: Web pages, Markdown, PDF, images and text files can be viewed directly
 * here without having to find another App. It is a self-contained Activity. In addition to the file
 * cards in the conversation, other places (suspended capsules and notifications) can also be opened with
 * [FileViewer.open].
 */
class FileViewerActivity : ComponentActivity() {
    // For Android 12 and before, the language selected in the App is applied; from Android 13 onwards,
    // the system can apply it by itself.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestMaxRefreshRate()
        val uri = intent.data
        if (uri == null) { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: uri.lastPathSegment.orEmpty()
        val kind = FileViewer.kindOf(this, uri, name)
        setContent {
            val dark = com.meenai.mina.ui.theme.appearanceDark()
            val reduceMotion = androidx.compose.runtime.remember { animationsOff(this@FileViewerActivity) }
            androidx.compose.runtime.LaunchedEffect(dark) { systemBarsFor(dark) }
            MinaTheme(dark = dark, reduceMotion = reduceMotion) {
                FileViewerScreen(uri, name, kind, onBack = ::finish)
            }
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
    }
}

/** The entrance to open the file: [FileViewerActivity] can be viewed in the App, and the rest is left to the App that can open it in the system. */
object FileViewer {
    fun kindOf(context: Context, uri: Uri, name: String): ViewerKind? =
        ViewerKinds.of(runCatching { context.contentResolver.getType(uri) }.getOrNull(), name)

    fun open(context: Context, uri: Uri, name: String) {
        if (kindOf(context, uri, name) == null) { openElsewhere(context, uri, name); return }
        val intent = Intent(context, FileViewerActivity::class.java).setData(uri).putExtra(FileViewerActivity.EXTRA_NAME, name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** Open with other applications: The system selection panel will prompt you when there is no app that can be opened. */
    fun openElsewhere(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(context, uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        start(context, Intent.createChooser(intent, name), tr(R.string.viewer_no_open_app))
    }

    /** Share: Send files to other apps (with read authorization). */
    fun share(context: Context, uri: Uri, name: String) {
        val intent = Intent(Intent.ACTION_SEND).setType(mimeOf(context, uri)).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        start(context, Intent.createChooser(intent, name), tr(R.string.viewer_no_share_app))
    }

    private fun mimeOf(context: Context, uri: Uri): String =
        runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: "*/*"

    private fun start(context: Context, chooser: Intent, failure: String) {
        try {
            context.startActivity(chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, failure, Toast.LENGTH_SHORT).show()
        }
    }
}
