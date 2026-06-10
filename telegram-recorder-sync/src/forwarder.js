// Optionally forwards each Telegram send response to an external endpoint
// (e.g. a Mac mini receiver) so it can pull the file via its file_id. The body
// matches the Telegram Bot API response shape: { ok: true, result: {...} }.
// Forwarding is best-effort: a failure is logged but never blocks syncing,
// because the file already lives safely in Telegram.

const sleep = (ms) => new Promise((resolve) => { setTimeout(resolve, ms); });

const createForwarder = ({
  url, logger, timeoutMs = 30000, maxRetries = 3,
}) => {
  const enabled = Boolean(url);

  const forward = async (result) => {
    if (!enabled || !result) {
      return;
    }
    const body = JSON.stringify({ ok: true, result });

    let attempt = 0;
    for (;;) {
      attempt += 1;
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), timeoutMs);
      try {
        const response = await fetch(url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body,
          signal: controller.signal,
        });
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        return;
      } catch (err) {
        if (attempt > maxRetries) {
          logger.warn(`Forward to receiver failed (file is still in Telegram): ${err.message}`);
          return;
        }
        await sleep(2 ** attempt * 1000);
      } finally {
        clearTimeout(timer);
      }
    }
  };

  return { enabled, forward };
};

module.exports = { createForwarder };
