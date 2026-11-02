#!/usr/bin/env node
// Real Xray interoperability check, using configs exported by the Android unit tests.
// All traffic remains on loopback; the upstream SOCKS server is an in-process fake.
// No TUN, root, account, subscription, public destination or production key is used.
import assert from 'node:assert/strict';
import net from 'node:net';
import dgram from 'node:dgram';
import { spawn } from 'node:child_process';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { once } from 'node:events';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const binary = process.env.LEVIK_TEST_XRAY || join(root, 'levik_vpn_macos/vendor/xray/darwin-arm64/xray');
const hostPath = join(root, 'levik_vpn_android/app/build/sharing-smoke-host.json');
const androidPath = join(root, 'levik_vpn_android/app/build/sharing-smoke-guest.json');
const directory = await mkdtemp(join(tmpdir(), 'levik-sharing-smoke-'));
const children = new Set();
const sockets = new Set();
const servers = new Set();
const datagrams = new Set();
const destination = '1.1.1.1'; // Public address accepted by destination policy; only the fake SOCKS server sees it.
const marker = 'LEVIK_SHARING_OK';
let requests = 0;

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function track(socket) { sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket)); return socket; }
async function port() {
  const server = net.createServer(); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const number = server.address().port; await new Promise(resolve => server.close(resolve)); return number;
}
async function connect(portNumber) {
  const socket = track(net.connect(portNumber, '127.0.0.1'));
  await once(socket, 'connect'); return socket;
}
function reader(socket) {
  let buffer = Buffer.alloc(0), ended = false;
  socket.on('data', data => { buffer = Buffer.concat([buffer, data]); });
  socket.on('close', () => { ended = true; });
  return async (predicate, timeout = 3000) => {
    const deadline = Date.now() + timeout;
    while (!predicate(buffer)) {
      if (ended || Date.now() >= deadline) throw new Error('Local sharing test timed out or closed');
      await delay(10);
    }
    const result = buffer; buffer = Buffer.alloc(0); return result;
  };
}
function socksAddress(host, portNumber) { return Buffer.from([1, ...host.split('.').map(Number), portNumber >> 8, portNumber & 255]); }
async function socksConnect(portNumber, host = destination, targetPort = 80, command = 1) {
  const socket = await connect(portNumber), read = reader(socket);
  socket.write(Buffer.from([5, 1, 0]));
  assert.deepEqual(await read(b => b.length >= 2), Buffer.from([5, 0]));
  socket.write(Buffer.concat([Buffer.from([5, command, 0]), socksAddress(host, targetPort)]));
  const reply = await read(b => b.length >= 10);
  assert.equal(reply[1], 0, 'SOCKS request failed');
  return { socket, read, port: reply.readUInt16BE(reply.length - 2) };
}

async function upstream() {
  const udp = dgram.createSocket('udp4'); datagrams.add(udp);
  udp.bind(0, '127.0.0.1'); await once(udp, 'listening');
  udp.on('message', (bytes, from) => { requests++; udp.send(bytes, from.port, from.address); });
  const server = net.createServer(socket => {
    track(socket); let stage = 0, bytes = Buffer.alloc(0);
    socket.on('data', incoming => {
      bytes = Buffer.concat([bytes, incoming]);
      if (stage === 0) {
        if (bytes.length < 2 || bytes.length < bytes[1] + 2) return;
        assert.equal(bytes[0], 5); bytes = bytes.subarray(bytes[1] + 2); socket.write(Buffer.from([5, 0])); stage = 1;
      }
      if (stage === 1) {
        if (bytes.length < 5) return;
        const addressLength = bytes[3] === 1 ? 4 : bytes[3] === 4 ? 16 : bytes[4] + 1;
        const length = 4 + addressLength + 2; if (bytes.length < length) return;
        const command = bytes[1]; bytes = bytes.subarray(length); stage = command === 3 ? 3 : 2;
        socket.write(Buffer.concat([Buffer.from([5, 0, 0]), socksAddress('127.0.0.1', command === 3 ? udp.address().port : 1)]));
      }
      if (stage === 2 && bytes.includes(Buffer.from('\r\n\r\n'))) {
        requests++; stage = 4;
        socket.end(`HTTP/1.1 200 OK\r\nContent-Length: ${marker.length}\r\nConnection: close\r\n\r\n${marker}`);
      }
    });
  });
  servers.add(server); server.listen(0, '127.0.0.1'); await once(server, 'listening');
  return server.address().port;
}

