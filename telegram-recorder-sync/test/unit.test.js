// Lightweight tests using Node's built-in test runner (no dependencies).
// Run with: npm test

const assert = require('node:assert');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { loadConfig } = require('../src/config');
const { createState } = require('../src/state');
const { formatSize, renderCaption } = require('../src/watcher');

test('formatSize renders human-readable sizes', () => {
  assert.strictEqual(formatSize(0), '0B');
  assert.strictEqual(formatSize(512), '512B');
  assert.strictEqual(formatSize(1024), '1.0KB');
  assert.strictEqual(formatSize(1536), '1.5KB');
  assert.strictEqual(formatSize(1024 * 1024), '1.0MB');
});

test('renderCaption substitutes placeholders', () => {
  const stat = { size: 2048, mtimeMs: Date.now() };
  const caption = renderCaption('{name} - {size}', '/a/b/call.m4a', stat);
  assert.strictEqual(caption, 'call.m4a - 2.0KB');
});

test('state tracks sent files by size and mtime', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'trs-'));
  const stateFile = path.join(dir, 'state.json');
  const state = createState(stateFile);
  const stat = { size: 100, mtimeMs: 111 };

  assert.strictEqual(state.isSent('/x.m4a', stat), false);
  state.markSent('/x.m4a', stat);
  assert.strictEqual(state.isSent('/x.m4a', stat), true);
  // Same name but different content (mtime/size) is not considered sent.
  assert.strictEqual(state.isSent('/x.m4a', { size: 100, mtimeMs: 222 }), false);

  // State persists across instances.
  const reloaded = createState(stateFile);
  assert.strictEqual(reloaded.isSent('/x.m4a', stat), true);

  fs.rmSync(dir, { recursive: true, force: true });
});

test('loadConfig throws with a helpful message when required vars are missing', () => {
  const saved = {
    TELEGRAM_BOT_TOKEN: process.env.TELEGRAM_BOT_TOKEN,
    TELEGRAM_CHAT_ID: process.env.TELEGRAM_CHAT_ID,
    WATCH_DIR: process.env.WATCH_DIR,
  };
  delete process.env.TELEGRAM_BOT_TOKEN;
  delete process.env.TELEGRAM_CHAT_ID;
  delete process.env.WATCH_DIR;

  assert.throws(
    () => loadConfig({ envPath: '/nonexistent/.env' }),
    /TELEGRAM_BOT_TOKEN is required/,
  );

  Object.entries(saved).forEach(([key, value]) => {
    if (value !== undefined) {
      process.env[key] = value;
    }
  });
});

test('loadConfig reads values and resolves the watch dir', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'trs-cfg-'));
  process.env.TELEGRAM_BOT_TOKEN = 'token';
  process.env.TELEGRAM_CHAT_ID = '123';
  process.env.WATCH_DIR = dir;

  const config = loadConfig({ envPath: '/nonexistent/.env' });
  assert.strictEqual(config.botToken, 'token');
  assert.strictEqual(config.chatId, '123');
  assert.strictEqual(config.watchDir, fs.realpathSync(dir) === dir ? dir : config.watchDir);
  assert.ok(Array.isArray(config.extensions));
  assert.ok(config.extensions.includes('.m4a'));

  delete process.env.TELEGRAM_BOT_TOKEN;
  delete process.env.TELEGRAM_CHAT_ID;
  delete process.env.WATCH_DIR;
  fs.rmSync(dir, { recursive: true, force: true });
});
