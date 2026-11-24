import db from '../db/index.js';
import mediaArchive from './media.js';
import phoneImageService from './phone-image.js';

const NON_TEST_MESSAGE = "id !~ '^test[-_]'";

/**
 * 消息存储服务
 */
class MessageService {
  async resolveAudioPhoneImage(message) {
    if (message.type !== 'audio' || !message.incoming_call_from || !message.phone_image_url) {
      return { localPath: null, sha256: null, sourceUrl: message.phone_image_url || null };
    }

    const preArchivedPath = await mediaArchive.storedFile(message.phone_image_local_path);
    if (preArchivedPath) {
      let sha256 = message.phone_image_sha256 || null;
      if (!sha256 && message.member_id) {
        const latest = await phoneImageService.getLatestForMember(message.member_id);
        if (latest?.local_path === preArchivedPath) sha256 = latest.sha256 || null;
      }
      return { localPath: preArchivedPath, sha256, sourceUrl: message.phone_image_url };
    }

    if (message.member_id) {
      const archived = await phoneImageService.recordMemberPhoneImage({
        memberId: message.member_id,
        memberName: message.member_name || null,
        sourceUrl: message.phone_image_url,
      });
      if (archived?.record) {
        return {
          localPath: archived.record.local_path || null,
          sha256: archived.record.sha256 || null,
          sourceUrl: archived.record.source_url || message.phone_image_url,
        };
      }
    }

    return { localPath: null, sha256: null, sourceUrl: message.phone_image_url };
  }

