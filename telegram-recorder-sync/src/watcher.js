// Polls the watch directory on an interval, finds new/finished recording files
// and sends them to Telegram. Polling (rather than fs.watch) is used because it
// is far more reliable on Android/Termux and across network/SD-card storage.
// Files larger than the Telegram upload limit are split into parts.

const fs = require('fs');
const path = require('path');

const { formatSize, renderCaption } = require('./format');
const { buildParts, reconstructHint } = require('./splitter');

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
  config, telegram, state, logger, notifier,
}) => {
  const hasWantedExtension = (filePath) => config.extensions
    .includes(path.extname(filePath).toLowerCase());

  // A file is "ready" once its size and mtime have been stable long enough,
  // which means recording has finished and it is safe to upload.
  const isStable = (stat) => Date.now() - stat.mtimeMs >= config.stableForMs;

  // Send a file, automatically splitting it when it exceeds the size limit.
  // Returns the number of parts that were sent.
  const sendWithSplit = async (filePath, baseCaption) => {
    const { total, parts } = await buildParts(filePath, config.maxPartBytes);

    if (total === 1) {
      await telegram.sendFile(filePath, {
        caption: baseCaption,
        asAudio: config.sendAsAudio,
      });
      return 1;
    }

    logger.info(`Splitting ${path.basename(filePath)} into ${total} parts`);
    for (const part of parts) {
      const caption = `${path.basename(filePath)} (part ${part.index}/${part.total}, `
        + `${formatSize(part.blob.size)})`;
      // Parts are raw binary slices, so always send them as documents.
      await telegram.sendBlob(part.blob, part.name, { caption, asAudio: false });
      logger.info(`Sent part ${part.index}/${part.total} of ${path.basename(filePath)}`);
    }
    return total;
  };

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
    const parts = await sendWithSplit(filePath, caption);
    state.markSent(filePath, stat);
    logger.info(`Sent: ${path.basename(filePath)}`);

    if (notifier) {
      await notifier.fileSent({
        name: path.basename(filePath),
        bytes: stat.size,
        parts,
        reconstructHint: reconstructHint(filePath, parts),
      });
    }

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
    start, stop, tick,
  };
};

// Re-exported for tests / backwards compatibility.
module.exports = { createWatcher, formatSize, renderCaption };
