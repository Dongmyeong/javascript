// Tests for the Telegram pull receiver. No network: telegram client is stubbed.

const assert = require('node:assert');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { pickFile, processUpdate } = require('../lib/receiver');

const noopLogger = { info: () => {}, error: () => {} };

const stubTelegram = (onDownload) => ({
  getFile: async (fileId) => ({ file_path: `documents/${fileId}` }),
  downloadFile: async (filePath, dest) => {
    fs.writeFileSync(dest, `DATA:${filePath}`);
    if (onDownload) {
      onDownload(filePath, dest);
    }
  },
});

test('pickFile prefers document, falls back to audio/voice', () => {
  assert.strictEqual(pickFile({ document: { file_id: 'D', file_name: 'a.m4a' } }).fileId, 'D');
  assert.strictEqual(pickFile({ audio: { file_id: 'A', file_unique_id: 'u' } }).fileName, 'u.m4a');
  assert.strictEqual(pickFile({ voice: { file_id: 'V', file_unique_id: 'w' } }).fileName, 'w.ogg');
  assert.strictEqual(pickFile({ text: 'hi' }), null);
});

test('processUpdate downloads a document preserving the original filename', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'recv-'));
  const telegram = stubTelegram();
  const update = {
    update_id: 7,
    message: { document: { file_id: 'F1', file_name: '미팅.m4a.part1of3', file_size: 10 } },
  };
  const dest = await processUpdate(update, { telegram, outputDir: dir, logger: noopLogger });
  assert.strictEqual(path.basename(dest), '미팅.m4a.part1of3'); // name kept -> reassembler can match
  assert.ok(fs.existsSync(dest));
  fs.rmSync(dir, { recursive: true, force: true });
});

test('processUpdate skips a file that already exists', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'recv-'));
  fs.writeFileSync(path.join(dir, 'dup.m4a'), 'old');
  let downloaded = false;
  const telegram = stubTelegram(() => { downloaded = true; });
  const update = { update_id: 1, message: { document: { file_id: 'X', file_name: 'dup.m4a' } } };
  await processUpdate(update, { telegram, outputDir: dir, logger: noopLogger });
  assert.strictEqual(downloaded, false);
  assert.strictEqual(fs.readFileSync(path.join(dir, 'dup.m4a'), 'utf8'), 'old');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('processUpdate ignores updates without a file and never throws on download error', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'recv-'));
  assert.strictEqual(
    await processUpdate({ update_id: 2, message: { text: 'hello' } }, { telegram: stubTelegram(), outputDir: dir, logger: noopLogger }),
    null,
  );
  const failing = {
    getFile: async () => { throw new Error('file is too big'); },
    downloadFile: async () => {},
  };
  const res = await processUpdate(
    { update_id: 3, message: { document: { file_id: 'B', file_name: 'big.m4a' } } },
    { telegram: failing, outputDir: dir, logger: noopLogger },
  );
  assert.strictEqual(res, null); // handled, not thrown
  fs.rmSync(dir, { recursive: true, force: true });
});
