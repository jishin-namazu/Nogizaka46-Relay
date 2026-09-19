import db from '../db/index.js';
import mediaArchive from './media.js';

const NON_TEST_MESSAGE = "id !~ '^test[-_]'";

/**
 * 消息存储服务
 */
class MessageService {
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
      // 为在本地媒体存储启用之前创建的行重试媒体归档。
      const needsPhoneImage = message.type === 'audio'
        && message.incoming_call_from
        && message.phone_image_url
        && !existing.phone_image_local_path;
      if ((message.media_url && !existing.media_local_path)
        || (message.thumbnail_url && !existing.thumbnail_local_path)
        || needsPhoneImage) {
        const archived = await mediaArchive.archiveMessage(message);
        if (archived.mediaLocalPath || archived.thumbnailLocalPath || archived.phoneImageLocalPath) {
          const updated = await db.queryOne(
            `UPDATE messages
             SET media_local_path = COALESCE($1, media_local_path),
                 thumbnail_local_path = COALESCE($2, thumbnail_local_path),
                 phone_image_local_path = COALESCE($3, phone_image_local_path)
             WHERE id = $4
             RETURNING *`,
            [archived.mediaLocalPath, archived.thumbnailLocalPath, archived.phoneImageLocalPath, message.id],
          );
          return { message: updated || existing, isNew: false };
        }
      }
      console.log(`Message ${message.id} already exists, skipping`);
      return { message: existing, isNew: false };
    }

    const archived = await mediaArchive.archiveMessage(message);
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
        message.phone_image_url || null,
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
        archived.phoneImageLocalPath,
        sourceAccountsJson,
      ]
    );

    if (result) {
      const isNew = Boolean(result.is_new_insert);
      delete result.is_new_insert;
      return { message: result, isNew };
    }

    const racedMessage = await db.queryOne(
      'SELECT * FROM messages WHERE id = $1',
      [message.id],
    );
    return { message: racedMessage || message, isNew: false };
  }

  async getStoredMediaPath(messageId, kind) {
    const message = await this.getMessage(messageId);
    if (!message) return null;
    const localPath = {
      media: message.media_local_path,
      thumbnail: message.thumbnail_local_path,
      phone_image: message.phone_image_local_path,
    }[kind];
    return mediaArchive.storedFile(localPath);
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
}

export default new MessageService();
