/**
 * Spotifree Jam relay server ("Escuchar juntos").
 * Same protocol as src/lib/jam-service.js. Rooms live in memory: they disappear when the host
 * leaves or the server restarts. No accounts, no storage.
 */
import http from 'node:http';
import { WebSocketServer } from 'ws';

const PORT = process.env.PORT || 8080;
const MAX_MEMBERS = 20;
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const rooms = new Map(); // code -> { code, host, members: Set<ws>, state }

const newCode = () => {
  let c;
  do {
    c = Array.from({ length: 5 }, () => ALPHABET[Math.floor(Math.random() * ALPHABET.length)]).join('');
  } while (rooms.has(c));
  return c;
};

const send = (ws, msg) => {
  if (ws.readyState === 1) ws.send(JSON.stringify(msg));
};

const memberList = (room) => [...room.members].map(m => ({ name: m.name, host: m === room.host }));

const broadcastMembers = (room) => {
  const list = memberList(room);
  for (const m of room.members) send(m, { type: 'members', members: list });
};

function leave(ws) {
  const room = ws.room && rooms.get(ws.room);
  ws.room = null;
  if (!room) return;
  room.members.delete(ws);
  if (room.host === ws) {
    for (const m of room.members) {
      send(m, { type: 'closed' });
      m.room = null;
    }
    rooms.delete(room.code);
  } else {
    broadcastMembers(room);
  }
}

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end(`Spotifree Jam server OK (${rooms.size} rooms)`);
});
const wss = new WebSocketServer({ server, maxPayload: 64 * 1024 });

wss.on('connection', (ws) => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    const name = String(msg.name || 'Invitado').slice(0, 40);
    switch (msg.type) {
      case 'create': {
        leave(ws);
        const room = { code: newCode(), host: ws, members: new Set([ws]), state: null };
        rooms.set(room.code, room);
        ws.room = room.code;
        ws.name = name;
        send(ws, { type: 'created', room: room.code });
        broadcastMembers(room);
        break;
      }
      case 'join': {
        const room = rooms.get(String(msg.room || '').toUpperCase());
        if (!room) { send(ws, { type: 'error', message: 'La sala no existe' }); break; }
        if (room.members.size >= MAX_MEMBERS) { send(ws, { type: 'error', message: 'La sala está llena' }); break; }
        leave(ws);
        room.members.add(ws);
        ws.room = room.code;
        ws.name = name;
        send(ws, { type: 'joined', room: room.code, state: room.state });
        broadcastMembers(room);
        break;
      }
      case 'state': {
        const room = rooms.get(ws.room);
        if (!room || room.host !== ws) break;
        room.state = msg.state;
        for (const m of room.members) if (m !== ws) send(m, { type: 'state', state: msg.state });
        break;
      }
      case 'suggest': {
        const room = rooms.get(ws.room);
        if (!room || room.host === ws || !msg.track) break;
        send(room.host, { type: 'suggest', track: msg.track, from: ws.name });
        break;
      }
      case 'leave':
        leave(ws);
        break;
      default:
        break;
    }
  });
  ws.on('close', () => leave(ws));
});

// Drop dead connections (phones that lost signal never send a close frame)
setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
}, 30000);

server.listen(PORT, () => console.log('Spotifree Jam server listening on', PORT));