async function start(config, readyPort) {
  const child = spawn(binary, ['run', '-config', 'stdin:'], { stdio: ['pipe', 'pipe', 'pipe'] });
  children.add(child);
  let exited = false;
  // Never echo native output/configs: they may contain endpoints or credentials.
  child.stdout.resume(); child.stderr.resume(); child.on('exit', () => { exited = true; children.delete(child); });
  child.stdin.end(JSON.stringify(config));
  for (let i = 0; i < 100; i++) {
    if (exited) throw new Error('Xray rejected a generated sharing configuration');
    try { (await connect(readyPort)).destroy(); return child; } catch { await delay(30); }
  }
  throw new Error('Xray listener did not start');
}
async function stop(child) { if (child.exitCode === null && child.signalCode === null) { child.kill('SIGTERM'); await once(child, 'exit'); } }
async function hostFixture(upstreamPort, encryptedPort, httpPort) {
  const config = JSON.parse(await readFile(hostPath, 'utf8'));
  config.inbounds = config.inbounds.filter(i => i.protocol !== 'tun');
  for (const inbound of config.inbounds) {
    if (inbound.tag === 'levik-share-http') { inbound.listen = '127.0.0.1'; inbound.port = httpPort; }
    else if (inbound.tag === 'levik-share-guest') { inbound.listen = '127.0.0.1'; inbound.port = encryptedPort; }
    else if (typeof inbound.listen === 'string' && inbound.listen.startsWith('/')) inbound.listen = join(directory, 'stats.sock');
  }
  const exit = config.outbounds.find(o => o.tag === 'test-vpn');
  assert.equal(exit.protocol, 'socks'); exit.settings = { servers: [{ address: '127.0.0.1', port: upstreamPort }] };
  // The account's direct route is an explicit dead sink in the lab: never contact public networks.
  for (const outbound of config.outbounds) if (outbound.protocol === 'freedom') { outbound.protocol = 'blackhole'; outbound.settings = {}; }
  return config;
}
async function guestFixture(path, encryptedPort, localPort, key, wrong = false) {
  const config = JSON.parse(await readFile(path, 'utf8'));
  delete config.env; delete config.api; delete config.stats; delete config.policy;
  // Replace only the OS TUN with local SOCKS for unprivileged native wire testing.
  config.inbounds = config.inbounds.filter(i => i.protocol === 'tun').map(i => ({ tag: i.tag, protocol: 'socks', listen: '127.0.0.1', port: localPort, settings: { auth: 'noauth', udp: true } }));
  assert.equal(config.inbounds.length, 1);
  const relay = config.outbounds.find(o => o.protocol === 'shadowsocks');
  assert.ok(relay); assert.equal(relay.settings.servers[0].method, 'aes-256-gcm');
  relay.settings.servers[0].address = '127.0.0.1'; relay.settings.servers[0].port = encryptedPort;
  relay.settings.servers[0].password = wrong ? 'wrong-public-test-key-not-a-real-credential' : key;
  return config;
}
async function tcpThroughGuest(localPort) {
  const client = await socksConnect(localPort);
  try { client.socket.write('GET / HTTP/1.1\r\nHost: test.invalid\r\n\r\n'); assert.ok((await client.read(b => b.includes(Buffer.from(marker)))).includes(Buffer.from(marker))); }
  finally { client.socket.destroy(); }
}
async function udpThroughGuest(localPort) {
  const control = await socksConnect(localPort, '0.0.0.0', 0, 3);
  const udp = dgram.createSocket('udp4'); datagrams.add(udp); udp.bind(0, '127.0.0.1'); await once(udp, 'listening');
  try {
    const received = new Promise((resolve, reject) => { const timeout = setTimeout(() => reject(new Error('UDP was not relayed')), 3000); udp.once('message', data => { clearTimeout(timeout); resolve(data); }); });
    const message = Buffer.concat([Buffer.from([0, 0, 0]), socksAddress(destination, 53000), Buffer.from(marker)]);
    udp.send(message, control.port, '127.0.0.1');
    assert.ok((await received).includes(Buffer.from(marker)));
  } finally { control.socket.destroy(); udp.close(); datagrams.delete(udp); }
}
async function httpThroughHost(httpPort, host = destination, success = true) {
  const socket = await connect(httpPort), read = reader(socket);
  try {
    socket.write(`CONNECT ${host}:443 HTTP/1.1\r\nHost: ${host}:443\r\n\r\n`);
    const response = await read(b => b.includes(Buffer.from('\r\n\r\n')));
    if (!response.toString().includes('200')) { assert.equal(success, false); return; }
    socket.write('GET / HTTP/1.1\r\nHost: test.invalid\r\n\r\n');
    const body = await read(b => b.includes(Buffer.from(marker)), success ? 3000 : 400);
    assert.equal(body.includes(Buffer.from(marker)), success);
  } catch (error) { if (success) throw error; }
  finally { socket.destroy(); }
}

