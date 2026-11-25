package com.meenai.mina.core.tool

/**
 * Credentials for executing a tool that will change the outside world: [ToolDispatcher.execute] wants
 * it. The constructor is internal and cannot be created by the App module; only the execution entry
 * (core.action.ActionExecutor) in the core creates it, guarded by SingleEntryTest. The read-only call
 * goes to [ToolDispatcher.executeRead].
 */
class ActionPass internal constructor()
