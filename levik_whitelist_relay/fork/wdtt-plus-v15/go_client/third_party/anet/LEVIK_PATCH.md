# Levik patch to `github.com/wlynxg/anet` v0.0.5

The Go source in this directory is an exact copy of upstream tag `v0.0.5`,
commit `839bc3a920f1b87dd3ce1386e425aa5ef2e69d24`, except for one bounded Android
change in `interface_android.go`. The README files only add a Levik warning
against the upstream global linker-check bypass.

The dependency is reached through `github.com/pion/transport/v4/stdnet`, the
default network implementation used by the Pion TURN client when no custom
`vnet.Net` is supplied.

Upstream writes to the private variables `net.zoneCache` and
`golang.org/x/net/internal/socket.zoneCache` through unsupported
`//go:linkname` directives. Go 1.23 and newer correctly reject those references
unless all linkname checks are disabled globally. The Levik fork removes only
those private cache writes and their now-unused mirror type. It retains the
Android 11+ `RTM_GETADDR`/ioctl interface enumeration used by Pion.

Levik relay sessions use an explicitly IPv4 WireGuard/TURN data plane and do
not use scoped IPv6 addresses. Removing the private IPv6 zone-cache side
effect therefore does not remove a supported Levik capability. Do not extend
the relay to scoped IPv6 without first providing and testing a public-API zone
resolution design.

The Android build keeps the linker's default linkname validation enabled;
`scripts/build-android-client.sh` rejects any reintroduced `//go:linkname` in
this module.

Upstream module checksum:

`h1:J3VJGi1gvo0JwZ/P1/Yc/8p63SoW98B5dHkYDmpgvvU=`

The upstream BSD-3-Clause license is retained in `LICENSE`.
