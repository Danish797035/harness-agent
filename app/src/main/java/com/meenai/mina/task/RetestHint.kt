package com.meenai.mina.task

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.settings.Provider

/**
 * When the task fails due to a model, but the main model has not passed the test at that company
 * ([untested], see [com.meenai.mina.settings.ProviderStore.untestedMainProvider]): Add a new line after
 * the reason to point to the "retest" of that company ([hint] gives this sentence with the name of the
 * service provider). All other endings are returned as is.
 */
internal object RetestHint {
    fun apply(end: TaskState, untested: Provider?, hint: (String) -> String): TaskState =
        if (end is TaskState.Failed && end.kind == FailKind.Model && untested != null) {
            end.copy(reason = end.reason + "\n" + hint(untested.name))
        } else end
}
