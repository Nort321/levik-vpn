#!/usr/bin/env bash
# Run as root on 94.156.114.70 from a reviewed staging folder with dnstt-server,
# proxy.py and both service units. First install only; never overwrite a release.
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
release=/opt/levik-dnstt/releases/20261028-pilot1
[[ "$(id -u)" == 0 ]]
ip -4 address show | grep -Fq '94.156.114.70/'
for path in "$release" /etc/levik-dnstt /etc/systemd/system/levik-dnstt.service /etc/systemd/system/levik-dnstt-proxy.service; do
 [[ ! -e "$path" && ! -L "$path" ]] || { echo "Refusing to overwrite $path" >&2; exit 1; }
done
[[ -r /etc/levik-tuic/config.json ]]
getent passwd levik-tuic >/dev/null
python3 -c 'import sys; assert sys.version_info >= (3, 11)'
[[ -x "$root/dnstt-server" ]]
if ss -Hlun 'sport = :53' | grep -Eq '94\.156\.114\.70:53|0\.0\.0\.0:53|\*:53'; then
 echo 'External DNS port is occupied; stop before installation' >&2; exit 1
fi
if ss -Hltn 'sport = :17853' | grep -q .; then
 echo 'Proxy port is occupied; stop before installation' >&2; exit 1
fi
python3 -m py_compile "$root/proxy.py"
# All destinations below were checked absent. No existing service/config is replaced.
install -d -m 0755 "$release"
install -m 0755 "$root/dnstt-server" "$release/dnstt-server"
install -m 0644 "$root/proxy.py" "$release/proxy.py"
install -d -m 0700 /etc/levik-dnstt
install -m 0644 "$root/resolv.conf" /etc/levik-dnstt/resolv.conf
umask 077
"$release/dnstt-server" -gen-key -privkey-file /etc/levik-dnstt/server.key -pubkey-file /etc/levik-dnstt/server.pub
install -m 0644 "$root/levik-dnstt.service" /etc/systemd/system/levik-dnstt.service
install -m 0644 "$root/levik-dnstt-proxy.service" /etc/systemd/system/levik-dnstt-proxy.service
systemd-analyze verify /etc/systemd/system/levik-dnstt.service /etc/systemd/system/levik-dnstt-proxy.service
systemctl daemon-reload
trap 'systemctl stop levik-dnstt.service levik-dnstt-proxy.service || true' ERR
systemctl enable --now levik-dnstt-proxy.service levik-dnstt.service
sleep 1
for service in levik-dnstt-proxy.service levik-dnstt.service; do
 systemctl is-active --quiet "$service"
done
ufw allow proto udp to 94.156.114.70 port 53 comment 'Levik DNSTT pilot'
trap - ERR
echo 'DNSTT public key (set LEVIK_DNSTT_PUBLIC_KEY on the profile backend only after testing):'
cat /etc/levik-dnstt/server.pub
