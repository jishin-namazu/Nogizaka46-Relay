import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { MediaArchive } from '../src/services/media.js';
import { MemberPhoneImageService, phoneImageVersionTag } from '../src/services/phone-image.js';
import { NogiBrowserMonitor } from '../src/monitor/nogi-browser.js';
import { pushPayload } from '../src/services/firebase.js';
import { NogiMediaServer } from '../src/monitor/media-server.js';

function jpegBuffer(marker) {
  return Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xdb]), Buffer.from(marker)]);
}

function responseFromBuffer(buffer, contentType = 'image/jpeg') {
  return new Response(buffer, {
    status: 200,
    headers: {
      'content-type': contentType,
      'content-length': String(buffer.byteLength),
    },
  });
}

function createInMemoryPhoneImageDb() {
  let nextId = 1;
  const rows = [];

  const execute = async (sql, params = []) => {
    const normalized = sql.replace(/\s+/g, ' ').trim();

    if (normalized.startsWith('CREATE TABLE') || normalized.startsWith('SELECT pg_advisory_xact_lock')) {
      return { rows: [], rowCount: 0 };
    }

    if (normalized.includes('WHERE member_id = $1 AND is_latest = true') && normalized.startsWith('SELECT *')) {
      const matched = rows
        .filter(r => r.member_id === params[0] && r.is_latest)
        .sort((a, b) => b.last_seen_at - a.last_seen_at || b.id - a.id);
      return { rows: matched.slice(0, 1).map(r => ({ ...r })), rowCount: Math.min(matched.length, 1) };
    }

    if (normalized.includes('WHERE member_id = $1 AND source_url = $2') && normalized.startsWith('SELECT *')) {
      const matched = rows
        .filter(r => r.member_id === params[0] && r.source_url === params[1])
        .sort((a, b) => b.last_seen_at - a.last_seen_at || b.id - a.id);
      return { rows: matched.slice(0, 1).map(r => ({ ...r })), rowCount: Math.min(matched.length, 1) };
    }

    if (normalized.includes('SELECT local_path FROM member_phone_images WHERE member_id = $1 AND sha256 = $2')) {
      const found = rows.find(r => r.member_id === params[0] && r.sha256 === params[1]);
      return { rows: found ? [{ local_path: found.local_path }] : [], rowCount: found ? 1 : 0 };
    }

    if (normalized.includes('WHERE member_id = $1 ORDER BY is_latest DESC, last_seen_at DESC')) {
      const matched = rows
        .filter(r => r.member_id === params[0])
        .sort((a, b) => Number(b.is_latest) - Number(a.is_latest) || b.last_seen_at - a.last_seen_at || b.id - a.id);
      return { rows: matched.slice(0, 1).map(r => ({ ...r })), rowCount: Math.min(matched.length, 1) };
    }

    if (normalized.includes('WHERE member_id = $1 ORDER BY is_latest DESC, first_seen_at DESC')) {
      const matched = rows
        .filter(r => r.member_id === params[0])
        .sort((a, b) => Number(b.is_latest) - Number(a.is_latest) || b.first_seen_at - a.first_seen_at || b.id - a.id);
      return { rows: matched.map(r => ({ ...r })), rowCount: matched.length };
    }

    if (normalized.includes('SET is_latest = false WHERE member_id = $1 AND is_latest = true AND id <> $2')) {
      let count = 0;
      for (const r of rows) {
        if (r.member_id === params[0] && r.is_latest && r.id !== params[1]) {
          r.is_latest = false;
          count += 1;
        }
      }
      return { rows: [], rowCount: count };
    }

    if (normalized.includes('SET is_latest = false WHERE member_id = $1 AND is_latest = true AND sha256 <> $2')) {
      let count = 0;
      for (const r of rows) {
        if (r.member_id === params[0] && r.is_latest && r.sha256 !== params[1]) {
          r.is_latest = false;
          count += 1;
        }
      }
      return { rows: [], rowCount: count };
    }

    if (normalized.includes('SET is_latest = true, member_name = COALESCE($2, member_name)')) {
      const row = rows.find(r => r.id === params[0]);
      if (!row) return { rows: [], rowCount: 0 };
      row.is_latest = true;
      row.member_name = params[1] ?? row.member_name;
      row.source_url = params[2] ?? row.source_url;
      row.last_seen_at = new Date();
      return { rows: [{ ...row }], rowCount: 1 };
    }

    if (normalized.startsWith('INSERT INTO member_phone_images')) {
      const [memberId, memberName, sourceUrl, localPath, sha256, fileSize] = params;
      const existing = rows.find(r => r.member_id === memberId && r.sha256 === sha256);
      const now = new Date();
      if (existing) {
        existing.member_name = memberName ?? existing.member_name;
        existing.source_url = sourceUrl;
        existing.local_path = localPath;
        existing.file_size = fileSize ?? existing.file_size;
        existing.is_latest = true;
        existing.last_seen_at = now;
        return { rows: [{ ...existing }], rowCount: 1 };
      }
      const created = {
        id: nextId++,
        member_id: memberId,
        member_name: memberName,
        source_url: sourceUrl,
        local_path: localPath,
        sha256,
        file_size: fileSize,
        is_latest: true,
        first_seen_at: now,
        last_seen_at: now,
      };
      rows.push(created);
      return { rows: [{ ...created }], rowCount: 1 };
    }

    throw new Error(`Unsupported mock SQL: ${normalized}`);
  };

  return {
    rows,
    query: execute,
    queryOne: async (sql, params) => (await execute(sql, params)).rows[0] || null,
    queryAll: async (sql, params) => (await execute(sql, params)).rows,
    withTransaction: async (fn) => fn({ query: execute }),
  };
}

