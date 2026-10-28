#!/usr/bin/python3
"""Forced SSH command: replace only the DNS mobile-access snapshot."""
import json
import os
import re
import tempfile
import time
import grp
import sys

def main():
    if os.environ.get('SSH_ORIGINAL_COMMAND', 'apply') != 'apply':
        raise ValueError('invalid command')
    raw = sys.stdin.buffer.read(8 * 1024 * 1024 + 1)
    if len(raw) > 8 * 1024 * 1024:
        raise ValueError('too large')
    data = json.loads(raw)
    if set(data) != {'version', 'validUntil', 'users'} or data['version'] != 1:
        raise ValueError('invalid snapshot')
    expiry = data['validUntil']
    if type(expiry) is not int or not time.time() < expiry <= time.time() + 180:
        raise ValueError('invalid expiry')
    if not isinstance(data['users'], list) or len(data['users']) > 50000:
        raise ValueError('invalid users')
    seen = set()
    for user in data['users']:
        if set(user) != {'uuid', 'password'} or not re.fullmatch(r'[0-9a-f]{32}', user['uuid']) or not re.fullmatch(r'[A-Za-z0-9_-]{32}', user['password']) or user['uuid'] in seen:
            raise ValueError('invalid user')
        seen.add(user['uuid'])
    target = {'validUntil': expiry, 'inbounds': [{'type': 'tuic', 'users': data['users']}]}
    fd, path = tempfile.mkstemp(prefix='.access-', dir='/etc/levik-dnstt')
    try:
        os.fchmod(fd, 0o640)
        os.fchown(fd, 0, grp.getgrnam('levik-tuic').gr_gid)
        with os.fdopen(fd, 'w') as handle:
            json.dump(target, handle, separators=(',', ':'))
            handle.flush(); os.fsync(handle.fileno())
        os.replace(path, '/etc/levik-dnstt/access.json')
    finally:
        if os.path.exists(path): os.unlink(path)
    print(json.dumps({'ok': True, 'users': len(seen)}))

if __name__ == '__main__':
    try: main()
    except Exception:
        print('{"ok":false,"error":"invalid access snapshot"}')
        raise SystemExit(1)
