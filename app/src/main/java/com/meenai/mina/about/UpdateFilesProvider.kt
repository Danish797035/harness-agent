package com.meenai.mina.about

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * When using the network proxy, the installation package downloaded by Mina itself is given to the
 * content:// address used by the system installer (the address under the system download service is
 * given by itself). Only the update package directory (see res/xml/update_files.xml) is covered,
 * read-only, not exported, and temporarily authorized during installation.
 */
class UpdateFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