test('MemberPhoneImageService incrementally archives cover photos and serves only the latest one', async (t) => {
  const storageDir = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-phone-image-'));
  t.after(() => fs.rm(storageDir, { recursive: true, force: true }));

  const url1 = 'https://cdn.example.com/public/members/phone-images/47-20230130.jfif';
  const url2 = 'https://cdn.example.com/public/members/phone-images/47-20261124.jfif';
  const url1Mirror = 'https://cdn.example.com/public/members/phone-images/47-20230130-reupload.jfif';
  const bytesV1 = jpegBuffer('ikeda-teresa-cover-v1');
  const bytesV2 = jpegBuffer('ikeda-teresa-cover-v2-newer');

  const fetchCalls = [];
  const archive = new MediaArchive({
    storageDir,
    fetchImpl: async (url) => {
      fetchCalls.push(url);
      if (url === url1 || url === url1Mirror) return responseFromBuffer(bytesV1);
      if (url === url2) return responseFromBuffer(bytesV2);
      return new Response('not found', { status: 404 });
    },
  });
  const mockDb = createInMemoryPhoneImageDb();
  const service = new MemberPhoneImageService({
    database: mockDb,
    archive,
    logger: { log() {} },
  });

  // 1. 首次归档封面 V1
  const first = await service.recordMemberPhoneImage({
    memberId: '47',
    memberName: '池田 瑛紗',
    sourceUrl: url1,
  });
  assert.equal(first.isNew, true);
  assert.equal(first.changed, true);
  assert.equal(fetchCalls.length, 1);
  assert.ok(first.record.local_path.includes(`${path.sep}phone-images${path.sep}47${path.sep}`));
  assert.deepEqual(await fs.readFile(first.record.local_path), bytesV1);

  // 2. 相同 URL 再次轮询：直接复用，不重复下载也不新建文件
  const repeat = await service.recordMemberPhoneImage({
    memberId: '47',
    memberName: '池田 瑛紗',
    sourceUrl: url1,
  });
  assert.equal(repeat.isNew, false);
  assert.equal(repeat.changed, false);
  assert.equal(fetchCalls.length, 1);

  // 3. 成员更换新封面 V2：增量保存 V2，保留 V1 文件，但最新记录仅指向 V2
  const second = await service.recordMemberPhoneImage({
    memberId: '47',
    memberName: '池田 瑛紗',
    sourceUrl: url2,
  });
  assert.equal(second.isNew, true);
  assert.equal(second.changed, true);
  assert.equal(fetchCalls.length, 2);
  assert.notEqual(second.record.local_path, first.record.local_path);
  assert.deepEqual(await fs.readFile(first.record.local_path), bytesV1);
  assert.deepEqual(await fs.readFile(second.record.local_path), bytesV2);

  const historyAfterV2 = await service.listMemberPhoneImages('47');
  assert.equal(historyAfterV2.length, 2);
  assert.equal(historyAfterV2[0].source_url, url2);
  assert.equal(historyAfterV2[0].is_latest, true);
  assert.equal(historyAfterV2[1].source_url, url1);
  assert.equal(historyAfterV2[1].is_latest, false);

  const latestStored = await service.getLatestStoredFileForMember('47');
  assert.equal(latestStored.filePath, second.record.local_path);
  assert.equal(latestStored.sourceUrl, url2);
  assert.equal(latestStored.sha256, second.record.sha256);

  // 4. 不同 URL 但内容字节与 V1 相同：按 sha256 去重复用原文件，仅切换 is_latest
  const sameBytesMirror = await service.recordMemberPhoneImage({
    memberId: '47',
    memberName: '池田 瑛紗',
    sourceUrl: url1Mirror,
  });
  assert.equal(sameBytesMirror.isNew, false);
  assert.equal(sameBytesMirror.changed, true);
  assert.equal(sameBytesMirror.record.local_path, first.record.local_path);
  assert.equal((await service.listMemberPhoneImages('47')).length, 2);

  // 5. 再次切回已归档的 V2 URL：免下载直接提升 V2 为最新
  const fetchCountBeforeSwitch = fetchCalls.length;
  const switchedBack = await service.recordMemberPhoneImage({
    memberId: '47',
    memberName: '池田 瑛紗',
    sourceUrl: url2,
  });
  assert.equal(fetchCalls.length, fetchCountBeforeSwitch);
  assert.equal(switchedBack.changed, true);
  assert.equal((await service.getLatestStoredFileForMember('47')).filePath, second.record.local_path);
});

