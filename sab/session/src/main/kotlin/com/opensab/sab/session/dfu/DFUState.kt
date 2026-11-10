package com.opensab.sab.session.dfu

internal enum class DFUCommand(val code: Int) {
    START_DFU(0x01),
    ABORT_DFU(0x02),
    APPLY_FIRMWARE(0x03)
}

internal sealed interface DFUState {

    data object Idle : DFUState

    data class Preparing(val imageSizeBytes: Int) : DFUState

    data class Transferring(
        val progressPercent: Int,
        val bytesSent: Int,
        val totalBytes: Int
    ) : DFUState

    data object Validating : DFUState

    data object Applying : DFUState

    data object Completed : DFUState

    data class Failed(val cause: Throwable) : DFUState
}
