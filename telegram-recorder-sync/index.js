#!/usr/bin/env node

// Entry point: load config, verify the Telegram bot, then watch the recordings
// folder and auto-send new files. Run with `node index.js` or `npm start`.

const { loadConfig } = require('./src/config');
const { createTelegramClient } = require('./src/telegram');
const { createState } = require('./src/state');
const { createWatcher } = require('./src/watcher');
const { createNotifier } = require('./src/notifier');
const { createForwarder } = require('./src/forwarder');
const logger = require('./src/logger');

const main = async () => {
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    logger.error(err.message);
    logger.error('Copy .env.example to .env and fill in the values, '
      + 'or set the variables in your environment.');
    process.exit(1);
    return;
  }

  const telegram = createTelegramClient({
    botToken: config.botToken,
    chatId: config.chatId,
    requestTimeoutMs: config.requestTimeoutMs,
    maxRetries: config.maxRetries,
  });

  try {
    const me = await telegram.getMe();
    logger.info(`Connected to Telegram as @${me.username}`);
  } catch (err) {
    logger.error(`Could not reach Telegram (check token / network): ${err.message}`);
    process.exit(1);
    return;
  }

  const state = createState(config.stateFile);
  const notifier = createNotifier({ telegram, config, logger });
  const forwarder = createForwarder({ url: config.forwardUrl, logger });
  if (forwarder.enabled) {
    logger.info('Forwarding send results to external receiver');
  }
  const watcher = createWatcher({
    config, telegram, state, logger, notifier, forwarder,
  });

  let shuttingDown = false;
  const shutdown = async (signal) => {
    if (shuttingDown) {
      return;
    }
    shuttingDown = true;
    logger.info(`Received ${signal}, stopping...`);
    watcher.stop();
    await notifier.summary();
    process.exit(0);
  };
  process.on('SIGINT', () => { shutdown('SIGINT'); });
  process.on('SIGTERM', () => { shutdown('SIGTERM'); });

  await notifier.started();
  await watcher.start();
};

main().catch((err) => {
  logger.error(`Fatal: ${err.stack || err.message}`);
  process.exit(1);
});