test('NogiBrowserMonitor prefers member.phone_image over group.phone_image and only sets it for audio', () => {
  const monitor = new NogiBrowserMonitor();
  const group = {
    id: 47,
    name: '池田 瑛紗',
    phone_image: 'https://cdn.example.com/public/groups/phone-images/47-20230120.jfif',
    thumbnail: 'https://cdn.example.com/public/groups/thumbnails/47-20261027.jpg',
  };
  const member = {
    id: '47',
    name: '池田 瑛紗',
    phone_image: 'https://cdn.example.com/public/members/phone-images/47-20230130.jfif',
    thumbnail: 'https://cdn.example.com/public/members/thumbnails/47-20230130.jfif',
  };

  const voiceMsg = monitor.normalizeMessage({
    id: '180960',
    group_id: 47,
    member_id: 47,
    type: 'voice',
    file: 'https://cdn.example.com/private/180960.m4a',
    published_at: '2026-11-21T12:49:12Z',
  }, group, member);

  assert.equal(voiceMsg.type, 'audio');
  assert.equal(voiceMsg.phone_image_url, member.phone_image);
  // 单人 group 头像优先保留更新的 group.thumbnail
  assert.equal(voiceMsg.member_avatar_url, group.thumbnail);

  const textMsg = monitor.normalizeMessage({
    id: '180961',
    group_id: 47,
    member_id: 47,
    type: 'text',
    text: 'hello',
    published_at: '2026-11-21T12:50:00Z',
  }, group, member);
  assert.equal(textMsg.phone_image_url, null);

  // member.phone_image 缺失时回退 group.phone_image
  const fallbackVoice = monitor.normalizeMessage({
    id: '180962',
    group_id: 47,
    member_id: 47,
    type: 'voice',
    file: 'https://cdn.example.com/private/180962.m4a',
    published_at: '2026-11-21T12:51:00Z',
  }, group, { ...member, phone_image: null });
  assert.equal(fallbackVoice.phone_image_url, group.phone_image);
});

