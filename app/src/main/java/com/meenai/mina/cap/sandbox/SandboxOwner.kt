package com.meenai.mina.cap.sandbox

import android.content.Context
import com.meenai.mina.core.workspace.SandboxTree

/** The state of the sandbox in the process and the tree lock: given by the App, the settings page, installation, and Agent see the same copy. */
interface SandboxOwner {
    val sandboxState: SandboxState
    val sandboxTree: SandboxTree

    companion object {
        fun of(context: Context): SandboxOwner = context.applicationContext as? SandboxOwner
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement SandboxOwner to give the sandbox's shared state")
    }
}
