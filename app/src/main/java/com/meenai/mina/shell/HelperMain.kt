package com.meenai.mina.shell

import com.meenai.mina.cap.phone.shell.HelperProcess

/** The entrance to the built-in assistant process: the full name is written in the startup command (see HelperHandoff.HELPER_CLASS); it is implemented in the mobile phone capabilities. */
object HelperMain {
    @JvmStatic
    fun main(args: Array<String>) = HelperProcess.main(args)
}
