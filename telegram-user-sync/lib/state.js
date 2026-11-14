// Tracks which files have already been sent (by path + size + mtime) so the
// watcher never sends the same recording twice.

const fs = require('fs');

const createState = (stateFile) => {
  let sent = {};
  try {
    if (fs.existsSync(stateFile)) {
      sent = JSON.parse(fs.readFileSync(stateFile, 'utf8')) || {};
    }
  } catch (err) {
    sent = {};
  }

  const save = () => {
    const tmp = `${stateFile}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(sent, null, 2));
    fs.renameSync(tmp, stateFile);
  };

  const isSent = (filePath, stat) => {
    const e = sent[filePath];
    return Boolean(e) && e.size === stat.size && e.mtimeMs === stat.mtimeMs;
  };

  const markSent = (filePath, stat) => {
    sent[filePath] = { size: stat.size, mtimeMs: stat.mtimeMs, sentAt: new Date().toISOString() };
    save();
  };

  return { isSent, markSent };
};

module.exports = { createState };
