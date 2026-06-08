// Minimal Telegram Bot API client for *pulling* files: getUpdates (long poll),
// getFile, and downloadFile. Uses global fetch (Node 18+); no dependencies.
// Note: the Bot API can only download files up to 20MB via getFile.

const fs = require('fs');
const { Readable } = require('stream');

const API = 'https://api.telegram.org';

const createTelegram = ({ botToken }) => {
  const call = async (method, params, { timeoutMs = 30000 } = {}) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(`${API}/bot${botToken}/${method}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(params || {}),
        signal: controller.signal,
      });
      const data = await response.json().catch(() => ({}));
      if (!data.ok) {
        throw new Error(`${method} failed: ${data.description || `HTTP ${response.status}`}`);
      }
      return data.result;
    } finally {
      clearTimeout(timer);
    }
  };

  const getMe = () => call('getMe', {});

  // Long-poll for updates. `timeoutSec` is how long Telegram holds the request.
  const getUpdates = (offset, timeoutSec = 30) => call(
    'getUpdates',
    { offset, timeout: timeoutSec, allowed_updates: ['message', 'channel_post'] },
    { timeoutMs: (timeoutSec + 15) * 1000 },
  );

  const getFile = (fileId) => call('getFile', { file_id: fileId });

  // Stream a file to disk via a temp file, then atomically rename.
  const downloadFile = async (filePath, destPath, { timeoutMs = 600000 } = {}) => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const response = await fetch(`${API}/file/bot${botToken}/${filePath}`, {
        signal: controller.signal,
      });
      if (!response.ok) {
        throw new Error(`download HTTP ${response.status}`);
      }
      const tmp = `${destPath}.downloading`;
      const out = fs.createWriteStream(tmp);
      await new Promise((resolve, reject) => {
        const input = Readable.fromWeb(response.body);
        input.on('error', reject);
        out.on('error', reject);
        out.on('finish', resolve);
        input.pipe(out);
      });
      fs.renameSync(tmp, destPath);
    } finally {
      clearTimeout(timer);
    }
  };

  return {
    call, getMe, getUpdates, getFile, downloadFile,
  };
};

module.exports = { createTelegram };
