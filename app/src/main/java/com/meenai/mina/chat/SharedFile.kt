package com.meenai.mina.chat

/** A file exported to "Download/Mina" is displayed as a file card in the dialog. [uri] is the content URI of MediaStore, used to open it. */
data class SharedFile(val name: String, val size: Long, val savedAs: String, val uri: String)