  /**
   * 保存消息（带去重）
   */
  async saveMessage(message) {
    // 检查消息是否已存在
    const existing = await db.queryOne(
      'SELECT * FROM messages WHERE id = $1',
      [message.id]
    );

    if (existing) {
      // 为在本地媒体存储启用之前创建或迁移后本地文件缺失的行重试媒体归档，
      // 并在成员来电封面更新后同步最新封面引用。
      const existingMedia = await mediaArchive.storedFile(existing.media_local_path);
      const existingThumb = await mediaArchive.storedFile(existing.thumbnail_local_path);
      const existingPhone = await mediaArchive.storedFile(existing.phone_image_local_path);
      const resolvedPhone = await this.resolveAudioPhoneImage(message);
      const targetPhoneUrl = resolvedPhone.sourceUrl || message.phone_image_url || null;
      const needsPhoneImage = message.type === 'audio'
        && Boolean(message.incoming_call_from)
        && Boolean(targetPhoneUrl)
        && (
          !existingPhone
          || existing.phone_image_url !== targetPhoneUrl
          || (resolvedPhone.localPath && existing.phone_image_local_path !== resolvedPhone.localPath)
        );
      if ((message.media_url && !existingMedia)
        || (message.thumbnail_url && !existingThumb)
        || needsPhoneImage) {
        const messageForArchive = {
          ...message,
          media_url: existingMedia ? null : message.media_url,
          thumbnail_url: existingThumb ? null : message.thumbnail_url,
          phone_image_url: needsPhoneImage ? targetPhoneUrl : null,
          phone_image_local_path: needsPhoneImage
            ? (resolvedPhone.localPath || message.phone_image_local_path || null)
            : null,
        };
        const archived = await mediaArchive.archiveMessage(messageForArchive);
        const nextPhoneLocalPath = resolvedPhone.localPath || archived.phoneImageLocalPath;
        if (archived.mediaLocalPath || archived.thumbnailLocalPath || nextPhoneLocalPath || needsPhoneImage) {
          const updated = await db.queryOne(
            `UPDATE messages
             SET media_local_path = COALESCE($1, media_local_path),
                 thumbnail_local_path = COALESCE($2, thumbnail_local_path),
                 phone_image_local_path = COALESCE($3, phone_image_local_path),
                 media_url = COALESCE($4, media_url),
                 thumbnail_url = COALESCE($5, thumbnail_url),
                 phone_image_url = COALESCE($6, phone_image_url)
             WHERE id = $7
             RETURNING *`,
            [
              archived.mediaLocalPath,
              archived.thumbnailLocalPath,
              nextPhoneLocalPath,
              message.media_url || null,
              message.thumbnail_url || null,
              targetPhoneUrl,
              message.id,
            ],
          );
          if (message.source_account_id) {
            await this.addSourceAccount(message.id, message.source_account_id);
          }
          const finalMessage = updated || existing;
          if (resolvedPhone.sha256) finalMessage.phone_image_sha256 = resolvedPhone.sha256;
          return { message: finalMessage, isNew: false };
        }
      }
      if (message.source_account_id) {
        await this.addSourceAccount(message.id, message.source_account_id);
      }
      console.log(`Message ${message.id} already exists, skipping`);
      return { message: existing, isNew: false, duplicate: true };
    }

    const resolvedPhone = await this.resolveAudioPhoneImage(message);
    const targetPhoneUrl = resolvedPhone.sourceUrl || message.phone_image_url || null;
    const archived = await mediaArchive.archiveMessage({
      ...message,
      phone_image_url: targetPhoneUrl,
      phone_image_local_path: resolvedPhone.localPath || message.phone_image_local_path || null,
    });
    const finalPhoneLocalPath = resolvedPhone.localPath || archived.phoneImageLocalPath;
    const sourceAccount = message.source_account_id ? [String(message.source_account_id)] : [];
    const sourceAccountsJson = JSON.stringify(sourceAccount);

    // 插入新消息或更新来源账号列表
    const result = await db.queryOne(
      `INSERT INTO messages (
        id, member_id, member_name, member_avatar_url, phone_image_url,
        type, text, media_url, thumbnail_url, duration_seconds,
        sent_at, incoming_call_from, ringtone_url, is_played, original_data,
        media_local_path, thumbnail_local_path, phone_image_local_path,
        source_accounts
      ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19::jsonb)
      ON CONFLICT (id) DO UPDATE SET
        source_accounts = CASE
          WHEN messages.source_accounts @> $19::jsonb THEN messages.source_accounts
          ELSE messages.source_accounts || $19::jsonb
        END
      RETURNING (xmax = 0) AS is_new_insert, *`,
      [
        message.id,
        message.member_id || null,
        message.member_name,
        message.member_avatar_url || null,
        targetPhoneUrl,
        message.type,
        message.text || null,
        message.media_url || null,
        message.thumbnail_url || null,
        message.duration_seconds || null,
        message.sent_at,
        message.incoming_call_from || null,
        message.ringtone_url || null,
        message.is_played !== undefined ? message.is_played : false,
        JSON.stringify(message.original_data || {}),
        archived.mediaLocalPath,
        archived.thumbnailLocalPath,
        finalPhoneLocalPath,
        sourceAccountsJson,
      ]
    );

    if (result) {
      const isNew = Boolean(result.is_new_insert);
      delete result.is_new_insert;
      if (resolvedPhone.sha256) result.phone_image_sha256 = resolvedPhone.sha256;
      return { message: result, isNew };
    }

    const racedMessage = await db.queryOne(
      'SELECT * FROM messages WHERE id = $1',
      [message.id],
    );
    const finalMessage = racedMessage || message;
    if (resolvedPhone.sha256) finalMessage.phone_image_sha256 = resolvedPhone.sha256;
    return { message: finalMessage, isNew: false };
  }

  async getStoredMediaInfo(messageId, kind) {
    const message = await this.getMessage(messageId);
    if (!message) return null;

    if (kind === 'phone_image') {
      if (message.member_id) {
        const latest = await phoneImageService.getLatestStoredFileForMember(message.member_id);
        if (latest?.filePath) {
          return {
            filePath: latest.filePath,
            sha256: latest.sha256 || null,
            sourceUrl: latest.sourceUrl || message.phone_image_url || null,
          };
        }
      }
      const fallbackPath = await mediaArchive.storedFile(message.phone_image_local_path);
      if (!fallbackPath) return null;
      return {
        filePath: fallbackPath,
        sha256: null,
        sourceUrl: message.phone_image_url || null,
      };
    }

    const localPath = {
      media: message.media_local_path,
      thumbnail: message.thumbnail_local_path,
    }[kind];
    const storedPath = await mediaArchive.storedFile(localPath);
    if (!storedPath) return null;
    return {
      filePath: storedPath,
      sha256: null,
      sourceUrl: kind === 'media' ? message.media_url : message.thumbnail_url,
    };
  }

