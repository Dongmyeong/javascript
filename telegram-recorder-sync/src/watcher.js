// Polls the watch directory on an interval, finds new/finished recording files
// and sends them to Telegram. Polling (rather than fs.watch) is used because it
// is far more reliable on Android/Termux and across network/SD-card storage.

const fs = require('fs');
const path = require('path');

const formatSize = (bytes) => {
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)}${units[unit]}`;
};

const renderCaption = (template, filePath, stat) => template
  .replace(/\{name\}/g, path.basename(filePath))
  .replace(/\{size\}/g, formatSize(stat.size))
  .replace(/\{date\}/g, new Date(stat.mtimeMs).toLocaleString());

// Recursively list files under `dir` (or just the top level when not recursive).
const listFiles = (dir, recursive) => {
  const out = [];
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (err) {
    return out;
  }
  entries.forEach((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (recursive) {
        out.push(...listFiles(full, recursive));
      }
    } else if (entry.isFile()) {
      out.push(full);
    }
  });
  return out;
};

const createWatcher = ({
  config, telegram, state, logger,
}) => {
  const hasWantedExtension = (filePath) => config.extensions
    .includes(path.extname(filePath).toLowerCase());

  // A file is "ready" once its size and mtime have been stable long enough,
  // which means recording has finished and it is safe to upload.
  const isStable = (stat) => Date.now() - stat.mtimeMs >= config.stableForMs;

  const processFile = async (filePath) => {
    let stat;
    try {
      stat = fs.statSync(filePath);
    } catch (err) {
      return; // File vanished between listing and stat.
    }

    if (!hasWantedExtension(filePath) || stat.size === 0) {
      return;
    }
    if (state.isSent(filePath, stat)) {
      return;
    }
    if (!isStable(stat)) {
      return; // Still being written; try again next tick.
    }

    const caption = renderCaption(config.captionTemplate, filePath, stat);
    logger.info(`Sending: ${path.basename(filePath)} (${formatSize(stat.size)})`);
    await telegram.sendFile(filePath, { caption, asAudio: config.sendAsAudio });
    state.markSent(filePath, stat);
    logger.info(`Sent: ${path.basename(filePath)}`);

    if (config.deleteAfterSend) {
      try {
        fs.unlinkSync(filePath);
        logger.info(`Deleted after send: ${path.basename(filePath)}`);
      } catch (err) {
        logger.warn(`Could not delete ${filePath}: ${err.message}`);
      }
    }
  };

  const tick = async () => {
    const files = listFiles(config.watchDir, config.recursive);
    // Process sequentially to avoid hammering the API / running out of memory.
    for (const filePath of files) {
      try {
        await processFile(filePath);
      } catch (err) {
        logger.error(`Failed to send ${path.basename(filePath)}: ${err.message}`);
      }
    }
  };

  let timer = null;
  let stopped = false;

  const start = async () => {
    logger.info(`Watching ${config.watchDir} (recursive=${config.recursive}) `
      + `for ${config.extensions.join(', ')}`);
    const loop = async () => {
      if (stopped) {
        return;
      }
      await tick();
      if (!stopped) {
        timer = setTimeout(loop, config.pollIntervalMs);
      }
    };
    await loop();
  };

  const stop = () => {
    stopped = true;
    if (timer) {
      clearTimeout(timer);
    }
  };

  return {
    start, stop, tick, formatSize: () => formatSize,
  };
};

module.exports = { createWatcher, formatSize, renderCaption };
