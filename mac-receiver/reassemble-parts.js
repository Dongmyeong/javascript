#!/usr/bin/env node

// Watches the inbound folder on the Mac mini and stitches split part files
// (name.m4a.partNofM) back into the original file, so the transcription
// pipeline sees whole recordings. Zero dependencies; needs Node.js 18+.
//
// Usage:
//   node reassemble-parts.js [inboundDir]
//   INBOUND_DIR=/path node reassemble-parts.js

const { scanOnce } = require('./lib/reassembler');

const timestamp = () => new Date().toISOString();
const logger = {
  info: (...a) => console.log(timestamp(), '[INFO]', ...a),
  error: (...a) => console.error(timestamp(), '[ERROR]', ...a),
};

const dir = process.argv[2]
  || process.env.INBOUND_DIR
  || '/Users/donoh/.openclaw/media/inbound';
const pollIntervalMs = Number.parseInt(process.env.POLL_INTERVAL_MS, 10) || 5000;
const stableForMs = Number.parseInt(process.env.STABLE_FOR_MS, 10) || 10000;

let stopped = false;

const loop = async () => {
  if (stopped) {
    return;
  }
  try {
    await scanOnce({ dir, stableForMs, logger });
  } catch (err) {
    logger.error(`Scan failed: ${err.message}`);
  }
  if (!stopped) {
    setTimeout(loop, pollIntervalMs);
  }
};

const shutdown = (signal) => {
  logger.info(`Received ${signal}, stopping...`);
  stopped = true;
  process.exit(0);
};
process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));

logger.info(`Reassembling parts in ${dir} (poll ${pollIntervalMs}ms, stable ${stableForMs}ms)`);
loop();
