import express from 'express';
import messageService from '../services/message.js';
import mediaArchive from '../services/media.js';
import phoneImageService, { phoneImageVersionTag } from '../services/phone-image.js';
import fs from 'node:fs';
import path from 'node:path';
import { authenticate, authenticateClient } from '../middleware/auth.js';

function mediaUrl(message, field, kind) {
  // 媒体归档在 monitor 卷上。返回受保护的 relay URL，
  // 因此客户端无需直接访问私有的 CloudFront URL。
  const localField = {
    media: 'media_local_path',
    thumbnail: 'thumbnail_local_path',
    phone_image: 'phone_image_local_path',
  }[kind];
  return (message[field] || message[localField]) ? mediaArchive.publicUrl(message.id, kind) : null;
}

function phoneImagePublicUrl(message, latestCover = null) {
  if (message.type !== 'audio') return null;
  if (latestCover && (latestCover.local_path || latestCover.source_url)) {
    return mediaArchive.publicUrl(message.id, 'phone_image', phoneImageVersionTag(latestCover));
  }
  return mediaUrl(message, 'phone_image_url', 'phone_image');
}

function matchesIfNoneMatch(headerValue, etag) {
  if (!headerValue || !etag) return false;
  return String(headerValue)
    .split(',')
    .map(token => token.trim())
    .some(token => token === '*' || token === etag || token === `W/${etag}`);
}

const router = express.Router();

/**
 * GET /v1/messages/:id
 * 获取消息详情（客户端回调使用）
 */
router.get('/:id', authenticateClient, async (req, res) => {
  try {
    const messageId = req.params.id;

    const message = await messageService.getMessage(messageId);

    if (!message) {
      return res.status(404).json({
        error: 'Message not found'
      });
    }

    const latestCover = message.type === 'audio' && message.member_id
      ? await phoneImageService.getLatestForMember(message.member_id)
      : null;

    // 返回客户端期望的格式
    res.json({
      id: message.id,
      member_id: message.member_id,
      member_name: message.member_name,
      member_avatar_url: message.member_avatar_url,
      phone_image_url: phoneImagePublicUrl(message, latestCover),
      type: message.type,
      text: message.text,
      media_url: mediaUrl(message, 'media_url', 'media'),
      thumbnail_url: mediaUrl(message, 'thumbnail_url', 'thumbnail'),
      duration_seconds: message.duration_seconds,
      sent_at: message.sent_at,
      incoming_call_from: message.incoming_call_from,
      ringtone_url: message.ringtone_url,
      is_played: message.is_played,
    });
  } catch (error) {
    console.error('Get message error:', error);
    res.status(500).json({
      error: 'Failed to get message',
      message: error.message,
    });
  }
});

/**
 * GET /v1/messages/:id/media/:kind
 * 提供一个已归档的媒体文件。
 */
router.get('/:id/media/:kind', authenticateClient, async (req, res) => {
  try {
    if (!['media', 'thumbnail', 'phone_image'].includes(req.params.kind)) {
      return res.status(400).json({ error: 'Invalid media kind' });
    }
    const mediaInfo = await messageService.getStoredMediaInfo(req.params.id, req.params.kind);
    const filePath = mediaInfo?.filePath || null;
    if (filePath && fs.existsSync(filePath)) {
      if (req.params.kind === 'phone_image') {
        const stat = fs.statSync(filePath);
        const etag = mediaInfo.sha256
          ? `"${mediaInfo.sha256}"`
          : `"${stat.size}-${Math.trunc(stat.mtimeMs)}"`;
        res.setHeader('Cache-Control', 'private, no-cache');
        res.setHeader('ETag', etag);
        res.setHeader('Last-Modified', stat.mtime.toUTCString());
        if (matchesIfNoneMatch(req.headers['if-none-match'], etag)) {
          return res.status(304).end();
        }
      } else {
        res.setHeader('Cache-Control', 'private, max-age=86400');
      }
      return res.sendFile(path.resolve(filePath), {
        acceptRanges: true,
      });
    }

    // 本地未落盘时仅回退至未过期的原始远程直链；若签名已过期则返回 404，避免客户端收到 CloudFront 403 而反复重试
    const message = await messageService.getMessage(req.params.id);
    let remoteUrl = {
      media: message?.media_url,
      thumbnail: message?.thumbnail_url,
      phone_image: message?.phone_image_url,
    }[req.params.kind];

    if (req.params.kind === 'phone_image' && message?.member_id) {
      const latestCover = await phoneImageService.getLatestForMember(message.member_id);
      if (latestCover?.source_url) remoteUrl = latestCover.source_url;
    }

    if (remoteUrl) {
      try {
        const parsed = new URL(remoteUrl);
        const expires = Number.parseInt(parsed.searchParams.get("Expires") || "", 10);
        if (!Number.isFinite(expires) || expires * 1000 > Date.now() + 5000) {
          return res.redirect(remoteUrl);
        }
      } catch {
        // ignore invalid URL and fall through to 404
      }
    }

    return res.status(404).json({ error: 'Media not found or archived' });
  } catch (error) {
    console.error('Get stored media error:', error);
    return res.status(500).json({ error: 'Failed to get stored media' });
  }
});

