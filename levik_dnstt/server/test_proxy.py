import asyncio
import json
import tempfile
import unittest
from pathlib import Path
from proxy import Proxy, public_target, read_users

IDENTITY = '123e4567e89b42d3a456426614174000'
PASSWORD = 'p' * 32


class AccessTests(unittest.TestCase):
    def test_blocks_lan_metadata_translated_addresses_smtp_and_foreign_dns(self):
        for ip, port in [('127.0.0.1', 443), ('169.254.169.254', 80), ('10.0.0.1', 443),
                         ('::ffff:127.0.0.1', 443), ('2002:7f00:1::', 443), ('ff02::1', 443),
                         ('8.8.8.8', 53), ('1.1.1.1', 25)]:
            self.assertFalse(public_target(ip, port), (ip, port))
        self.assertTrue(public_target('77.88.8.8', 53))
        self.assertTrue(public_target('1.1.1.1', 443))


class SocksTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'access.json'
        self.path.write_text(json.dumps({'inbounds': [{'type': 'tuic', 'users': [
            {'uuid': '123e4567-e89b-42d3-a456-426614174000', 'password': PASSWORD}]}]}))
        self.proxy = Proxy(self.path)
        self.server = await asyncio.start_server(self.proxy.handle, '127.0.0.1', 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()
        self.temp.cleanup()

    async def connect(self, password=PASSWORD):
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port)
        writer.write(bytes([5, 1, 2])); await writer.drain()
        self.assertEqual(await reader.readexactly(2), bytes([5, 2]))
        writer.write(bytes([1, 32]) + IDENTITY.encode() + bytes([len(password)]) + password.encode())
        await writer.drain()
        return reader, writer

    async def test_requires_password_authentication(self):
        reader, writer = await asyncio.open_connection('127.0.0.1', self.port)
        writer.write(bytes([5, 1, 0])); await writer.drain()
        self.assertEqual(await reader.readexactly(2), bytes([5, 255]))
        writer.close(); await writer.wait_closed()

    async def test_wrong_credentials_are_rejected(self):
        reader, writer = await self.connect('x' * 32)
        self.assertEqual(await reader.readexactly(2), bytes([1, 1]))
        writer.close(); await writer.wait_closed()

    async def test_removed_users_are_not_admitted(self):
        self.proxy.users = {}
        reader, writer = await self.connect()
        self.assertEqual(await reader.readexactly(2), bytes([1, 1]))
        writer.close(); await writer.wait_closed()

    async def test_revocation_closes_an_already_authenticated_stream(self):
        reader, writer = await self.connect()
        self.assertEqual(await reader.readexactly(2), bytes([1, 0]))
        self.path.write_text('{"inbounds":[]}')
        await self.proxy.refresh()
        self.assertEqual(await asyncio.wait_for(reader.read(1), 1), b'')
        writer.close(); await writer.wait_closed()

    async def test_corrupt_access_file_fails_closed(self):
        self.path.write_text('invalid')
        await self.proxy.refresh()
        self.assertEqual(self.proxy.users, {})

    async def test_authenticated_user_cannot_access_metadata(self):
        reader, writer = await self.connect()
        self.assertEqual(await reader.readexactly(2), bytes([1, 0]))
        writer.write(bytes([5, 1, 0, 1, 169, 254, 169, 254, 0, 80])); await writer.drain()
        self.assertEqual((await reader.readexactly(10))[1], 2)
        writer.close(); await writer.wait_closed()

    async def test_udp_associate_is_rejected(self):
        reader, writer = await self.connect()
        self.assertEqual(await reader.readexactly(2), bytes([1, 0]))
        writer.write(bytes([5, 3, 0, 1])); await writer.drain()
        self.assertEqual((await reader.readexactly(10))[1], 7)
        writer.close(); await writer.wait_closed()


if __name__ == '__main__':
    unittest.main()
