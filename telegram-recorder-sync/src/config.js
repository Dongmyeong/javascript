// Loads configuration from environment variables, with a tiny built-in `.env`
// parser so no external `dotenv` dependency is required.

const fs = require('fs');
const path = require('path');

const DEFAULT_EXTENSIONS = ['.m4a', '.mp3', '.amr', '.aac', '.wav', '.ogg', '.opus', '.3gp'];

// Parse a `.env` file into `process.env` without overwriting existing values.
const loadDotEnv = (envPath) => {
  if (!fs.existsSync(envPath)) {
    return;
  }
  const contents = fs.readFileSync(envPath, 'utf8');
  contents.split(/\r?\n/).forEach((rawLine) => {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) {
      return;
    }
    const eq = line.indexOf('=');
    if (eq === -1) {
      return;
    }
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    // Strip optional surrounding quotes.
    if (
      (value.startsWith('"') && value.endsWith('"'))
      || (value.startsWith("'") && value.endsWith("'"))
    ) {
      value = value.slice(1, -1);
    }
    if (key && !(key in process.env)) {
      process.env[key] = value;
    }
  });
};

const parseBool = (value, fallback) => {
  if (value === undefined) {
    return fallback;
  }
  return ['1', 'true', 'yes', 'on'].includes(String(value).toLowerCase());
};

const parseInteger = (value, fallback) => {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
};

const parseExtensions = (value) => {
  if (!value) {
    return DEFAULT_EXTENSIONS;
  }
  return value
    .split(',')
    .map((ext) => ext.trim().toLowerCase())
    .filter(Boolean)
    .map((ext) => (ext.startsWith('.') ? ext : `.${ext}`));
};

const loadConfig = ({ envPath = path.resolve(process.cwd(), '.env') } = {}) => {
  loadDotEnv(envPath);

  const errors = [];
  const botToken = process.env.TELEGRAM_BOT_TOKEN;
  const chatId = process.env.TELEGRAM_CHAT_ID;
  const watchDir = process.env.WATCH_DIR;

  if (!botToken) {
    errors.push('TELEGRAM_BOT_TOKEN is required');
  }
  if (!chatId) {
    errors.push('TELEGRAM_CHAT_ID is required');
  }
  if (!watchDir) {
    errors.push('WATCH_DIR is required');
  } else if (!fs.existsSync(watchDir)) {
    errors.push(`WATCH_DIR does not exist: ${watchDir}`);
  }

  if (errors.length > 0) {
    const error = new Error(`Invalid configuration:\n  - ${errors.join('\n  - ')}`);
    error.code = 'ECONFIG';
    throw error;
  }

  return {
    botToken,
    chatId,
    watchDir: path.resolve(watchDir),
    extensions: parseExtensions(process.env.WATCH_EXTENSIONS),
    recursive: parseBool(process.env.WATCH_RECURSIVE, false),
    pollIntervalMs: parseInteger(process.env.POLL_INTERVAL_MS, 5000),
    // A file must stay unchanged (size + mtime) for this long before we send it,
    // so we never upload a recording that is still being written.
    stableForMs: parseInteger(process.env.STABLE_FOR_MS, 10000),
    sendAsAudio: parseBool(process.env.SEND_AS_AUDIO, false),
    deleteAfterSend: parseBool(process.env.DELETE_AFTER_SEND, false),
    // Telegram bot uploads are limited to 50MB; default to a safe 49MB so
    // larger recordings are split into parts automatically.
    maxPartBytes: parseInteger(process.env.MAX_PART_BYTES, 49 * 1024 * 1024),
    // Telegram text notifications.
    notifySummary: parseBool(process.env.NOTIFY_SUMMARY, true),
    notifyOnSend: parseBool(process.env.NOTIFY_ON_SEND, false),
    notifySplitInstructions: parseBool(process.env.NOTIFY_SPLIT_INSTRUCTIONS, true),
    captionTemplate: process.env.CAPTION_TEMPLATE || '🎙 {name} ({size})',
    stateFile: process.env.STATE_FILE
      ? path.resolve(process.env.STATE_FILE)
      : path.resolve(process.cwd(), '.sent-state.json'),
    requestTimeoutMs: parseInteger(process.env.REQUEST_TIMEOUT_MS, 120000),
    maxRetries: parseInteger(process.env.MAX_RETRIES, 4),
  };
};

module.exports = { loadConfig, DEFAULT_EXTENSIONS };
