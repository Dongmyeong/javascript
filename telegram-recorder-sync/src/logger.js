// Minimal timestamped logger. No dependencies so it runs anywhere (incl. Termux).

const timestamp = () => new Date().toISOString();

const log = (level, ...args) => {
  const line = `${timestamp()} [${level}]`;
  if (level === 'ERROR') {
    console.error(line, ...args);
  } else {
    console.log(line, ...args);
  }
};

module.exports = {
  info: (...args) => log('INFO', ...args),
  warn: (...args) => log('WARN', ...args),
  error: (...args) => log('ERROR', ...args),
};
