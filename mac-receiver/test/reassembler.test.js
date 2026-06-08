// Tests for the part reassembler. Node built-in test runner, no dependencies.
// Run: node --test

const assert = require('node:assert');
const test = require('node:test');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const {
  parsePart, groupParts, isComplete, reassembleGroup, scanOnce,
} = require('../lib/reassembler');

const noopLogger = { info: () => {}, error: () => {} };

test('parsePart understands name.m4a.partNofM (and ignores others)', () => {
  assert.deepStrictEqual(
    parsePart('미팅 녹음.m4a.part2of3'),
    { base: '미팅 녹음.m4a', index: 2, total: 3 },
  );
  assert.deepStrictEqual(parsePart('call.m4a.part01of12'), { base: 'call.m4a', index: 1, total: 12 });
  assert.strictEqual(parsePart('call.m4a'), null);
  assert.strictEqual(parsePart('call.m4a.reassembling'), null);
});

test('groupParts groups by base name and total; isComplete checks all parts', () => {
  const groups = groupParts([
    '/in/a.m4a.part1of2',
    '/in/a.m4a.part2of2',
    '/in/b.m4a.part1of3',
  ]);
  const a = groups.find((g) => g.base === 'a.m4a');
  const b = groups.find((g) => g.base === 'b.m4a');
  assert.strictEqual(a.total, 2);
  assert.strictEqual(isComplete(a), true);
  assert.strictEqual(isComplete(b), false); // only 1 of 3
});

test('reassembleGroup concatenates in order and removes parts', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'reasm-'));
  const original = Buffer.from(Array.from({ length: 2500 }, (_, i) => i % 256));
  // Split into 3 deterministic parts.
  const slices = [original.subarray(0, 1000), original.subarray(1000, 2000), original.subarray(2000)];
  // Write parts out of order to prove sorting works.
  fs.writeFileSync(path.join(dir, 'rec.m4a.part3of3'), slices[2]);
  fs.writeFileSync(path.join(dir, 'rec.m4a.part1of3'), slices[0]);
  fs.writeFileSync(path.join(dir, 'rec.m4a.part2of3'), slices[1]);

  const group = {
    base: 'rec.m4a',
    total: 3,
    parts: [
      { path: path.join(dir, 'rec.m4a.part3of3'), index: 3 },
      { path: path.join(dir, 'rec.m4a.part1of3'), index: 1 },
      { path: path.join(dir, 'rec.m4a.part2of3'), index: 2 },
    ],
  };
  const out = await reassembleGroup(dir, group);
  assert.ok(fs.readFileSync(out).equals(original));
  // Parts are gone.
  assert.strictEqual(fs.existsSync(path.join(dir, 'rec.m4a.part1of3')), false);
  assert.strictEqual(fs.existsSync(path.join(dir, 'rec.m4a.part3of3')), false);

  fs.rmSync(dir, { recursive: true, force: true });
});

test('scanOnce reassembles only complete, stable groups', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'reasm-scan-'));
  const original = Buffer.alloc(1500, 9);
  fs.writeFileSync(path.join(dir, 'done.m4a.part1of2'), original.subarray(0, 1000));
  fs.writeFileSync(path.join(dir, 'done.m4a.part2of2'), original.subarray(1000));
  // Incomplete group: only one part.
  fs.writeFileSync(path.join(dir, 'wait.m4a.part1of2'), Buffer.alloc(10, 1));
  // Make the complete group's parts look "old" (download finished).
  const old = new Date(Date.now() - 60000);
  fs.utimesSync(path.join(dir, 'done.m4a.part1of2'), old, old);
  fs.utimesSync(path.join(dir, 'done.m4a.part2of2'), old, old);

  await scanOnce({ dir, stableForMs: 10000, logger: noopLogger });

  // Complete + stable -> reassembled, parts removed.
  assert.ok(fs.existsSync(path.join(dir, 'done.m4a')));
  assert.ok(fs.readFileSync(path.join(dir, 'done.m4a')).equals(original));
  assert.strictEqual(fs.existsSync(path.join(dir, 'done.m4a.part1of2')), false);
  // Incomplete -> left untouched.
  assert.ok(fs.existsSync(path.join(dir, 'wait.m4a.part1of2')));
  assert.strictEqual(fs.existsSync(path.join(dir, 'wait.m4a')), false);

  fs.rmSync(dir, { recursive: true, force: true });
});
