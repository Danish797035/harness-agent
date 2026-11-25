package com.meenai.mina.core.run

/** Who initiates this run; completely separate from the Invoker of the individual actions. */
enum class RunOrigin { Ui, Channel, Trigger, Mcp, Child }

/** Whether the user can handle questions or confirmations in a timely manner during this run. */
enum class Attendance { Present, Reachable, Unattended }

data class RunProvenance(
    val origin: RunOrigin = RunOrigin.Ui,
    val attendance: Attendance = Attendance.Present,
)
