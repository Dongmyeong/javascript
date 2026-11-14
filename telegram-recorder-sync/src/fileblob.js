// Returns a Blob backed by a file. `fs.openAsBlob` (Node 20+) avoids loading the
// whole file into memory and supports lazy `.slice()`; otherwise we fall back to
// reading the file into a buffer.

const fs = require('fs');

const fileToBlob = async (filePath) => {
  if (typeof fs.openAsBlob === 'function') {
    return fs.openAsBlob(filePath);
  }
  const buffer = await fs.promises.readFile(filePath);
  return new Blob([buffer]);
};

module.exports = { fileToBlob };
