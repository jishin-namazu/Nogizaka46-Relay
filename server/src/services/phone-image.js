import db from '../db/index.js';
import mediaArchive from './media.js';
import { recordError } from './error-log.js';

export const MEMBER_PHONE_IMAGES_SCHEMA_SQL = `
  CREATE TABLE IF NOT EXISTS member_phone_images (
      id BIGSERIAL PRIMARY KEY,
      member_id VARCHAR(255) NOT NULL,
      member_name VARCHAR(255),
      source_url TEXT NOT NULL,
      local_path TEXT NOT NULL,
      sha256 VARCHAR(64) NOT NULL,
      file_size BIGINT,
      is_latest BOOLEAN NOT NULL DEFAULT false,
      first_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
      last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
      UNIQUE (member_id, sha256)
  );

  CREATE INDEX IF NOT EXISTS idx_member_phone_images_member_latest
      ON member_phone_images (member_id, is_latest DESC, last_seen_at DESC, id DESC);
  CREATE INDEX IF NOT EXISTS idx_member_phone_images_member_url
      ON member_phone_images (member_id, source_url);
  CREATE UNIQUE INDEX IF NOT EXISTS idx_member_phone_images_one_latest
      ON member_phone_images (member_id) WHERE is_latest = true;
`;

function normalizeMemberId(memberId) {
  if (memberId == null) return '';
  return String(memberId).trim();
}

function normalizeUrl(url) {
  if (url == null) return '';
  return String(url).trim();
}

function normalizeName(name) {
  if (name == null) return null;
  const trimmed = String(name).trim();
  return trimmed || null;
}

export function phoneImageVersionTag(recordOrSha) {
  const sha = typeof recordOrSha === 'string'
    ? recordOrSha
    : recordOrSha?.sha256;
  const trimmed = String(sha || '').trim();
  return trimmed ? trimmed.slice(0, 12) : null;
}

/**
 * 成员来电封面图增量归档服务：
 * - 服务端按成员增量保留历史上出现过的每一张不同封面（不覆盖旧文件、不删旧记录）
 * - 始终维护每位成员唯一一条 is_latest = true 记录，供来电页面只展示最新封面
 */
export class MemberPhoneImageService {
  constructor({ database = db, archive = mediaArchive, logger = console } = {}) {
    this.db = database;
    this.archive = archive;
    this.logger = logger;
    this.schemaReadyPromise = null;
    this.memberQueues = new Map();
  }

  async ensureSchema() {
    if (!this.schemaReadyPromise) {
      this.schemaReadyPromise = this.db.query(MEMBER_PHONE_IMAGES_SCHEMA_SQL).catch(error => {
        this.schemaReadyPromise = null;
        throw error;
      });
    }
    return this.schemaReadyPromise;
  }

  async withMemberLock(memberId, task) {
    const previous = this.memberQueues.get(memberId) || Promise.resolve();
    let release;
    const current = new Promise(resolve => { release = resolve; });
    const tail = previous.then(() => current);
    this.memberQueues.set(memberId, tail);

    await previous;
    try {
      return await task();
    } finally {
      release();
      if (this.memberQueues.get(memberId) === tail) {
        this.memberQueues.delete(memberId);
      }
    }
  }

  async withTransaction(fn) {
    if (typeof this.db.withTransaction === 'function') {
      return this.db.withTransaction(fn);
    }
    if (typeof this.db.pool?.connect === 'function') {
      const client = await this.db.pool.connect();
      try {
        await client.query('BEGIN');
        const result = await fn(client);
        await client.query('COMMIT');
        return result;
      } catch (error) {
        await client.query('ROLLBACK').catch(() => {});
        throw error;
      } finally {
        client.release();
      }
    }
    return fn({ query: (text, params) => this.db.query(text, params) });
  }

