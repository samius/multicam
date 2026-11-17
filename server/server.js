// Multicam remote recording control server
// In-memory state only, single-file, no build step for the client.
//
// Protocol (see handoff-multicam.md):
//   GET  /api/state?device=<id>        -> { recording, cmdId }
//   POST /api/report                   -> heartbeat JSON from device
//   POST /api/preview/<id>             -> JPEG body, stored as last preview frame
//   GET  /api/preview/<id>             -> last JPEG preview for device
//   GET  /api/devices                  -> list of all known devices + state
//   POST /api/command                  -> { target: "<id>"|"all", recording: bool }
//
// Auth: shared token required as header `X-Auth-Token: <token>` OR query
// param `?token=<token>` (useful for <img src> preview tags and simple clients).
// Set the token via env var MULTICAM_TOKEN (falls back to "changeme-token" with
// a loud warning so it's never silently insecure in a way that's hard to notice).

'use strict';

const express = require('express');
const path = require('path');

const PORT = process.env.PORT || 8787;
const TOKEN = process.env.MULTICAM_TOKEN || 'changeme-token';
const STALE_MS = 5000; // heartbeat older than this = considered offline in UI (client-side), server just reports lastSeen

if (TOKEN === 'changeme-token') {
  console.warn('[WARN] MULTICAM_TOKEN not set - using insecure default "changeme-token". Set MULTICAM_TOKEN env var in production.');
}

const app = express();
app.use(express.json({ limit: '1mb' }));
// Raw body for JPEG preview uploads
app.use('/api/preview', express.raw({ type: '*/*', limit: '5mb' }));

// ---- State ----
// devices: Map<deviceId, DeviceState>
// DeviceState = {
//   id, name,
//   desiredRecording: bool, cmdId: int,       // what the server wants
//   recording: bool,                           // last reported actual state
//   currentFileDurationSec, batteryPct, charging, batteryTempC,
//   thermalStatus, freeSpaceBytes, resolution, fps, bitrateKbps,
//   currentFileName,
//   lastReportAt: epoch ms
// }
const devices = new Map();
// previews: Map<deviceId, { buffer: Buffer, contentType: string, at: epoch ms }>
const previews = new Map();
let globalCmdId = 0;

function getOrCreateDevice(id, name) {
  let d = devices.get(id);
  if (!d) {
    d = {
      id,
      name: name || id,
      desiredRecording: false,
      cmdId: 0,
      recording: false,
      currentFileDurationSec: 0,
      batteryPct: null,
      charging: null,
      batteryTempC: null,
      thermalStatus: null,
      freeSpaceBytes: null,
      resolution: null,
      fps: null,
      bitrateKbps: null,
      currentFileName: null,
      lastReportAt: 0,
    };
    devices.set(id, d);
  }
  if (name) d.name = name;
  return d;
}

// ---- Auth middleware ----
function checkAuth(req, res, next) {
  const headerToken = req.get('X-Auth-Token');
  const queryToken = req.query.token;
  if (headerToken === TOKEN || queryToken === TOKEN) return next();
  return res.status(401).json({ error: 'unauthorized' });
}

app.use('/api', checkAuth);

// ---- GET /api/state?device=<id> ----
app.get('/api/state', (req, res) => {
  const deviceId = req.query.device;
  if (!deviceId) return res.status(400).json({ error: 'missing device param' });
  const d = getOrCreateDevice(deviceId);
  res.json({ recording: d.desiredRecording, cmdId: d.cmdId });
});

// ---- POST /api/report (heartbeat) ----
app.post('/api/report', (req, res) => {
  const b = req.body || {};
  if (!b.id) return res.status(400).json({ error: 'missing id' });
  const d = getOrCreateDevice(b.id, b.name);
  d.recording = !!b.recording;
  d.currentFileDurationSec = b.currentFileDurationSec ?? d.currentFileDurationSec;
  d.batteryPct = b.batteryPct ?? d.batteryPct;
  d.charging = b.charging ?? d.charging;
  d.batteryTempC = b.batteryTempC ?? d.batteryTempC;
  d.thermalStatus = b.thermalStatus ?? d.thermalStatus;
  d.freeSpaceBytes = b.freeSpaceBytes ?? d.freeSpaceBytes;
  d.resolution = b.resolution ?? d.resolution;
  d.fps = b.fps ?? d.fps;
  d.bitrateKbps = b.bitrateKbps ?? d.bitrateKbps;
  d.currentFileName = b.currentFileName ?? d.currentFileName;
  d.lastReportAt = Date.now();
  res.json({ ok: true, desiredRecording: d.desiredRecording, cmdId: d.cmdId });
});

// ---- POST /api/preview/:id (JPEG body) ----
app.post('/api/preview/:id', (req, res) => {
  const id = req.params.id;
  if (!req.body || !req.body.length) return res.status(400).json({ error: 'empty body' });
  getOrCreateDevice(id); // ensure it shows up even if no report has arrived yet
  previews.set(id, {
    buffer: req.body,
    contentType: req.get('Content-Type') || 'image/jpeg',
    at: Date.now(),
  });
  res.json({ ok: true });
});

// ---- GET /api/preview/:id (serve last JPEG) ----
// Note: this one also needs auth but images are usually loaded via <img src="...?token=...">
app.get('/api/preview/:id', (req, res) => {
  const p = previews.get(req.params.id);
  if (!p) return res.status(404).end();
  res.set('Content-Type', p.contentType);
  res.set('Cache-Control', 'no-store');
  res.send(p.buffer);
});

// ---- GET /api/devices ----
app.get('/api/devices', (req, res) => {
  const now = Date.now();
  const list = Array.from(devices.values()).map((d) => ({
    ...d,
    ageMs: d.lastReportAt ? now - d.lastReportAt : null,
    hasPreview: previews.has(d.id),
  }));
  res.json({ devices: list, now });
});

// ---- POST /api/command { target: "<id>"|"all", recording: bool } ----
app.post('/api/command', (req, res) => {
  const { target, recording } = req.body || {};
  if (typeof recording !== 'boolean' || !target) {
    return res.status(400).json({ error: 'need target and boolean recording' });
  }
  globalCmdId += 1;
  const affected = [];
  if (target === 'all') {
    for (const d of devices.values()) {
      d.desiredRecording = recording;
      d.cmdId = globalCmdId;
      affected.push(d.id);
    }
  } else {
    const d = getOrCreateDevice(target);
    d.desiredRecording = recording;
    d.cmdId = globalCmdId;
    affected.push(d.id);
  }
  res.json({ ok: true, cmdId: globalCmdId, affected });
});

// ---- Static control page (public, no auth - page itself asks for token via localStorage) ----
app.use(express.static(path.join(__dirname, 'public')));

app.listen(PORT, () => {
  console.log(`multicam-server listening on port ${PORT}`);
});
