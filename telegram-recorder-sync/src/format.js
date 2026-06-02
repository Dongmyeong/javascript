// Shared formatting helpers.

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

module.exports = { formatSize, renderCaption };
