#!/usr/bin/env node

// One-time login with YOUR Telegram account. Produces a SESSION string that
// lets the sync run non-interactively afterwards. Run once:
//   npm run login   (or: node login.js)
// Then copy the printed SESSION=... line into .env.

const fs = require('fs');
const path = require('path');
const readline = require('readline');
const { TelegramClient } = require('telegram');
const { StringSession } = require('telegram/sessions');

const { loadEnv } = require('./lib/env');

loadEnv();

const ask = (question) => new Promise((resolve) => {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  rl.question(question, (answer) => {
    rl.close();
    resolve(answer.trim());
  });
});

(async () => {
  const apiId = Number(process.env.API_ID);
  const apiHash = process.env.API_HASH;
  if (!apiId || !apiHash) {
    console.error('먼저 .env 에 API_ID 와 API_HASH 를 넣으세요 (https://my.telegram.org).');
    process.exit(1);
  }

  const client = new TelegramClient(new StringSession(''), apiId, apiHash, { connectionRetries: 5 });

  await client.start({
    phoneNumber: () => ask('전화번호 (예: +821012345678): '),
    password: () => ask('2단계 인증 비밀번호 (없으면 그냥 Enter): '),
    phoneCode: () => ask('텔레그램 앱으로 받은 코드: '),
    onError: (err) => console.log('오류:', err.message || err),
  });

  const session = client.session.save();
  fs.writeFileSync(path.resolve(__dirname, '.session'), session);

  console.log('\n=== 로그인 성공! ===');
  console.log('아래 한 줄을 .env 에 추가하세요 (.session 파일에도 저장됨):\n');
  console.log(`SESSION=${session}\n`);

  await client.disconnect();
  process.exit(0);
})().catch((err) => {
  console.error('로그인 실패:', err.stack || err.message);
  process.exit(1);
});
