#!/usr/bin/python3
"""DNS-only access for active, unexpired members of the existing LTE squad."""
import base64
import hashlib
import hmac
import json
import subprocess
import time

SQUAD = '9b5e687b-0630-47b7-8248-e2a8f013ff05'
QUERY = """select coalesce(json_agg(distinct u.vless_uuid::text), '[]')
from users u join internal_squad_members m on m.user_id=u.id
where m.internal_squad_uuid='%s'::uuid and u.status='ACTIVE'
and u.expire_at > (now() at time zone 'utc')""" % SQUAD

def password(key, uuid):
    return base64.urlsafe_b64encode(hmac.new(key, ('levik-dnstt-v1:'+uuid).encode(), hashlib.sha256).digest()).decode().rstrip('=')[:32]

def once():
    result = subprocess.run(['docker','exec','remnawave-db','psql','-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1','-At','-c',QUERY],capture_output=True,check=True,timeout=30)
    uuids = json.loads(result.stdout)
    with open('/opt/levik-tuic/secrets/password.key','rb') as handle:
        key = base64.b64decode(handle.read().strip(), validate=True)
    if len(key) < 32: raise ValueError('invalid key')
    users = [{'uuid': u.replace('-','').lower(), 'password': password(key,u.lower())} for u in sorted(uuids)]
    payload = json.dumps({'version':1,'validUntil':int(time.time())+120,'users':users}).encode()
    result = subprocess.run(['ssh','-i','/etc/levik-dnstt-sync/id_ed25519','-o','UserKnownHostsFile=/etc/levik-dnstt-sync/known_hosts','-o','IdentitiesOnly=yes','-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=8','root@94.156.114.70','apply'],input=payload,capture_output=True,timeout=20,check=True)
    status = json.loads(result.stdout)
    if status.get('ok') is not True: raise ValueError('apply rejected')
    return len(users)

if __name__ == '__main__':
    while True:
        try: print('DNS mobile access synced:', once(), flush=True)
        except Exception: print('DNS access sync failed; snapshot will expire closed', flush=True)
        time.sleep(20)