  async getStoredMediaPath(messageId, kind) {
    const info = await this.getStoredMediaInfo(messageId, kind);
    return info?.filePath || null;
  }

  /**
   * 获取消息详情
   */
  async getMessage(messageId) {
    return await db.queryOne(
      `SELECT * FROM messages WHERE id = $1 AND ${NON_TEST_MESSAGE}`,
      [messageId]
    );
  }

  /**
   * 获取消息列表
   */
  async getMessages({ limit = 50, offset = 0, type = null, memberId = null, accountId = null }) {
    let query = `SELECT * FROM messages WHERE ${NON_TEST_MESSAGE}`;
    const params = [];
    let paramCount = 0;

    if (type) {
      paramCount++;
      query += ` AND type = $${paramCount}`;
      params.push(type);
    }

    if (memberId) {
      paramCount++;
      query += ` AND member_id = $${paramCount}`;
      params.push(memberId);
    }

    if (accountId) {
      paramCount++;
      query += ` AND source_accounts @> $${paramCount}::jsonb`;
      params.push(JSON.stringify([String(accountId)]));
    }

    query += ` ORDER BY sent_at DESC, id DESC LIMIT $${paramCount + 1} OFFSET $${paramCount + 2}`;
    params.push(limit, offset);

    return await db.queryAll(query, params);
  }

  /**
   * 更新消息播放状态
   */
  async markAsPlayed(messageId) {
    const result = await db.queryOne(
      'UPDATE messages SET is_played = true WHERE id = $1 RETURNING *',
      [messageId]
    );
    return result;
  }

  /**
   * 获取消息统计
   */
  async getStatistics() {
    const stats = await db.queryOne(
      `SELECT
        COUNT(*) as total,
        COUNT(CASE WHEN type = 'text' THEN 1 END) as text_count,
        COUNT(CASE WHEN type = 'image' THEN 1 END) as image_count,
        COUNT(CASE WHEN type = 'audio' THEN 1 END) as audio_count,
        COUNT(CASE WHEN type = 'video' THEN 1 END) as video_count,
        COUNT(CASE WHEN type = 'audio' AND is_played = false THEN 1 END) as unplayed_audio
      FROM messages
      WHERE ${NON_TEST_MESSAGE}`
    );
    return stats;
  }

  /**
   * 检查指定消息是否已经存在于数据库中
   */
  async hasMessage(id) {
    if (!id) return false;
    const row = await db.queryOne(
      'SELECT id, type, member_id, media_url, media_local_path, phone_image_url, phone_image_local_path FROM messages WHERE id = $1',
      [id],
    );
    if (!row) return false;
    if (row.type !== 'text' && row.media_url) {
      const stored = await mediaArchive.storedFile(row.media_local_path);
      if (!stored) return false;
    }
    if (row.type === 'audio') {
      const latest = row.member_id ? await phoneImageService.getLatestForMember(row.member_id) : null;
      if (latest) {
        const latestStored = await mediaArchive.storedFile(latest.local_path);
        if (!latestStored) return false;
        if (row.phone_image_url !== latest.source_url || row.phone_image_local_path !== latest.local_path) {
          return false;
        }
      } else if (row.phone_image_url) {
        const storedPhone = await mediaArchive.storedFile(row.phone_image_local_path);
        if (!storedPhone) return false;
      }
    }
    return true;
  }

  /**
   * 将账号 ID 追加到指定消息的 source_accounts 列表中（去重追加）
   */
  async addSourceAccount(id, accountId) {
    if (!id || !accountId) return;
    try {
      const accJson = JSON.stringify([String(accountId)]);
      await db.query(
        `UPDATE messages
         SET source_accounts = CASE
           WHEN source_accounts @> $1::jsonb THEN source_accounts
           ELSE source_accounts || $1::jsonb
         END
         WHERE id = $2`,
        [accJson, id]
      );
    } catch (err) {
      console.warn(`Failed to add source account ${accountId} to message ${id}:`, err.message);
    }
  }
}

export default new MessageService();