  async promoteExistingRecord(memberId, memberName, existingId, sourceUrl = null) {
    return this.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`member_phone_images:${memberId}`]);
      await client.query(
        `UPDATE member_phone_images
         SET is_latest = false
         WHERE member_id = $1 AND is_latest = true AND id <> $2`,
        [memberId, existingId],
      );
      const updated = await client.query(
        `UPDATE member_phone_images
         SET is_latest = true,
             member_name = COALESCE($2, member_name),
             source_url = COALESCE($3, source_url),
             last_seen_at = CURRENT_TIMESTAMP
         WHERE id = $1
         RETURNING *`,
        [existingId, memberName, sourceUrl],
      );
      return updated.rows[0] || null;
    });
  }

  async upsertArchivedRecord({ memberId, memberName, sourceUrl, localPath, sha256, fileSize }) {
    return this.withTransaction(async (client) => {
      await client.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`member_phone_images:${memberId}`]);
      await client.query(
        `UPDATE member_phone_images
         SET is_latest = false
         WHERE member_id = $1 AND is_latest = true AND sha256 <> $2`,
        [memberId, sha256],
      );
      const res = await client.query(
        `INSERT INTO member_phone_images (
           member_id, member_name, source_url, local_path, sha256, file_size,
           is_latest, first_seen_at, last_seen_at
         ) VALUES ($1, $2, $3, $4, $5, $6, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
         ON CONFLICT (member_id, sha256) DO UPDATE SET
           member_name = COALESCE(EXCLUDED.member_name, member_phone_images.member_name),
           source_url = EXCLUDED.source_url,
           local_path = EXCLUDED.local_path,
           file_size = COALESCE(EXCLUDED.file_size, member_phone_images.file_size),
           is_latest = true,
           last_seen_at = CURRENT_TIMESTAMP
         RETURNING *`,
        [memberId, memberName, sourceUrl, localPath, sha256, fileSize ?? null],
      );
      return res.rows[0] || null;
    });
  }

  /**
   * 增量记录成员当前来电封面图：
   * 1. 若当前最新记录 URL 一致且本地文件完整，则直接复用（免重复下载）
   * 2. 若该 URL 已曾归档且本地文件完整，则提升为最新封面（保留历史记录）
   * 3. 若为新图或本地缺失，则下载并增量落盘到 phone-images/<memberId>/，设为最新
   */
  async recordMemberPhoneImage({ memberId, memberName = null, sourceUrl }) {
    const normalizedMemberId = normalizeMemberId(memberId);
    const normalizedUrl = normalizeUrl(sourceUrl);
    const normalizedName = normalizeName(memberName);
    if (!normalizedMemberId || !normalizedUrl) return null;

    await this.ensureSchema();

    return this.withMemberLock(normalizedMemberId, async () => {
      try {
        const currentLatest = await this.db.queryOne(
          `SELECT *
           FROM member_phone_images
           WHERE member_id = $1 AND is_latest = true
           ORDER BY last_seen_at DESC, id DESC
           LIMIT 1`,
          [normalizedMemberId],
        );

        if (
          currentLatest
          && currentLatest.source_url === normalizedUrl
          && await this.archive.storedFile(currentLatest.local_path)
        ) {
          if (normalizedName && !currentLatest.member_name) {
            const updated = await this.db.queryOne(
              `UPDATE member_phone_images
               SET member_name = $2, last_seen_at = CURRENT_TIMESTAMP
               WHERE id = $1
               RETURNING *`,
              [currentLatest.id, normalizedName],
            );
            return { record: updated || currentLatest, isNew: false, changed: false };
          }
          return { record: currentLatest, isNew: false, changed: false };
        }

        const existingByUrl = await this.db.queryOne(
          `SELECT *
           FROM member_phone_images
           WHERE member_id = $1 AND source_url = $2
           ORDER BY last_seen_at DESC, id DESC
           LIMIT 1`,
          [normalizedMemberId, normalizedUrl],
        );

        if (existingByUrl && await this.archive.storedFile(existingByUrl.local_path)) {
          const promoted = await this.promoteExistingRecord(
            normalizedMemberId,
            normalizedName,
            existingByUrl.id,
            normalizedUrl,
          );
          this.logger.log?.(
            `[PhoneImage] 成员 ${normalizedName || normalizedMemberId} (${normalizedMemberId}) 切换最新来电封面至已归档版本: ${promoted?.sha256?.slice(0, 12)}`,
          );
          return { record: promoted || existingByUrl, isNew: false, changed: true };
        }

        const downloaded = await this.archive.downloadMemberPhoneImage(normalizedUrl, normalizedMemberId, {
          resolveExistingPath: async (sha256) => {
            const byHash = await this.db.queryOne(
              `SELECT local_path
               FROM member_phone_images
               WHERE member_id = $1 AND sha256 = $2
               LIMIT 1`,
              [normalizedMemberId, sha256],
            );
            return byHash?.local_path || null;
          },
        });

        const record = await this.upsertArchivedRecord({
          memberId: normalizedMemberId,
          memberName: normalizedName,
          sourceUrl: normalizedUrl,
          localPath: downloaded.localPath,
          sha256: downloaded.sha256,
          fileSize: downloaded.fileSize,
        });

        if (downloaded.isNewFile) {
          this.logger.log?.(
            `[PhoneImage] 增量保存成员 ${normalizedName || normalizedMemberId} (${normalizedMemberId}) 新来电封面: ${downloaded.localPath} (sha256=${downloaded.sha256.slice(0, 12)})`,
          );
        }

        return {
          record,
          isNew: downloaded.isNewFile,
          changed: !currentLatest || currentLatest.sha256 !== downloaded.sha256 || currentLatest.source_url !== normalizedUrl,
        };
      } catch (error) {
        await recordError('phone_image.archive', error, {
          member_id: normalizedMemberId,
          member_name: normalizedName,
          source_url: normalizedUrl,
        });
        return null;
      }
    });
  }

  async getLatestForMember(memberId) {
    const normalizedMemberId = normalizeMemberId(memberId);
    if (!normalizedMemberId) return null;
    await this.ensureSchema();
    return this.db.queryOne(
      `SELECT *
       FROM member_phone_images
       WHERE member_id = $1
       ORDER BY is_latest DESC, last_seen_at DESC, id DESC
       LIMIT 1`,
      [normalizedMemberId],
    );
  }

  async getLatestByMemberIds(memberIds) {
    const uniqueIds = [...new Set(
      (memberIds || [])
        .map(normalizeMemberId)
        .filter(Boolean),
    )];
    const result = new Map();
    if (uniqueIds.length === 0) return result;
    await this.ensureSchema();
    const rows = await this.db.queryAll(
      `SELECT DISTINCT ON (member_id) *
       FROM member_phone_images
       WHERE member_id = ANY($1::text[])
       ORDER BY member_id, is_latest DESC, last_seen_at DESC, id DESC`,
      [uniqueIds],
    );
    for (const row of rows) {
      result.set(String(row.member_id), row);
    }
    return result;
  }

  async getLatestStoredFileForMember(memberId) {
    const latest = await this.getLatestForMember(memberId);
    if (!latest) return null;
    const filePath = await this.archive.storedFile(latest.local_path);
    if (!filePath) return null;
    return {
      filePath,
      sha256: latest.sha256 || null,
      sourceUrl: latest.source_url || null,
      record: latest,
    };
  }

  async listMemberPhoneImages(memberId) {
    const normalizedMemberId = normalizeMemberId(memberId);
    if (!normalizedMemberId) return [];
    await this.ensureSchema();
    return this.db.queryAll(
      `SELECT *
       FROM member_phone_images
       WHERE member_id = $1
       ORDER BY is_latest DESC, first_seen_at DESC, id DESC`,
      [normalizedMemberId],
    );
  }
}

const phoneImageService = new MemberPhoneImageService();

export default phoneImageService;
