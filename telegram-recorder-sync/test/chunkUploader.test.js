// Tests for direct binary chunk upload to an external receiver. Stubs fetch.

const assert = require('node:assert');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { createChunkUploader } = require('../src/chunkUploader');

const noopLogger = { info: () => {}, warn: () => {}, error: () => {} };

test('chunkUploader is disabled when no url is set', () => {
  const uploader = createChunkUploader({ url: '', chunkBytes: 1000, logger: noopLogger });
  assert.strictEqual(uploader.enabled, false);
});

test('chunkUploader posts ordered chunks with shared upload_id and token', async () => {
  const original = global.fetch;
  const calls = [];
  global.fetch = async (target, opts) => {
    calls.push({ url: target.toString(), opts });
    return { ok: true, status: 200 };
  };

  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'chunk-'));
  const file = path.join(dir, '통화 녹음.m4a'); // spaces + non-ascii on purpose
  fs.writeFileSync(file, Buffer.alloc(2500, 5));

  try {
    const uploader = createChunkUploader({
      url: 'http://host/upload-chunk?token=SECRET',
      chunkBytes: 1000,
      logger: noopLogger,
    });
    const total = await uploader.upload(file);

    assert.strictEqual(total, 3);
    assert.strictEqual(calls.length, 3);

    const urls = calls.map((c) => new URL(c.url));
    // token preserved, indices ordered, total correct
    assert.strictEqual(urls[0].searchParams.get('token'), 'SECRET');
    assert.deepStrictEqual(urls.map((u) => u.searchParams.get('index')), ['0', '1', '2']);
    urls.forEach((u) => assert.strictEqual(u.searchParams.get('total'), '3'));
    // filename round-trips through URL encoding
    assert.strictEqual(urls[0].searchParams.get('filename'), '통화 녹음.m4a');
    // one shared upload_id
    const ids = new Set(urls.map((u) => u.searchParams.get('upload_id')));
    assert.strictEqual(ids.size, 1);
    // chunk body sizes
    assert.strictEqual(calls[0].opts.body.size, 1000);
    assert.strictEqual(calls[2].opts.body.size, 500);
    assert.strictEqual(calls[0].opts.method, 'POST');
  } finally {
    global.fetch = original;
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
