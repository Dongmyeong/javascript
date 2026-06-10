// Thin wrapper over the Telegram Bot API using the built-in global `fetch`,
// `FormData` and `Blob` (Node 18+). No external HTTP library required.

const path = require('path');
const { fileToBlob } = require('./fileblob');

const API_BASE = 'https://api.telegram.org';

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

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

  // Retry transient failures (network/abort, 429, 5xx) with exponential backoff.
  const withRetry = async (fn) => {
    let attempt = 0;
    for (;;) {
      attempt += 1;
      try {
        return await fn();
      } catch (err) {
        const retryable = !err.status
          || err.status === 429
          || err.status >= 500
          || err.name === 'AbortError';
        if (!retryable || attempt > maxRetries) {
          throw err;
        }
        const backoff = err.retryAfter ? err.retryAfter * 1000 : 2 ** attempt * 1000;
        await sleep(backoff);
      }
    }
  };

  // Verify the token and return the bot's own info.
  const getMe = () => withRetry(() => callApi('getMe', undefined));

  const sendBlob = (blob, filename, { caption, asAudio = false } = {}) => {
    const method = asAudio ? 'sendAudio' : 'sendDocument';
    const field = asAudio ? 'audio' : 'document';
    return withRetry(() => {
      const form = new FormData();
      form.append('chat_id', String(chatId));
      if (caption) {
        form.append('caption', caption);
      }
      form.append(field, blob, filename);
      return callApi(method, form);
    });
  };

  const sendFile = async (filePath, options = {}) => {
    const blob = await fileToBlob(filePath);
    return sendBlob(blob, path.basename(filePath), options);
  };

  const sendMessage = (text) => withRetry(() => {
    const form = new FormData();
    form.append('chat_id', String(chatId));
    form.append('text', text);
    form.append('disable_web_page_preview', 'true');
    return callApi('sendMessage', form);
  });

  return {
    getMe, sendBlob, sendFile, sendMessage,
  };
};

module.exports = { createTelegramClient };
