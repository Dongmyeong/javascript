// Turns a Telegram update into a downloaded file, preserving the original
// filename so split parts keep their ".partNofM" suffix for reassembly.

const fs = require('fs');
const path = require('path');

// Extract the downloadable file (document/audio/voice) from a message.
const pickFile = (msg) => {
  if (!msg) {
    return null;
  }
  if (msg.document) {
    return {
      fileId: msg.document.file_id,
      fileName: msg.document.file_name,
      fileSize: msg.document.file_size,
    };
  }
  if (msg.audio) {
    return {
      fileId: msg.audio.file_id,
      fileName: msg.audio.file_name || `${msg.audio.file_unique_id}.m4a`,
      fileSize: msg.audio.file_size,
    };
  }
  if (msg.voice) {
    return {
      fileId: msg.voice.file_id,
      fileName: `${msg.voice.file_unique_id}.ogg`,
      fileSize: msg.voice.file_size,
    };
  }
  return null;
};

// Download the file referenced by one update into outputDir. Returns the
// destination path, or null if the update carried no file.
const processUpdate = async (update, { telegram, outputDir, logger }) => {
  const msg = update.message || update.channel_post;
  const file = pickFile(msg);
  if (!file) {
    return null;
  }

  // path.basename guards against any path-traversal in the supplied name.
  const safeName = path.basename(file.fileName || `${update.update_id}.bin`);
  const dest = path.join(outputDir, safeName);

  if (fs.existsSync(dest)) {
    logger.info(`Skip existing: ${safeName}`);
    return dest;
  }

  try {
    const info = await telegram.getFile(file.fileId);
    await telegram.downloadFile(info.file_path, dest);
    logger.info(`Downloaded: ${safeName}${file.fileSize ? ` (${file.fileSize} bytes)` : ''}`);
    return dest;
  } catch (err) {
    // Files over 20MB can't be pulled by a bot; warn and move on.
    logger.error(`Download failed for ${safeName}: ${err.message}`);
    return null;
  }
};

module.exports = { pickFile, processUpdate };
