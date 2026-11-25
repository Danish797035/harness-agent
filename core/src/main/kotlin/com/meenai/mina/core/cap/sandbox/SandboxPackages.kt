package com.meenai.mina.core.cap.sandbox

/**
 * Packages installed in the running environment: Python and Node, plus those required by `apk:` in the
 * skill script list (py3-pypdf: PDF extraction; py3-pillow currently has no built-in skills to use, so
 * the package itself is reserved for now), and the tzdata required to follow the mobile phone time zone.
 * They are all in the main/community of Alpine 3.22, and the built-in running environment is loaded when
 * building (the WANT of tools/sandbox/build-runtime.py is consistent with this, check with
 * BundledRuntimeTest).
 */
val SANDBOX_PACKAGES: List<String> = listOf("python3", "nodejs", "py3-pillow", "py3-pypdf", "tzdata")