/**
 * GET /v1/messages
 * 获取消息列表
 */
router.get('/', authenticateClient, async (req, res) => {
  try {
    const limit = Math.min(parseInt(req.query.limit, 10) || 50, 1000);
    const offset = parseInt(req.query.offset, 10) || 0;
    const type = req.query.type || null;
    const memberId = req.query.member_id || null;

    const messages = await messageService.getMessages({
      limit,
      offset,
      type,
      memberId,
    });

    const audioMemberIds = messages
      .filter(m => m.type === 'audio' && m.member_id)
      .map(m => m.member_id);
    const latestCoversByMember = await phoneImageService.getLatestByMemberIds(audioMemberIds);

    res.json({
      success: true,
      messages: messages.map(m => ({
        id: m.id,
        member_id: m.member_id,
        member_name: m.member_name,
        member_avatar_url: m.member_avatar_url,
        phone_image_url: phoneImagePublicUrl(m, latestCoversByMember.get(String(m.member_id)) || null),
        type: m.type,
        text: m.text,
        media_url: mediaUrl(m, 'media_url', 'media'),
        thumbnail_url: mediaUrl(m, 'thumbnail_url', 'thumbnail'),
        duration_seconds: m.duration_seconds,
        sent_at: m.sent_at,
        incoming_call_from: m.incoming_call_from,
        ringtone_url: m.ringtone_url,
        is_played: m.is_played,
        created_at: m.created_at,
      })),
      pagination: {
        limit,
        offset,
        count: messages.length,
      },
    });
  } catch (error) {
    console.error('Get messages error:', error);
    res.status(500).json({
      error: 'Failed to get messages',
      message: error.message,
    });
  }
});

/**
 * PATCH /v1/messages/:id/played
 * 标记消息为已播放
 */
router.patch('/:id/played', authenticate, async (req, res) => {
  try {
    const messageId = req.params.id;

    const message = await messageService.markAsPlayed(messageId);

    if (!message) {
      return res.status(404).json({
        error: 'Message not found'
      });
    }

    res.json({
      success: true,
      message: {
        id: message.id,
        is_played: message.is_played,
      },
    });
  } catch (error) {
    console.error('Mark played error:', error);
    res.status(500).json({
      error: 'Failed to mark message as played',
      message: error.message,
    });
  }
});

/**
 * GET /v1/messages/stats
 * 获取消息统计
 */
router.get('/stats/summary', authenticateClient, async (req, res) => {
  try {
    const stats = await messageService.getStatistics();

    res.json({
      success: true,
      stats: {
        total: parseInt(stats.total, 10),
        by_type: {
          text: parseInt(stats.text_count, 10),
          image: parseInt(stats.image_count, 10),
          audio: parseInt(stats.audio_count, 10),
          video: parseInt(stats.video_count, 10),
        },
        unplayed_audio: parseInt(stats.unplayed_audio, 10),
      },
    });
  } catch (error) {
    console.error('Get stats error:', error);
    res.status(500).json({
      error: 'Failed to get statistics',
      message: error.message,
    });
  }
});

export default router;
