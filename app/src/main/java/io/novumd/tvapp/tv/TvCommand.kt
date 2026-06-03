package io.novumd.tvapp.tv

enum class TvCommand(
    val displayName: String,
    private val commandId: Int,
) {
    Power("Power", 0x01),
    VolumeUp("Volume Up", 0x02),
    VolumeDown("Volume Down", 0x03),
    Mute("Mute", 0x04),
    InputSwitch("Input Switch", 0x05),
    ;

    fun payload(): ByteArray = byteArrayOf(commandId.toByte())
}
