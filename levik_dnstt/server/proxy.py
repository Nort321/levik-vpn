#!/usr/bin/env python3
"""DNSTT pilot TCP egress. Reuses the node's synchronized TUIC entitlements.

No new credential store: user removal/password rotation closes existing streams
within 15 seconds. No unauthenticated proxy, UDP, LAN/metadata or SMTP access.
"""
import asyncio
import contextlib
import hmac
import ipaddress
import json
import os
import re
import socket
import time
from pathlib import Path

MAX_CONNECTIONS = 96
MAX_PER_USER = 16
BYTES_PER_SECOND = 64 * 1024
ALLOWED_PORTS = {53, 80, 443, 5222, 5223, 8443}
YANDEX_DNS = {'77.88.8.8', '77.88.8.1'}


def read_users(path):
    with Path(path).open('rb') as handle:
        raw = handle.read(8 * 1024 * 1024 + 1)
    if len(raw) > 8 * 1024 * 1024:
        raise ValueError('access file too large')
    document = json.loads(raw)
    users = {}
    for inbound in document['inbounds']:
        if inbound.get('type') != 'tuic':
            continue
        for user in inbound.get('users', []):
            identity = user['uuid'].replace('-', '').lower()
            password = user['password']
            if not re.fullmatch(r'[0-9a-f]{32}', identity) or not re.fullmatch(r'[A-Za-z0-9_-]{22,64}', password):
                raise ValueError('invalid access file')
            if identity in users and users[identity] != password:
                raise ValueError('ambiguous access file')
            users[identity] = password
    return users


def public_target(address, port):
    ip = ipaddress.ip_address(address)
    if not ip.is_global or ip.is_multicast or ip.is_unspecified or ip.is_reserved:
        return False
    # Deny translated/mapped private destinations as well.
    if isinstance(ip, ipaddress.IPv6Address) and (ip.ipv4_mapped or ip.sixtofour or ip.teredo):
        return False
    return port in ALLOWED_PORTS and (port != 53 or str(ip) in YANDEX_DNS)


class Pacer:
    def __init__(self):
        self.next = 0.0
        self.lock = asyncio.Lock()

    async def wait(self, size):
        async with self.lock:
            delay = max(0, self.next - time.monotonic())
            if delay:
                await asyncio.sleep(delay)
            self.next = time.monotonic() + size / BYTES_PER_SECOND


class Proxy:
    def __init__(self, access_file):
        self.access_file = access_file
        self.users = read_users(access_file)  # Invalid initial state prevents startup.
        self.active = {}  # writer -> (identity, password); credentials never logged.
        self.counts = {}
        self.pacers = {}
        self.connections = 0

    async def reload(self):
        while True:
            await asyncio.sleep(15)
            await self.refresh()

    async def refresh(self):
        try:
            self.users = await asyncio.to_thread(read_users, self.access_file)
        except (OSError, ValueError, KeyError, TypeError):
            self.users = {}  # Fail closed on missing/corrupt entitlement state.
        for writer, (identity, password) in list(self.active.items()):
            if not hmac.compare_digest(self.users.get(identity, ''), password):
                writer.close()

    async def handle(self, reader, writer):
        if self.connections >= MAX_CONNECTIONS:
            writer.close()
            return
        self.connections += 1
        upstream = None
        identity = None
        try:
            async with asyncio.timeout(25):
                header = await reader.readexactly(2)
                if header[0] != 5 or header[1] == 0:
                    return
                methods = await reader.readexactly(header[1])
                if 2 not in methods:
                    writer.write(b'\x05\xff'); await writer.drain(); return
                writer.write(b'\x05\x02'); await writer.drain()
                auth = await reader.readexactly(2)
                if auth[0] != 1 or auth[1] != 32:
                    return
                name = (await reader.readexactly(auth[1])).decode('ascii')
                length = (await reader.readexactly(1))[0]
                if not 22 <= length <= 64:
                    return
                password = (await reader.readexactly(length)).decode('ascii')
                expected = self.users.get(name)
                if expected is None or not hmac.compare_digest(expected, password) or self.counts.get(name, 0) >= MAX_PER_USER:
                    writer.write(b'\x01\x01'); await writer.drain(); return
                identity = name
                self.counts[name] = self.counts.get(name, 0) + 1
                pacer = self.pacers.setdefault(name, Pacer())
                self.active[writer] = (name, password)
                writer.write(b'\x01\x00'); await writer.drain()
                request = await reader.readexactly(4)
                if request[:3] != b'\x05\x01\x00':
                    writer.write(b'\x05\x07\x00\x01' + bytes(6)); await writer.drain(); return
                if request[3] == 1:
                    host = socket.inet_ntop(socket.AF_INET, await reader.readexactly(4))
                elif request[3] == 4:
                    host = socket.inet_ntop(socket.AF_INET6, await reader.readexactly(16))
                elif request[3] == 3:
                    size = (await reader.readexactly(1))[0]
                    host = (await reader.readexactly(size)).decode('ascii')
                    if not re.fullmatch(r'(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?', host):
                        return
                else:
                    return
                port = int.from_bytes(await reader.readexactly(2), 'big')
                if port not in ALLOWED_PORTS:
                    writer.write(b'\x05\x02\x00\x01' + bytes(6)); await writer.drain(); return
                family = socket.AF_INET6 if ':' in host else socket.AF_INET
                addresses = await asyncio.get_running_loop().getaddrinfo(host, port, family=family, type=socket.SOCK_STREAM)
                # Resolve once, validate all results, dial the validated IP (no DNS rebinding).
                if not addresses or any(not public_target(item[4][0], port) for item in addresses):
                    writer.write(b'\x05\x02\x00\x01' + bytes(6)); await writer.drain(); return
                remote_reader, upstream = await asyncio.wait_for(
                    asyncio.open_connection(addresses[0][4][0], port, limit=32768), 10)
                writer.write(b'\x05\x00\x00\x01' + bytes(6)); await writer.drain()
            async def copy(source, destination):
                while data := await asyncio.wait_for(source.read(8192), 90):
                    await pacer.wait(len(data))
                    destination.write(data)
                    await asyncio.wait_for(destination.drain(), 30)
            tasks = [asyncio.create_task(copy(reader, upstream)), asyncio.create_task(copy(remote_reader, writer))]
            try:
                done, _ = await asyncio.wait(tasks, timeout=3600, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
            finally:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
        except (OSError, ValueError, TimeoutError, asyncio.IncompleteReadError):
            pass  # Never log destination addresses, usernames or credentials.
        finally:
            self.connections -= 1
            self.active.pop(writer, None)
            if identity is not None:
                self.counts[identity] -= 1
                if self.counts[identity] == 0:
                    self.counts.pop(identity)
                    self.pacers.pop(identity, None)
            for stream in (upstream, writer):
                if stream is not None:
                    stream.close()
                    with contextlib.suppress(OSError, TimeoutError):
                        await asyncio.wait_for(stream.wait_closed(), 2)


async def main():
    proxy = Proxy(os.environ.get('DNSTT_ACCESS_FILE', '/etc/levik-tuic/config.json'))
    server = await asyncio.start_server(proxy.handle, '127.0.0.1', 17853, limit=32768, backlog=64)
    async with server:
        await asyncio.gather(server.serve_forever(), proxy.reload())


if __name__ == '__main__':
    asyncio.run(main())
