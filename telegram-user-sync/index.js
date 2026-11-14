#!/usr/bin/env node

// Watches WATCH_DIR and sends new recordings to TARGET (the bot) AS YOUR USER
// ACCOUNT, so they appear like manual uploads and the receiver's getUpdates
// poller picks them up. Files larger than MAX_PART_BYTES are split into
// name.partNofM pieces so the receiving bot can download each (<=20MB) part.

const fs = require('fs');
const path = require('path');
const { TelegramClient } = require('telegram');
const { StringSession } = require('telegram/sessions');
const { CustomFile } = require('telegram/client/uploads');

const { loadEnv } = require('./lib/env');
const { createState } = require('./lib/state');

loadEnv();

const log = (...a) => console.log(new Date().toISOString(), ...a);

const num = (v, d) => {
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) ? n : d;
};

const cfg = {
  apiId: Number(process.env.API_ID),
  apiHash: process.env.API_HASH,
  session: process.env.SESSION || (() => {
    try { return fs.readFileSync(path.resolve(__dirname, '.session'), 'utf8').trim(); } catch (e) { return ''; }
  })(),
  // Where to send: the bot the Mac receiver listens on (e.g. @pe_uploader_bot).
  target: process.env.TARGET,
  watchDir: process.env.WATCH_DIR,
  extensions: (process.env.WATCH_EXTENSIONS || '.m4a,.mp3,.amr,.aac,.wav,.ogg,.opus,.3gp')
    .split(',').map((s) => s.trim().toLowerCase()).filter(Boolean),
  recursive: /^(1|true|yes|on)$/i.test(process.env.WATCH_RECURSIVE || ''),
  pollIntervalMs: num(process.env.POLL_INTERVAL_MS, 5000),
  stableForMs: num(process.env.STABLE_FOR_MS, 10000),
  // 19MB keeps each part under the receiving bot's 20MB getFile download limit.
  maxPartBytes: num(process.env.MAX_PART_BYTES, 19000000),
  stateFile: process.env.STATE_FILE
    ? path.resolve(process.env.STATE_FILE)
    : path.resolve(process.cwd(), '.sent-state.json'),
};

const required = ['apiId', 'apiHash', 'session', 'target', 'watchDir'];
const missing = required.filter((k) => !cfg[k]);
if (missing.length) {
  console.error(`설정 누락: ${missing.join(', ')} — .env 를 확인하세요. (로그인 안 했으면 'node login.js')`);
  process.exit(1);
}

const listFiles = (dir) => {
  const out = [];
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return out; }
  entries.forEach((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (cfg.recursive) out.push(...listFiles(full));
    } else if (entry.isFile()) {
      out.push(full);
    }
  });
  return out;
};

const pad = (n, w) => String(n).padStart(w, '0');

const client = new TelegramClient(new StringSession(cfg.session), cfg.apiId, cfg.apiHash, {
  connectionRetries: 5,
});

const sendWhole = (filePath, name) => client.sendFile(cfg.target, {
  file: filePath,
  forceDocument: true,
  caption: name,
});

const sendParts = async (filePath, name) => {
  const buf = fs.readFileSync(filePath);
  const total = Math.max(1, Math.ceil(buf.length / cfg.maxPartBytes));
  const width = String(total).length;
  for (let i = 0; i < total; i += 1) {
    const start = i * cfg.maxPartBytes;
    const slice = buf.subarray(start, Math.min(buf.length, start + cfg.maxPartBytes));
    const partName = `${name}.part${pad(i + 1, width)}of${pad(total, width)}`;
    // eslint-disable-next-line no-await-in-loop
    await client.sendFile(cfg.target, {
      file: new CustomFile(partName, slice.length, '', Buffer.from(slice)),
      forceDocument: true,
      caption: partName,
    });
    log(`  part ${i + 1}/${total} sent: ${partName}`);
  }
  return total;
};

const state = createState(cfg.stateFile);

const processFile = async (filePath) => {
  let stat;
  try { stat = fs.statSync(filePath); } catch (e) { return; }
  if (!cfg.extensions.includes(path.extname(filePath).toLowerCase()) || stat.size === 0) return;
  if (state.isSent(filePath, stat)) return;
  if (Date.now() - stat.mtimeMs < cfg.stableForMs) return; // still being written

  const name = path.basename(filePath);
  const mb = (stat.size / 1024 / 1024).toFixed(1);
  if (stat.size <= cfg.maxPartBytes) {
    log(`Sending: ${name} (${mb}MB)`);
    await sendWhole(filePath, name);
  } else {
    log(`Sending (split): ${name} (${mb}MB)`);
    await sendParts(filePath, name);
  }
  state.markSent(filePath, stat);
  log(`Sent: ${name}`);
};

const tick = async () => {
  for (const filePath of listFiles(cfg.watchDir)) {
    try {
      // eslint-disable-next-line no-await-in-loop
      await processFile(filePath);
    } catch (err) {
      log(`ERROR sending ${path.basename(filePath)}: ${err.message}`);
    }
  }
};

let stopped = false;
const shutdown = async (sig) => { log(`Received ${sig}, stopping...`); stopped = true; try { await client.disconnect(); } catch (e) { /* noop */ } process.exit(0); };
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

(async () => {
  await client.connect();
  const me = await client.getMe();
  log(`Logged in as ${me.username ? `@${me.username}` : me.firstName}. Sending to ${cfg.target}.`);
  log(`Watching ${cfg.watchDir} (recursive=${cfg.recursive}) for ${cfg.extensions.join(', ')}`);
  const loop = async () => {
    if (stopped) return;
    await tick();
    if (!stopped) setTimeout(loop, cfg.pollIntervalMs);
  };
  await loop();
})().catch((err) => {
  log(`Fatal: ${err.stack || err.message}`);
  process.exit(1);
});
