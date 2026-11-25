package com.meenai.mina.task

import android.content.Context
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.model.ModelSession
import com.meenai.mina.settings.ProviderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * The task is obtained from the App: where the model comes from, the evaluation hook, and whether the
 * task being displayed has a screen image at the moment. A copy of the entire process is given by the
 * App; test and replace the model sources and hooks inside.
 */
class TaskEnvironment {
    /** The virtual screen number of the task being displayed; it is -1 when there is no task, or the screen has not been created (using system commands, event tools, or when the app is opened for the first time). */
    val agentDisplayId = MutableStateFlow(-1)
    /** The task being displayed has already opened an app on the virtual screen (it does not count if the screen is built: the screen is empty when only running system commands). */
    val agentAppShown = MutableStateFlow(false)
    /** The task being displayed is using the built-in browser (picture-in-picture displays web pages). */
    val webScreenOpen = MutableStateFlow(false)
    /** There is a picture to watch in picture-in-picture: an app is open on the virtual screen, or the built-in browser is being used. Tasks that only create a blank screen are not counted. */
    val previewOpen: Flow<Boolean> = combine(agentAppShown, webScreenOpen) { app, web -> app || web }

    // The ModelSession of the three factories is the conversation to which this call belongs
    // (ModelSession.of (conversation id)): the models built in the same conversation are all given to
    // the same one. If the service provider wants the session number, it can be routed and reused in the
    // cache.

    /** The main model comes from the model configuration in the settings; the test can be replaced by a script model. */
    @Volatile var modelFactory: (Context, ModelSession) -> ChatModel? = defaultModelFactory
    /** Lightweight model (lists success criteria, etc.); when empty, the main model takes over. When testing the main model, replace it together to avoid having to adjust to the real model in the settings. */
    @Volatile var lightModelFactory: (Context, ModelSession) -> ChatModel? = defaultLightModelFactory
    /** Visual model (see screenshot when checking); it is empty when there is no model that can be viewed in the settings. */
    @Volatile var visionModelFactory: (Context, ModelSession) -> ChatModel? = defaultVisionModelFactory

    /** Hook used to evaluate running machines; all products have default values. */
    val evalHooks = EvalHooks()

    /** Give the application in the process. */
    interface Owner {
        val tasks: TaskEnvironment
    }

    companion object {
        val defaultModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Main, s) }
        val defaultLightModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Light, s) }
        val defaultVisionModelFactory: (Context, ModelSession) -> ChatModel? = { c, s -> ProviderStore.get(c).modelFor(ModelRole.Vision, s) }

        fun of(context: Context): TaskEnvironment = (context.applicationContext as Owner).tasks
    }
}
