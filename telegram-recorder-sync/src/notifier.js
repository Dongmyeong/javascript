// Sends optional Telegram text notifications (split-file instructions, per-file
// confirmations) and keeps running session stats for a start/stop summary.
// All sends are best-effort: a failed notification never interrupts syncing.

const { formatSize } = require('./format');

const createNotifier = ({ telegram, config, logger }) => {
  const stats = {
    files: 0, bytes: 0, parts: 0, startedAt: Date.now(),
  };

  const safeSend = async (text) => {
    try {
      await telegram.sendMessage(text);
    } catch (err) {
      logger.warn(`Notification failed: ${err.message}`);
    }
  };

  const started = async () => {
    if (config.notifySummary) {
      await safeSend(`▶️ recorder-sync 시작 — ${config.watchDir} 감시 중`);
    }
  };

  // Called after a file (and all of its parts) has been sent successfully.
  const fileSent = async ({
    name, bytes, parts, channel = 'telegram', reconstructHint,
  }) => {
    stats.files += 1;
    stats.bytes += bytes;
    stats.parts += parts;

    if (channel === 'chunk') {
      if (config.notifySplitInstructions) {
        await safeSend(
          `📦 ${name} (${formatSize(bytes)}) 은 20MB 초과라 ${parts}개 조각으로 `
          + '수신기에 직접 업로드했습니다.',
        );
      }
    } else if (parts > 1 && config.notifySplitInstructions) {
      await safeSend(
        `📎 ${name} 은 50MB 제한으로 ${parts}개로 분할 전송되었습니다.\n`
        + `복원(터미널): ${reconstructHint}`,
      );
    } else if (config.notifyOnSend) {
      await safeSend(`✅ ${name} 전송 완료 (${formatSize(bytes)})`);
    }
  };

  const summary = async () => {
    if (!config.notifySummary) {
      return;
    }
    const minutes = Math.round((Date.now() - stats.startedAt) / 60000);
    await safeSend(
      `📊 세션 요약 — 파일 ${stats.files}개 / ${formatSize(stats.bytes)} `
      + `/ 청크 ${stats.parts}개 (${minutes}분 가동)`,
    );
  };

  return {
    started, fileSent, summary, stats,
  };
};

module.exports = { createNotifier };
