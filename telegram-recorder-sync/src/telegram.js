// Thin wrapper over the Telegram Bot API using the built-in global `fetch`,
// `FormData` and `Blob` (Node 18+). No external HTTP library required.

const fs = require('fs');
const path = require('path');

const API_BASE = 'https://api.telegram.org';

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

// Build a Blob backed by a file. `fs.openAsBlob` (Node 20+) avoids loading the
// whole file into memory; otherwise we fall back to reading it.
const fileToBlob = async (filePath) => {
  if (typeof fs.openAsBlob === 'function') {
    return fs.openAsBlob(filePath);
  }
  const buffer = await fs.promises.readFile(filePath);
  return new Blob([buffer]);
};

const createTelegramClient = ({
  botToken,
  chatId,
  requestTimeoutMs = 120000,
  maxRetries = 4,
}) => {
  const callApi = async (method, body) => {
    const url = `${API_BASE}/bot${botToken}/${method}`;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), requestTimeoutMs);
    try {
      const response = await fetch(url, {
        method: 'POST',
        body,
        signal: controller.signal,
      });
      const data = await response.json().catch(() => ({}));
      if (!response.ok || !data.ok) {
        const description = data.description || `HTTP ${response.status}`;
        const error = new Error(`Telegram API ${method} failed: ${description}`);
        error.status = response.status;
        error.retryAfter = data.parameters && data.parameters.retry_after;
        throw error;
      }
      return data.result;
    } finally {
      clearTimeout(timer);
    }
  };

  // Verify the token and return the bot's own info.
  const getMe = () => callApi('getMe', undefined);

  const sendFile = async (filePath, { caption, asAudio = false } = {}) => {
    const method = asAudio ? 'sendAudio' : 'sendDocument';
    const field = asAudio ? 'audio' : 'document';

    let attempt = 0;
    // Retry transient failures (network, 429, 5xx) with exponential backoff.
    for (;;) {
      attempt += 1;
      try {
        const form = new FormData();
        form.append('chat_id', String(chatId));
        if (caption) {
          form.append('caption', caption);
        }
        const blob = await fileToBlob(filePath);
        form.append(field, blob, path.basename(filePath));
        return await callApi(method, form);
      } catch (err) {
        const retryable = !err.status || err.status === 429 || err.status >= 500;
        if (!retryable || attempt > maxRetries) {
          throw err;
        }
        const backoff = err.retryAfter
          ? err.retryAfter * 1000
          : 2 ** attempt * 1000;
        await sleep(backoff);
      }
    }
  };

  return { getMe, sendFile };
};

module.exports = { createTelegramClient };
