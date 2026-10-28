#!/usr/bin/env bash
# Disable only this pilot. Preserve keys and release files for recovery.
set -euo pipefail
[[ "$(id -u)" == 0 ]]
ip -4 address show | grep -Fq '94.156.114.70/'
systemctl disable --now levik-dnstt.service levik-dnstt-proxy.service
ufw delete allow proto udp to 94.156.114.70 port 53
echo 'Pilot stopped. Remove LEVIK_DNSTT_PUBLIC_KEY from the profile backend to stop new issuance.'
