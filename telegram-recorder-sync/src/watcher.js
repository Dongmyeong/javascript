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
  config, telegram, state, logger, notifier, forwarder, chunkUploader,
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
      const result = await telegram.sendFile(filePath, {
        caption: baseCaption,
        asAudio: config.sendAsAudio,
      });
      if (forwarder) {
        await forwarder.forward(result);
      }
      return 1;
    }

    logger.info(`Splitting ${path.basename(filePath)} into ${total} parts`);
    for (const part of parts) {
      const caption = `${path.basename(filePath)} (part ${part.index}/${part.total}, `
        + `${formatSize(part.blob.size)})`;
      // Parts are raw binary slices, so always send them as documents.
      const result = await telegram.sendBlob(part.blob, part.name, { caption, asAudio: false });
      if (forwarder) {
        await forwarder.forward(result);
      }
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

    const name = path.basename(filePath);
    const caption = renderCaption(config.captionTemplate, filePath, stat);
    logger.info(`Sending: ${name} (${formatSize(stat.size)})`);

    let parts;
    let channel;
    // Files larger than the Telegram bot download limit can't be fetched by the
    // receiver from Telegram, so upload them directly to it as binary chunks.
    if (chunkUploader && chunkUploader.enabled && stat.size > config.chunkThresholdBytes) {
      parts = await chunkUploader.upload(filePath, config.chunkSizeBytes);
      channel = 'chunk';
      logger.info(`Chunk-uploaded ${name} to receiver (${parts} chunks)`);
    } else {
      parts = await sendWithSplit(filePath, caption);
      channel = 'telegram';
      logger.info(`Sent: ${name}`);
    }
    state.markSent(filePath, stat);

    if (notifier) {
      await notifier.fileSent({
        name,
        bytes: stat.size,
        parts,
        channel,
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
