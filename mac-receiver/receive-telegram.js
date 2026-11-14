#!/usr/bin/env node

// Mac-side receiver: polls Telegram for new recordings, downloads them to the
// inbound folder preserving filenames, and reassembles split parts back into
// the original file. No tunnel / no inbound HTTP endpoint required — it only
// needs the bot token. Zero dependencies; Node.js 18+.
//
// Config (env vars or a .env file next to this script):
//   TELEGRAM_BOT_TOKEN   (required)
//   OUTPUT_DIR           default /Users/donoh/.openclaw/media/inbound
//   OFFSET_FILE          default <OUTPUT_DIR>/.telegram-offset
//   POLL_TIMEOUT_SEC     default 30
//   STABLE_FOR_MS        default 5000

const fs = require('fs');
const path = require('path');

const { createTelegram } = require('./lib/telegram');
const { processUpdate } = require('./lib/receiver');
const { scanOnce } = require('./lib/reassembler');

const timestamp = () => new Date().toISOString();
const logger = {
  info: (...a) => console.log(timestamp(), '[INFO]', ...a),
  error: (...a) => console.error(timestamp(), '[ERROR]', ...a),
};

// Tiny .env loader (no dependency) — does not overwrite real env vars.
const loadDotEnv = (envPath) => {
  if (!fs.existsSync(envPath)) {
    return;
  }
  fs.readFileSync(envPath, 'utf8').split(/\r?\n/).forEach((raw) => {
    const line = raw.trim();
    if (!line || line.startsWith('#')) {
      return;
    }
    const eq = line.indexOf('=');
    if (eq === -1) {
      return;
    }
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (key && !(key in process.env)) {
      process.env[key] = value;
    }
  });
};

loadDotEnv(path.resolve(__dirname, '.env'));

const botToken = process.env.TELEGRAM_BOT_TOKEN;
const outputDir = process.env.OUTPUT_DIR || '/Users/donoh/.openclaw/media/inbound';
const offsetFile = process.env.OFFSET_FILE || path.join(outputDir, '.telegram-offset');
const pollTimeoutSec = Number.parseInt(process.env.POLL_TIMEOUT_SEC, 10) || 30;
const stableForMs = Number.parseInt(process.env.STABLE_FOR_MS, 10) || 5000;

if (!botToken) {
  logger.error('TELEGRAM_BOT_TOKEN is required (set it in the environment or a .env file).');
  process.exit(1);
}
fs.mkdirSync(outputDir, { recursive: true });

const loadOffset = () => {
  try {
    return Number.parseInt(fs.readFileSync(offsetFile, 'utf8'), 10) || undefined;
  } catch (err) {
    return undefined;
  }
};
const saveOffset = (offset) => {
  try {
    fs.writeFileSync(offsetFile, String(offset));
  } catch (err) {
    logger.error(`Could not save offset: ${err.message}`);
  }
};

const telegram = createTelegram({ botToken });

let stopped = false;
const shutdown = (signal) => {
  logger.info(`Received ${signal}, stopping...`);
  stopped = true;
  process.exit(0);
};
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

const run = async () => {
  const me = await telegram.getMe();
  logger.info(`Connected to Telegram as @${me.username}. Saving to ${outputDir}`);

  let offset = loadOffset();
  while (!stopped) {
    let updates;
    try {
      // eslint-disable-next-line no-await-in-loop
      updates = await telegram.getUpdates(offset, pollTimeoutSec);
    } catch (err) {
      // "Conflict" means another process is polling this same bot. Wait longer
      // (the other poller's long-poll has to time out) and retry automatically.
      if (/Conflict/i.test(err.message)) {
        logger.error('Conflict: another process is already polling this bot. '
          + 'Stop the other receiver (or use a dedicated bot token). Retrying in 15s...');
        // eslint-disable-next-line no-await-in-loop
        await new Promise((r) => { setTimeout(r, 15000); });
      } else {
        logger.error(`getUpdates failed: ${err.message}`);
        // eslint-disable-next-line no-await-in-loop
        await new Promise((r) => { setTimeout(r, 5000); });
      }
      continue;
    }

    for (const update of updates) {
      // eslint-disable-next-line no-await-in-loop
      await processUpdate(update, { telegram, outputDir, logger });
      offset = update.update_id + 1;
      saveOffset(offset);
    }

    // After each batch, stitch any complete part sets into their originals.
    // eslint-disable-next-line no-await-in-loop
    await scanOnce({ dir: outputDir, stableForMs, logger });
  }
};

run().catch((err) => {
  logger.error(`Fatal: ${err.stack || err.message}`);
  process.exit(1);
});
