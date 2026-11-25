package com.meenai.mina.cap.sandbox

import com.meenai.mina.core.workspace.NioFdSys
import com.meenai.mina.core.workspace.WorkspaceFileGate

/** The pass in the JVM test (according to the approximate path; on the mobile phone it is AndroidFdSys.gate). */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
