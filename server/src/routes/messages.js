import express from 'express';
import messageService from '../services/message.js';
import mediaArchive from '../services/media.js';
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

    // 返回客户端期望的格式
    res.json({
      id: message.id,
      member_id: message.member_id,
      member_name: message.member_name,
      member_avatar_url: message.member_avatar_url,
      phone_image_url: mediaUrl(message, 'phone_image_url', 'phone_image'),
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
    const filePath = await messageService.getStoredMediaPath(req.params.id, req.params.kind);
    if (filePath && fs.existsSync(filePath)) {
      res.setHeader('Cache-Control', 'private, max-age=86400');
      return res.sendFile(path.resolve(filePath), {
        acceptRanges: true,
      });
    }

    // 本地未落盘时回退至原始远程直链
    const message = await messageService.getMessage(req.params.id);
    const remoteUrl = {
      media: message?.media_url,
      thumbnail: message?.thumbnail_url,
      phone_image: message?.phone_image_url,
    }[req.params.kind];

    if (remoteUrl) {
      return res.redirect(remoteUrl);
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

    res.json({
      success: true,
      messages: messages.map(m => ({
        id: m.id,
        member_id: m.member_id,
        member_name: m.member_name,
        member_avatar_url: m.member_avatar_url,
        phone_image_url: mediaUrl(m, 'phone_image_url', 'phone_image'),
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
