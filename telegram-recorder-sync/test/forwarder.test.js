// Tests for the external receiver forwarder. Stubs global.fetch.

const assert = require('node:assert');
const test = require('node:test');

const { createForwarder } = require('../src/forwarder');

const noopLogger = { info: () => {}, warn: () => {}, error: () => {} };

test('forwarder is disabled and a no-op when no url is set', async () => {
  const forwarder = createForwarder({ url: '', logger: noopLogger });
  assert.strictEqual(forwarder.enabled, false);
  await forwarder.forward({ document: { file_id: 'X' } }); // must not throw
});

test('forwarder POSTs the result wrapped as a Telegram response', async () => {
  const original = global.fetch;
  const calls = [];
  global.fetch = async (url, opts) => {
    calls.push({ url, opts });
    return { ok: true, status: 200 };
  };
  try {
    const forwarder = createForwarder({ url: 'http://host/path?token=abc', logger: noopLogger });
    await forwarder.forward({ document: { file_id: 'FID', file_name: 'a.m4a' } });

    assert.strictEqual(calls.length, 1);
    assert.strictEqual(calls[0].url, 'http://host/path?token=abc');
    assert.strictEqual(calls[0].opts.method, 'POST');
    assert.strictEqual(calls[0].opts.headers['Content-Type'], 'application/json');
    const body = JSON.parse(calls[0].opts.body);
    assert.strictEqual(body.ok, true);
    assert.strictEqual(body.result.document.file_id, 'FID');
    assert.strictEqual(body.result.document.file_name, 'a.m4a');
  } finally {
    global.fetch = original;
  }
});

test('forwarder retries then gives up without throwing on persistent failure', async () => {
  const original = global.fetch;
  let attempts = 0;
  global.fetch = async () => {
    attempts += 1;
    return { ok: false, status: 502 };
  };
  try {
    // maxRetries 1 keeps the test fast (1 try + 1 retry = 2 attempts).
    const forwarder = createForwarder({
      url: 'http://host/x', logger: noopLogger, maxRetries: 1, timeoutMs: 1000,
    });
    await forwarder.forward({ document: { file_id: 'Z' } }); // must resolve, not throw
    assert.strictEqual(attempts, 2);
  } finally {
    global.fetch = original;
  }
});
