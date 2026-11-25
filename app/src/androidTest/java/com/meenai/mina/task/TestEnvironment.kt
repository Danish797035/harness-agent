package com.meenai.mina.task

import androidx.test.platform.app.InstrumentationRegistry

/** For real machine testing: the task environment in the App process under test (changing the model source, evaluating the hook, and watching the screen). */
internal val testTasks: TaskEnvironment
    get() = TaskEnvironment.of(InstrumentationRegistry.getInstrumentation().targetContext)