try {
  const upstreamPort = await upstream(), encryptedPort = await port(), httpPort = await port();
  const config = await hostFixture(upstreamPort, encryptedPort, httpPort);
  const key = config.inbounds.find(i => i.protocol === 'shadowsocks').settings.password;
  const host = await start(config, httpPort);
  await httpThroughHost(httpPort); console.log('PASS manual HTTP CONNECT through selected VPN upstream');
  const before = requests; await httpThroughHost(httpPort, '127.0.0.1', false); await httpThroughHost(httpPort, '10.0.0.1', false);
  assert.equal(requests, before); console.log('PASS private destinations blocked before upstream');
  const guests = [['Android', androidPath], ...process.argv.slice(2).map((path, index) => [`Desktop ${index + 1}`, resolve(path)])];
  for (const [name, path] of guests) {
    const localPort = await port();
    const child = await start(await guestFixture(path, encryptedPort, localPort, key), localPort);
    await tcpThroughGuest(localPort); await udpThroughGuest(localPort); await stop(child);
    console.log(`PASS ${name} encrypted TCP and UDP to Android host config`);
  }
  const wrongPort = await port();
  const wrong = await start(await guestFixture(androidPath, encryptedPort, wrongPort, key, true), wrongPort);
  const beforeWrong = requests;
  await assert.rejects(tcpThroughGuest(wrongPort)); assert.equal(requests, beforeWrong); await stop(wrong);
  console.log('PASS wrong guest key rejected');
  // Stop the sole fake upstream: the owner config also contains a direct rule.
  // Guest routing must never fall through to it.
  for (const server of servers) server.close();
  for (const socket of sockets) socket.destroy();
  await httpThroughHost(httpPort, destination, false);
  await stop(host);
  console.log('PASS upstream loss does not produce a working direct fallback');
  console.log('Sharing native smoke complete. OS TUN/hotspot/firewall and physical devices remain separate checks.');
} finally {
  for (const socket of sockets) socket.destroy();
  for (const udp of datagrams) { try { udp.close(); } catch {} }
  for (const server of servers) { try { server.close(); } catch {} }
  for (const child of children) child.kill('SIGKILL');
  await rm(directory, { recursive: true, force: true });
}
