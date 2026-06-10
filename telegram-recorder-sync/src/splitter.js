// Splits a file into byte-range parts so each one fits under Telegram's upload
// limit. Parts are plain binary slices (like the `split` command); reassemble
// them with `cat`. Uses Blob.slice so no part is fully read into memory when
// `fs.openAsBlob` is available.

const path = require('path');
const { fileToBlob } = require('./fileblob');

const pad = (value, width) => String(value).padStart(width, '0');

// Build the list of parts for a file. Returns `{ size, total, parts }` where
// each part has `{ index, total, name, blob }`. When the file already fits in a
// single part, `total` is 1 and the single part keeps the original name.
const buildParts = async (filePath, maxBytes) => {
  const blob = await fileToBlob(filePath);
  const base = path.basename(filePath);
  const total = Math.max(1, Math.ceil(blob.size / maxBytes));

  if (total === 1) {
    return {
      size: blob.size,
      total: 1,
      parts: [{
        index: 1, total: 1, name: base, blob,
      }],
    };
  }

  const width = String(total).length;
  const parts = [];
  for (let i = 0; i < total; i += 1) {
    const start = i * maxBytes;
    const end = Math.min(blob.size, start + maxBytes);
    parts.push({
      index: i + 1,
      total,
      name: `${base}.part${pad(i + 1, width)}of${pad(total, width)}`,
      blob: blob.slice(start, end),
    });
  }
  return { size: blob.size, total, parts };
};

// Shell command that reassembles the parts back into the original file.
const reconstructHint = (filePath, total) => {
  const base = path.basename(filePath);
  const width = String(total).length;
  return `cat "${base}".part*of${pad(total, width)} > "${base}"`;
};

module.exports = { buildParts, reconstructHint };
