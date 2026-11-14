// Tiny .env loader (no dependency). Does not overwrite existing env vars.

const fs = require('fs');
const path = require('path');

const loadEnv = (dir = __dirname) => {
  const envPath = path.resolve(dir, '..', '.env');
  if (!fs.existsSync(envPath)) {
    return;
  }
  fs.readFileSync(envPath, 'utf8').split(/\r?\n/).forEach((raw) => {
    const line = raw.trim();
    if (!line || line.startsWith('#')) {
      return;
    }
    const eq = line.indexOf('=');
    if (eq === -1) {
      return;
    }
    const key = line.slice(0, eq).trim();
    let value = line.slice(eq + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (key && !(key in process.env)) {
      process.env[key] = value;
    }
  });
};

module.exports = { loadEnv };