test('NogiBrowserMonitor fetches /v2/groups/:id/members, resolves 6th-gen covers, and force-refreshes member on live audio call', async () => {
  const savedMessages = [];
  const recordedCovers = [];
  let member60Cover = 'https://cdn.example.com/public/members/phone-images/60-v1.jfif';
  let timelineMessages = [
    {
      id: '200001',
      group_id: 60,
      member_id: 60,
      type: 'text',
      text: '初始消息',
      published_at: '2026-11-24T01:00:00Z',
    },
  ];

  const apiCalls = [];
  const monitor = new NogiBrowserMonitor({
    messageStore: {
      hasMessage: async () => false,
      saveMessage: async (msg) => {
        savedMessages.push(msg);
        return { isNew: true, message: msg };
      },
    },
    pusher: { pushMessage: async () => {} },
    phoneImageStore: {
      recordMemberPhoneImage: async ({ memberId, memberName, sourceUrl }) => {
        recordedCovers.push({ memberId, memberName, sourceUrl });
        return {
          record: {
            member_id: String(memberId),
            source_url: sourceUrl,
            local_path: `/data/nogi-media/phone-images/${memberId}/${recordedCovers.length}.jpg`,
            sha256: `${String(recordedCovers.length).padStart(12, '0')}abcdef012345`,
          },
        };
      },
    },
  });

  monitor.backfillOnStart = true;
  monitor.apiRequest = async (pathname) => {
    apiCalls.push(pathname);
    if (pathname.startsWith('/v2/groups?')) {
      return [
        {
          id: 60,
          organization_id: 1,
          state: 'open',
          name: '愛宕 心響',
          phone_image: null, // 6 期生在 /v2/groups 中为 null
          thumbnail: 'https://cdn.example.com/public/groups/thumbnails/60.jpg',
          subscription: { state: 'active' },
        },
      ];
    }
    if (pathname === '/v2/groups/60/members') {
      return [
        {
          id: 60,
          name: '愛宕 心響',
          thumbnail: 'https://cdn.example.com/public/members/thumbnails/60.jpg',
          phone_image: member60Cover,
        },
      ];
    }
    if (pathname === '/v2/members/60') {
      return {
        id: 60,
        name: '愛宕 心響',
        thumbnail: 'https://cdn.example.com/public/members/thumbnails/60.jpg',
        phone_image: member60Cover,
      };
    }
    if (pathname === '/v2/groups/60/past_messages') return { messages: [] };
    if (pathname.startsWith('/v2/groups/60/timeline')) return { messages: timelineMessages, continuation: null };
    throw new Error(`Unexpected API call: ${pathname}`);
  };

  // 第 1 次轮询：无语音消息也会同步 /v2/groups/60/members 并增量记录 V1
  await monitor.poll();
  assert.equal(recordedCovers.length, 1);
  assert.equal(recordedCovers[0].sourceUrl, 'https://cdn.example.com/public/members/phone-images/60-v1.jfif');

  // 官网在缓存有效期内更新了成员 60 的来电封面至 V2，并发送一条实时语音来电
  member60Cover = 'https://cdn.example.com/public/members/phone-images/60-v2.jfif';
  timelineMessages = [
    {
      id: '200002',
      group_id: 60,
      member_id: 60,
      type: 'voice',
      file: 'https://cdn.example.com/private/200002.m4a',
      published_at: '2026-11-24T01:05:00Z',
    },
    ...timelineMessages,
  ];

  await monitor.poll();
  assert.ok(apiCalls.includes('/v2/members/60'), '实时来电时应强制刷新 /v2/members/:id');
  assert.equal(recordedCovers.length, 2);
  assert.equal(recordedCovers[1].sourceUrl, member60Cover);

  const audioSaved = savedMessages.find(m => m.id === '200002');
  assert.ok(audioSaved);
  assert.equal(audioSaved.phone_image_url, member60Cover);
  assert.ok(audioSaved.phone_image_local_path);
  assert.ok(audioSaved.phone_image_sha256);
});

test('NogiBrowserMonitor resolveGroups preserves metadata when NOGI_GROUP_IDS is configured', async () => {
  const monitor = new NogiBrowserMonitor();
  monitor.groupIds = [47, 999];
  monitor.apiRequest = async (pathname) => {
    assert.ok(pathname.startsWith('/v2/groups?'));
    return [
      {
        id: 47,
        organization_id: 1,
        state: 'open',
        name: '池田 瑛紗',
        phone_image: 'https://cdn.example.com/group-47.jfif',
        thumbnail: 'https://cdn.example.com/thumb-47.jpg',
      },
    ];
  };

  const groups = await monitor.resolveGroups();
  assert.deepEqual(groups, [
    {
      id: 47,
      name: '池田 瑛紗',
      phone_image: 'https://cdn.example.com/group-47.jfif',
      thumbnail: 'https://cdn.example.com/thumb-47.jpg',
    },
    {
      id: 999,
      name: '',
      phone_image: null,
      thumbnail: null,
    },
  ]);
});

