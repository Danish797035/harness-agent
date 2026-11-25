package com.meenai.mina.core.shell

import kotlinx.coroutines.flow.MutableStateFlow

/** Use when testing and not yet connected to the real implementation: write down the call, the status is set by the caller. [pairingResult] is the state after [startPairing]. */
class FakeBuiltinShellControl(
    initial: BuiltinShellStatus = BuiltinShellStatus.NotPaired,
    var pairingResult: BuiltinShellStatus = BuiltinShellStatus.Running,
    var retryResult: BuiltinShellStatus = BuiltinShellStatus.Running,
) : BuiltinShellControl {
    override val status = MutableStateFlow(initial)
    override val pairingDiscovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    val calls = mutableListOf<String>()

    override fun startDiscovery() {
        calls += "discover"
        if (pairingDiscovery.value == PairingDiscovery.Idle) pairingDiscovery.value = PairingDiscovery.Searching
    }

    override fun stopDiscovery() {
        calls += "stopDiscovery"
        pairingDiscovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        calls += "pair $code" + (port?.let { " $it" } ?: "")
        status.value = BuiltinShellStatus.Connecting
        status.value = pairingResult
    }

    override suspend fun retry() {
        calls += "retry"
        status.value = BuiltinShellStatus.Connecting
        status.value = retryResult
    }

    override suspend fun unpair() {
        calls += "unpair"
        status.value = BuiltinShellStatus.NotPaired
    }
}
