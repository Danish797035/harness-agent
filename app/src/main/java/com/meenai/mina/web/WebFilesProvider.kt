package com.meenai.mina.web

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * Pass the workspace file to the content:// address used in the web page file selection box. Read-only:
 * no writing, no deletion, no insertion is allowed; only the work/ directory of the sandbox is covered
 * (see res/xml/web_files.xml), and no external export is allowed.
 */
class WebFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