test('pushPayload versions audio phone_image URL and NogiMediaServer serves latest cover with ETag/304', async (t) => {
  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), 'nogi-media-server-'));
  t.after(() => fs.rm(tempDir, { recursive: true, force: true }));

  const fileV1 = path.join(tempDir, 'cover-v1.jpg');
  const fileV2 = path.join(tempDir, 'cover-v2.jpg');
  const bytesV1 = jpegBuffer('served-v1');
  const bytesV2 = jpegBuffer('served-v2-latest');
  await fs.writeFile(fileV1, bytesV1);
  await fs.writeFile(fileV2, bytesV2);

  const shaV1 = '1111111111112222222222223333333333334444444444445555555555556666';
  const shaV2 = 'aaaaaaaaaaaaaaaaaaaaaaaabbbbbbbbbbbbbbbbbbbbbbbbcccccccccccccccc';
  let activeCover = { filePath: fileV1, sha256: shaV1 };

  const previous = {
    ADMIN_TOKEN: process.env.ADMIN_TOKEN,
    CLIENT_TOKEN: process.env.CLIENT_TOKEN,
    PUBLIC_MEDIA_BASE_URL: process.env.PUBLIC_MEDIA_BASE_URL,
  };
  process.env.ADMIN_TOKEN = 'admin-secret-phone-test';
  process.env.CLIENT_TOKEN = 'client-secret-phone-test';
  process.env.PUBLIC_MEDIA_BASE_URL = 'https://relay.example.test:8081';
  t.after(() => {
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  });

  const payload = pushPayload({
    id: '180960',
    member_id: '47',
    member_name: '池田 瑛紗',
    type: 'audio',
    incoming_call_from: '池田 瑛紗',
    phone_image_url: 'https://cdn.example.com/v2.jfif',
    phone_image_local_path: fileV2,
    phone_image_sha256: shaV2,
  });
  assert.equal(
    payload.phone_image_url,
    `https://relay.example.test:8081/v1/messages/180960/media/phone_image?v=${phoneImageVersionTag(shaV2)}`,
  );

  const textPayload = pushPayload({
    id: '180961',
    member_id: '47',
    member_name: '池田 瑛紗',
    type: 'text',
    phone_image_url: 'https://cdn.example.com/stale.jfif',
  });
  assert.equal(textPayload.phone_image_url, null);

  const mediaServer = new NogiMediaServer({
    messageStore: {
      getStoredMediaInfo: async (_messageId, kind) => {
        if (kind === 'phone_image') return activeCover;
        return null;
      },
    },
  });
  const listener = http.createServer((req, res) => {
    void mediaServer.handle(req, res);
  });
  await new Promise(resolve => listener.listen(0, resolve));
  t.after(() => listener.close());

  const url = `http://127.0.0.1:${listener.address().port}/v1/messages/180960/media/phone_image`;
  const headers = { Authorization: 'Bearer client-secret-phone-test' };

  // 初次请求返回 V1 与 ETag
  const res1 = await fetch(url, { headers });
  assert.equal(res1.status, 200);
  assert.equal(res1.headers.get('cache-control'), 'private, no-cache');
  assert.equal(res1.headers.get('etag'), `"${shaV1}"`);
  assert.deepEqual(Buffer.from(await res1.arrayBuffer()), bytesV1);

  // 携带相同 ETag 命中 304
  const res304 = await fetch(url, { headers: { ...headers, 'If-None-Match': `"${shaV1}"` } });
  assert.equal(res304.status, 304);

  // 成员更新封面为 V2 后，同一条语音消息也返回最新封面 V2 与新 ETag
  activeCover = { filePath: fileV2, sha256: shaV2 };
  const res2 = await fetch(url, { headers: { ...headers, 'If-None-Match': `"${shaV1}"` } });
  assert.equal(res2.status, 200);
  assert.equal(res2.headers.get('etag'), `"${shaV2}"`);
  assert.deepEqual(Buffer.from(await res2.arrayBuffer()), bytesV2);
});
