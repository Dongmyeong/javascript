// Uploads a file directly to an external receiver in binary chunks, bypassing
// Telegram's 20MB bot-download limit. Each chunk is POSTed as an octet-stream
// with query params: upload_id (same for all chunks of one file), filename,
// index (0-based) and total. The receiver reassembles once all chunks arrive.

const crypto = require('crypto');
const path = require('path');
const { fileToBlob } = require('./fileblob');

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

const createChunkUploader = ({
  url, chunkBytes, logger, timeoutMs = 120000, maxRetries = 4,
}) => {
  const enabled = Boolean(url);

  const postChunk = async (blob, {
    uploadId, filename, index, total,
  }) => {
    const target = new URL(url); // preserves the existing ?token=... param
    target.searchParams.set('upload_id', uploadId);
    target.searchParams.set('filename', filename);
    target.searchParams.set('index', String(index));
    target.searchParams.set('total', String(total));

    let attempt = 0;
    for (;;) {
      attempt += 1;
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetch(target, {
          method: 'POST',
          headers: { 'Content-Type': 'application/octet-stream' },
          body: blob,
          signal: controller.signal,
        });
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        return;
      } catch (err) {
        if (attempt > maxRetries) {
          throw err;
        }
        await sleep(2 ** attempt * 1000);
      } finally {
        clearTimeout(timer);
      }
    }
  };

  // Upload a whole file as chunks. Returns the number of chunks sent.
  const upload = async (filePath, chunkSize = chunkBytes) => {
    const blob = await fileToBlob(filePath);
    const filename = path.basename(filePath);
    const total = Math.max(1, Math.ceil(blob.size / chunkSize));
    const uploadId = crypto.randomUUID();

    for (let i = 0; i < total; i += 1) {
      const start = i * chunkSize;
      const slice = blob.slice(start, Math.min(blob.size, start + chunkSize));
      // eslint-disable-next-line no-await-in-loop -- chunks must arrive in order
      await postChunk(slice, {
        uploadId, filename, index: i, total,
      });
      logger.info(`Uploaded chunk ${i + 1}/${total} of ${filename}`);
    }
    return total;
  };

  return { enabled, upload };
};

module.exports = { createChunkUploader };
