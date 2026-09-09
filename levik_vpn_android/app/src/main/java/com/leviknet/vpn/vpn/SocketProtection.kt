package com.leviknet.vpn.vpn

/** Network pinning is best-effort only for servers that allow any physical network. */
internal fun protectTunnelSocket(
    fd: Long,
    requireNetworkBinding: Boolean,
    protect: (Int) -> Boolean,
    bind: (Int) -> Unit,
    onBindingFailure: (Exception) -> Unit,
): Boolean {
    if (fd !in 0..Int.MAX_VALUE.toLong()) return false
    val socketFd = fd.toInt()
    return try {
        if (!protect(socketFd)) return false
        try {
            bind(socketFd)
            true
        } catch (error: Exception) {
            onBindingFailure(error)
            // A connected UDP socket can reject bindSocket after protect succeeded.
            // Reassert protection before allowing the OS to select the physical network.
            !requireNetworkBinding && protect(socketFd)
        }
    } catch (_: Exception) {
        false
    }
}
