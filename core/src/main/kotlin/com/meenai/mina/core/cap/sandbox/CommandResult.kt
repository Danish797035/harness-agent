package com.meenai.mina.core.cap.sandbox

/**
 * The result of a sandbox command. [output] is the merged standard output and standard error (up to 1
 * MB), more is discarded and included in [droppedBytes]; [killedBySystem]: killed by the system (mostly
 * due to insufficient memory); [network]: whether the network is connected when running.
 */
data class CommandResult(
    val exitCode: Int, val timedOut: Boolean, val killedBySystem: Boolean, val durationMs: Long,
    val output: String, val droppedBytes: Long, val network: Boolean,
)
