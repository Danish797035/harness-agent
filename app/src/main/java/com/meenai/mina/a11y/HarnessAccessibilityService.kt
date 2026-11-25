package com.meenai.mina.a11y

import com.meenai.mina.cap.phone.a11y.ScreenAccessibility

/** The system uses this full name to find the Harness Agent's accessibility service (it is stored in the list and system settings); it is implemented in the mobile phone capabilities. */
class HarnessAccessibilityService : ScreenAccessibility()

typealias MinaAccessibilityService = HarnessAccessibilityService
