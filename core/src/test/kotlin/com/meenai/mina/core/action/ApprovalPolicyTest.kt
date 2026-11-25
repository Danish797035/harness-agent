package com.meenai.mina.core.action

import com.meenai.mina.core.run.Attendance
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.run.RunProvenance
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalPolicyTest {
    @Test fun onlyPaymentAsks() {
        for (level in ActionRisk.entries) {
            assertEquals("$level", Approval.Allow, ApprovalPolicy.decide(Risk(level)))
            assertEquals("$level payment", Approval.Ask, ApprovalPolicy.decide(Risk(level, payment = true)))
        }
    }

    @Test fun everyOriginAndAttendance() {
        for (origin in RunOrigin.entries) for (attendance in Attendance.entries) for (level in ActionRisk.entries) {
            val run = RunProvenance(origin, attendance)
            // It costs nothing: whoever initiates it does it directly regardless of whether the person
            // is present or not.
            assertEquals("$origin $attendance $level", Approval.Allow, ApprovalPolicy.decide(Risk(level), run))
            // Those that cost money: can only be initiated on the mobile phone and asked when someone is
            // present; do not do things that cannot be asked (channels, timing, MCP, sub-tasks,
            // unattended)
            val expected = if (origin == RunOrigin.Ui && attendance != Attendance.Unattended) Approval.Ask else Approval.Deny
            assertEquals("$origin $attendance $level payment", expected, ApprovalPolicy.decide(Risk(level, payment = true), run))
        }
    }

    @Test fun alwaysAskToolsNeedThePersonOnThePhone() {
        for (level in ActionRisk.entries) {
            assertEquals("$level", Approval.Ask, ApprovalPolicy.decide(Risk(level, alwaysAsk = true)))
            for (origin in RunOrigin.entries) for (attendance in Attendance.entries) {
                val run = RunProvenance(origin, attendance)
                val expected = if (origin == RunOrigin.Ui && attendance != Attendance.Unattended) Approval.Ask else Approval.Deny
                assertEquals("$origin $attendance $level", expected, ApprovalPolicy.decide(Risk(level, alwaysAsk = true), run))
            }
        }
    }
}
