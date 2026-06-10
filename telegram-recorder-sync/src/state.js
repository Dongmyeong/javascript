// Tracks which files have already been sent so the watcher never sends a file
// twice. State is persisted as JSON keyed by absolute file path.

const fs = require('fs');

const createState = (stateFile) => {
  let sent = {};

  const load = () => {
    try {
      if (fs.existsSync(stateFile)) {
        sent = JSON.parse(fs.readFileSync(stateFile, 'utf8')) || {};
      }
    } catch (err) {
      // A corrupt state file should not crash the watcher; start fresh.
      sent = {};
    }
  };

  const save = () => {
    const tmp = `${stateFile}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(sent, null, 2));
    fs.renameSync(tmp, stateFile);
  };

  // We treat a file as "already sent" only when path, size and mtime all match,
  // so a file that is recreated/edited with the same name gets resent.
  const isSent = (filePath, stat) => {
    const entry = sent[filePath];
    if (!entry) {
      return false;
    }
    return entry.size === stat.size && entry.mtimeMs === stat.mtimeMs;
  };

  const markSent = (filePath, stat) => {
    sent[filePath] = {
      size: stat.size,
      mtimeMs: stat.mtimeMs,
      sentAt: new Date().toISOString(),
    };
    save();
  };

  load();

  return { isSent, markSent };
};

module.exports = { createState };
