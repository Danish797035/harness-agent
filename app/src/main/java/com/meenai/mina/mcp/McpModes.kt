package com.meenai.mina.mcp

import com.meenai.mina.settings.McpSettings
import com.meenai.mina.settings.McpUsbSettings
import com.meenai.mina.settings.RelaySettings

/**
 * Local monitoring shared by LAN and USB: If LAN is on, all network cards will be bound; if only USB is
 * on, only loopback will be bound. The remote gateway uses an independent outbound channel and does not
 * change the local monitoring range; the three switches each manage their own affairs, and the "close"
 * notification will be closed together. Do not touch the Android interface and test on the computer.
 */
object McpModes {
    /** The computer opens the USB mode (`am start-foreground-service -a`) via adb. */
    const val ACTION_USB_ON = "com.meenai.mina.mcp.USB_ON"
    /** Turn off the USB mode on the computer via adb. */
    const val ACTION_USB_OFF = "com.meenai.mina.mcp.USB_OFF"
    /** "Close" on the notification: Both local monitoring and remote channels are turned off. */
    const val ACTION_TURN_OFF = "com.meenai.mina.mcp.TURN_OFF"

    enum class Listen { Off, LoopbackOnly, PrivateNetworks }

    fun listen(network: Boolean, usb: Boolean): Listen = when {
        network -> Listen.PrivateNetworks
        usb -> Listen.LoopbackOnly
        else -> Listen.Off
    }

    /** Which switch is changed for the action received by the service; other actions (including no action) are not changed, only the current status of the switch is changed. */
    fun apply(action: String?, network: McpSettings, usb: McpUsbSettings, relay: RelaySettings? = null) {
        when (action) {
            ACTION_USB_ON -> usb.enable()
            ACTION_USB_OFF -> usb.disable()
            ACTION_TURN_OFF -> {
                try { network.setEnabled(false) }
                finally { try { usb.disable() } finally { relay?.setEnabled(false) } }
            }
        }
    }
}
