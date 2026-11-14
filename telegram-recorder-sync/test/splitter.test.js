// Tests for file splitting and reconstruction hints.

const assert = require('node:assert');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { buildParts, reconstructHint } = require('../src/splitter');

test('buildParts returns a single part for small files', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'split-'));
  const file = path.join(dir, 'call.m4a');
  fs.writeFileSync(file, Buffer.alloc(500, 7));

  const { total, parts } = await buildParts(file, 1000);
  assert.strictEqual(total, 1);
  assert.strictEqual(parts.length, 1);
  assert.strictEqual(parts[0].name, 'call.m4a');

  fs.rmSync(dir, { recursive: true, force: true });
});

test('buildParts splits large files into reassemblable parts', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'split-'));
  const file = path.join(dir, 'long.m4a');
  // 2500 bytes of distinct content so reassembly order matters.
  const original = Buffer.from(Array.from({ length: 2500 }, (_, i) => i % 256));
  fs.writeFileSync(file, original);

  const { total, parts } = await buildParts(file, 1000);
  assert.strictEqual(total, 3);
  assert.deepStrictEqual(parts.map((p) => p.name), [
    'long.m4a.part1of3',
    'long.m4a.part2of3',
    'long.m4a.part3of3',
  ]);
  assert.deepStrictEqual(parts.map((p) => p.blob.size), [1000, 1000, 500]);

  // Reassemble the blobs and confirm we get the original bytes back.
  const chunks = [];
  for (const part of parts) {
    chunks.push(Buffer.from(await part.blob.arrayBuffer()));
  }
  assert.ok(Buffer.concat(chunks).equals(original));

  fs.rmSync(dir, { recursive: true, force: true });
});

test('reconstructHint builds a cat command matching the part names', () => {
  assert.strictEqual(
    reconstructHint('/recordings/long.m4a', 3),
    'cat "long.m4a".part*of3 > "long.m4a"',
  );
});
