package com.meenai.mina.core.workspace

/** Known errors reported by the sandbox implementation to the tool layer; [code] becomes the tool result error (such as sandbox_network_isolation_unavailable) as it is. */
class SandboxError(val code: String, message: String, val recoverable: Boolean = false) : Exception(message)
