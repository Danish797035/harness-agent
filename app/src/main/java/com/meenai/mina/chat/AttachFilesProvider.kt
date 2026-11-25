package com.meenai.mina.chat

import android.content.ContentValues
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * The content:// address used in the dialogue page attachment (see res/xml/attach_files.xml) is not
 * exported to the outside world and is only temporarily authorized when used:
 * - - camera/: Photos taken by the system camera are written here (authorize the camera app to write
 * this file when taking a photo), and then copy it into the conversation workspace when sending;
 * - - viewing/: A copy is copied from the workspace when clicking on the sent photo or file, and is
 * handed to the viewing page in the App (or other App selected by the user) to open for read-only.
 * The workspace itself is not open. Except for camera/, it is read-only; no deletion or insertion is
 * allowed.
 */
class AttachFilesProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r" && uri.pathSegments.firstOrNull() != CAMERA) throw SecurityException("read-only")
        return super.openFile(uri, mode)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        /** Same as the name in attach_files.xml: the first segment of the address. */
        const val CAMERA = "camera"

        fun authority(packageName: String) = "$packageName.attachfiles"
    }
}
