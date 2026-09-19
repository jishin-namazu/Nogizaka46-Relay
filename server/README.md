# Nogi Relay Server 运维与命令使用指南

本文档提供 Nogi Relay 服务端核心命令、REST API 接口操作范例以及云端运维实操手册。涵盖**会话管理、媒体下载、消息查询、成员统计、推送测试与排障指令**。

如需查看服务端代码内部实现原理，请参考根目录 [DEVELOPMENT.md](../DEVELOPMENT.md)；云端上线部署指南请参考 [DEPLOYMENT.md](../DEPLOYMENT.md)。

---

## 目录

- [1. 快速准备与通用约定](#1-快速准备与通用约定)
- [2. 官网会话管理与热更新](#2-官网会话管理与热更新)
- [3. 消息记录查询与统计](#3-消息记录查询与统计)
- [4. 订阅成员查询与分析](#4-订阅成员查询与分析)
- [5. 媒体文件下载与存储排查](#5-媒体文件下载与存储排查)
- [6. 设备管理与推送测试](#6-设备管理与推送测试)
- [7. 数据库初始化与健康检查](#7-数据库初始化与健康检查)
- [8. 本地脚本与实用运维命令](#8-本地脚本与实用运维命令)

---

## 1. 快速准备与通用约定

### 环境变量替换说明
在下文所有的 curl 与命令行示例中，请根据实际部署替换以下变量：
- `<SERVER_URL>`：服务端地址，例如本地为 `http://127.0.0.1:3000`，云端为 `https://<YOUR_APP_NAME>.fly.dev`
- `<MEDIA_URL>`：独立受保护媒体流地址，例如本地为 `http://127.0.0.1:8081`，云端为 `https://<YOUR_APP_NAME>.fly.dev:8081`（通过主 API 地址亦可访问）
- `<ACCESS_TOKEN>`：你在服务端配置的访问令牌（即 `ACCESS_TOKEN` Secret）

### 通用认证头
除 `/health` 探针外，所有请求均须携带 Bearer 鉴权头：
```bash
-H "Authorization: Bearer <ACCESS_TOKEN>"
```

为了方便在终端连续执行，可预先在终端定义临时变量：
```bash
# Linux / macOS / Git Bash
export SERVER="https://<YOUR_APP_NAME>.fly.dev"
export MEDIA="https://<YOUR_APP_NAME>.fly.dev:8081"
export TOKEN="你的ACCESS_TOKEN"

# Windows PowerShell
$SERVER = "https://<YOUR_APP_NAME>.fly.dev"
$MEDIA = "https://<YOUR_APP_NAME>.fly.dev:8081"
$TOKEN = "你的ACCESS_TOKEN"
```

---

## 2. 官网会话管理与热更新

服务端通过官方 API 自动轮询并维护访问令牌。当官网账号在其他设备重新登录导致会话失效（日志输出 `[NOGI_SESSION_UPDATE_REQUIRED]`）时，使用以下命令提取并热更新会话，无需重启容器。

> [!WARNING]
> **乃木坂 Message Web 端单会话限制**
> - 官方 Message Web 端仅允许一个活跃会话存在。
> - 当在其他设备登录官网 Web 端后，服务端持有的会话将被官方吊销。
> - 服务端 Monitor 进程续期失败时转入 `signedOut` 状态并暂停轮询。此时需重新提取并上传新会话。

### 2.1 本地提取官网会话
在本地电脑的 `server/` 目录下运行交互式提取工具：
```bash
cd server
npm install
npm run bootstrap:browser
```
- 脚本会自动唤起本地浏览器并导航至官方消息网页；
- 在网页中登录乃木坂46消息账号，确保能看到已订阅成员页面后；
- 回到终端敲击 **回车**，当前目录下会自动生成 `nogi-browser-state.json`。

### 2.2 一键上传并等待云端激活 (推荐)
使用内置的 `upload-session.js` 脚本上传：
```bash
node upload-session.js ./nogi-browser-state.json $SERVER $TOKEN
```
该脚本会自动完成：
1. 校验本地 JSON 结构的完整性（支持轻量会话凭证与完整快照格式）；
2. 调接口热写入云端私有目录（`0600` 文件权限）；
3. 轮询云端 Monitor 状态机，直到 Monitor 成功调用官网 API 校验通过并激活（返回 `✓ Session activated`）。

### 2.3 通过 curl 手动上传会话
如需直接通过 API 接口上传：
```bash
curl -X POST "$SERVER/v1/admin/browser-session" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"session\": $(cat ./nogi-browser-state.json)}"
```
- 响应 `200`（已激活）或 `202 Accepted`（已接收文件，等待 Monitor 校验）。

### 2.4 查询云端会话状态
检查云端当前会话文件是否存在、修改时间以及激活状态：
```bash
curl -s -H "Authorization: Bearer $TOKEN" "$SERVER/v1/admin/browser-session/status" | jq .
```
**成功响应示例**：
```json
{
  "success": true,
  "exists": true,
  "path": "/data/nogi-browser-state.json",
  "size": 15420,
  "lastModified": "2026-09-14T12:00:00.000Z",
  "uploadedVersion": "a1b2c3d4e5f6...",
  "activeVersion": "a1b2c3d4e5f6...",
  "activated": true,
  "activationStatus": "active"
}
```

### 2.5 查询持久化错误日志

错误日志接口读取挂载卷中的脱敏 JSONL 记录，因此数据库异常时仍可使用：

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/admin/error-logs?limit=100&level=error" | jq .
```

支持 `limit`（1–500）、`level`、`scope`、`q`、`since` 和 `before` 参数。响应中的
`nextBefore` 可用于读取下一页：

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/admin/error-logs?limit=100&before=<nextBefore>" | jq .
```

### 2.6 访问令牌自动续期机制

Monitor 进程每轮轮询时检查当前 Access Token 的有效期：
- 默认在 Token 过期前 3 分钟（或服务启动初始化时）调用官方 `POST /v2/update_token` 接口完成续期。
- 续期成功后，服务端自动将官方响应中 Set-Cookie 返回的新 `session` Cookie 持久化保存至挂载卷，保持会话持续有效。
- 连续刷新失败达到阈值时转入安全挂起状态，等待新会话上传激活。

---

## 3. 消息记录查询与统计

### 3.1 分页拉取最新消息列表
默认返回最新 50 条消息（按 `sent_at DESC` 排序）：
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/messages?limit=20&offset=0" | jq .
```

### 3.2 按消息类型筛选
支持类型：`text` (文字), `image` (图片), `audio` (语音/来电), `video` (视频)。
```bash
# 只查询最新 10 条语音/来电消息
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/messages?type=audio&limit=10" | jq .

# 只查询最新 10 条图片消息
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/messages?type=image&limit=10" | jq .
```

### 3.3 按特定成员查询消息
传入目标成员的 `member_id`（可通过 4.1 节获取）：
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/messages?member_id=2&limit=20" | jq .
```

### 3.4 查询单条消息完整详情
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/messages/<MESSAGE_ID>" | jq .
```
**响应包含字段**：
- `id`：官方消息全局唯一 ID
- `member_id` / `member_name` / `member_avatar_url`：成员元数据
- `type`：消息类型（text, image, audio, video）
- `text`：正文文字
- `media_url`：受保护的流媒体下载直链（指向 Relay 媒体端口，无需直连 CloudFront）
- `phone_image_url`：来电全屏写真直链
- `duration_seconds`：语音时长
- `sent_at`：发送时间戳

### 3.5 查询全库消息统计概要
快速了解当前数据库中的消息总量与各类型分布：
```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/messages/stats/summary" | jq .
```
**响应示例**：
```json
{
  "success": true,
  "stats": {
    "total": 3528,
    "by_type": {
      "text": 2180,
      "image": 890,
      "audio": 420,
      "video": 38
    },
    "unplayed_audio": 12
  }
}
```

### 3.6 将消息标记为已读/已播放
```bash
curl -X PATCH "$SERVER/v1/messages/<MESSAGE_ID>/played" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 4. 订阅成员查询与分析

### 4.1 从服务端获取所有已同步的成员列表
服务端消息库中存储了已订阅成员的历史与增量消息。使用 `jq` 管道可以快速聚合去重出成员列表：
```bash
curl -s -H "Authorization: Bearer $TOKEN" "$SERVER/v1/messages?limit=1000" \
  | jq '[.messages[] | {member_id, member_name, member_avatar_url}] | unique_by(.member_id)'
```
**输出示例**：
```json
[
  {
    "member_id": "2",
    "member_name": "池田 瑛紗",
    "member_avatar_url": "https://..."
  },
  {
    "member_id": "5",
    "member_name": "一ノ瀬 美空",
    "member_avatar_url": "https://..."
  }
]
```

### 4.2 直接在数据库中统计各成员发信量 (SQL)
如果进入了云端或本地 PostgreSQL 数据库，可执行以下 SQL 分析：
```sql
SELECT 
    member_id, 
    member_name, 
    COUNT(*) AS total_messages,
    COUNT(CASE WHEN type = 'audio' THEN 1 END) AS voice_calls,
    COUNT(CASE WHEN type = 'image' THEN 1 END) AS photos,
    MAX(sent_at) AS last_message_time
FROM messages
GROUP BY member_id, member_name
ORDER BY total_messages DESC;
```

---

## 5. 媒体文件下载与存储排查

Relay 服务端将抓取到的所有语音、图片、视频和来电全屏写真按 **SHA-256 二进制摘要** 保存在持久化卷中，杜绝重复占用。通过独立媒体服务端口（默认 `8081`）提供支持 HTTP Range 断点续传的受保护下载。

### 5.1 媒体下载接口规范
端点结构：`GET /v1/messages/:id/media/:kind`
- `:kind` 支持三种：
  - `media`：原始媒体（语音 `.m4a`、图片 `.jpg`/`.png`、视频 `.mp4`）
  - `thumbnail`：缩略图
  - `phone_image`：语音来电时的全屏高清写真

主 API 与独立媒体服务均提供该路由。Fly 容器内对应端口为 `8080` 和 `8081`，本地默认端口为 `3000` 和 `8081`。

### 5.2 下载并保存语音文件 (音频流)
```bash
# 将语音下载保存为 voice.m4a
curl -H "Authorization: Bearer $TOKEN" \
  -o "voice_<MESSAGE_ID>.m4a" \
  "$MEDIA/v1/messages/<MESSAGE_ID>/media/media"
```

### 5.3 下载并保存原图
```bash
# 将图片下载保存为 image.jpg
curl -H "Authorization: Bearer $TOKEN" \
  -o "image_<MESSAGE_ID>.jpg" \
  "$MEDIA/v1/messages/<MESSAGE_ID>/media/media"
```

### 5.4 下载全屏来电写真背景
```bash
curl -H "Authorization: Bearer $TOKEN" \
  -o "call_bg_<MESSAGE_ID>.jpg" \
  "$MEDIA/v1/messages/<MESSAGE_ID>/media/phone_image"
```

### 5.5 批量下载指定成员的最新语音文件 (Bash 脚本范例)
```bash
#!/bin/bash
MEMBER_ID="2"
echo "正在获取成员 $MEMBER_ID 的语音消息列表..."
MSG_IDS=$(curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/messages?member_id=$MEMBER_ID&type=audio&limit=5" \
  | jq -r '.messages[].id')

for ID in $MSG_IDS; do
  echo "正在下载语音消息 $ID..."
  curl -s -H "Authorization: Bearer $TOKEN" \
  -o "audio_${ID}.m4a" \
  "$MEDIA/v1/messages/${ID}/media/media"
done
echo "下载完成！"
```

### 5.6 云端存储卷排查与用量检查 (Fly.io)
进入云端容器控制台检查磁盘：
```bash
# 登录容器
fly ssh console

# 1. 检查持久化挂载卷用量
df -h /data

# 2. 检查媒体文件去重对象池
ls -lh /data/nogi-media/objects/ | head -n 20

# 3. 统计已归档的媒体文件总数与总大小
du -sh /data/nogi-media/objects/
ls -1 /data/nogi-media/objects/ | wc -l
```

---

## 6. 设备管理与推送测试

### 6.1 查询已注册的客户端设备
```bash
curl -s -H "Authorization: Bearer $TOKEN" "$SERVER/v1/devices" | jq .
```

### 6.2 手动注册设备 (通常由 Android App 自动完成)
```bash
curl -X POST "$SERVER/v1/devices" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "FCM_REGISTRATION_TOKEN...",
    "platform": "android",
    "label": "My Pixel 8"
  }'
```

### 6.3 模拟发送测试消息推送
用于排查手机是否能正常收到系统通知。测试消息不会写入真实消息数据库：
```bash
curl -X POST "$SERVER/v1/push/test-message" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "member_name": "乃木坂46",
    "text": "这是一条服务端发送的测试消息推送！"
  }'
```

### 6.4 模拟发送测试全屏来电
唤醒手机锁屏全屏语音呼入界面与铃声：
```bash
curl -X POST "$SERVER/v1/push/test-call" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "member_name": "池田 瑛紗"
  }'
```

### 6.5 重新推送已存在的特定消息
```bash
curl -X POST "$SERVER/v1/push/send" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "message_id": "<MESSAGE_ID>"
  }'
```

### 6.6 查询近期推送投递日志
排查 FCM 推送状态、成功率与客户端接收历史：
```bash
# 查询最新 20 条推送记录
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/push/logs?limit=20" | jq .

# 按特定消息 ID 追踪推送结果
curl -s -H "Authorization: Bearer $TOKEN" \
  "$SERVER/v1/push/logs?message_id=<MESSAGE_ID>" | jq .
```

### 6.7 注销 / 删除旧设备
如果更换了手机或卸载了 App，可通过设备 ID（从 6.1 节获取）删除过期的设备注册项：
```bash
curl -X DELETE "$SERVER/v1/devices/<DEVICE_ID>" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 7. 数据库初始化与健康检查

### 7.1 主服务探活探针 (无鉴权)
用于 Fly.io / Kubernetes 健康检查探针：
```bash
curl -i "$SERVER/health"
```
响应：`HTTP/1.1 200 OK`，`{"status":"ok","timestamp":"..."}`

### 7.2 媒体流服务探活探针 (无鉴权)
```bash
curl -i "$MEDIA/health"
```
响应：`HTTP/1.1 200 OK`，`{"status":"ok"}`

### 7.3 初始化/升级生产数据库表结构
通过 API 自动执行 `database/schema.sql` 中的 DDL：
```bash
curl -X POST "$SERVER/init-db" \
  -H "Authorization: Bearer $TOKEN"
```
响应：`{"success":true,"message":"Database initialized successfully"}`

---

## 8. 本地脚本与实用运维命令

在 `server/` 目录下提供了一系列即开即用的实用脚本：

| 命令 / 脚本 | 用途说明 |
| :--- | :--- |
| `npm run bootstrap:browser` | 交互式唤起本地浏览器登录官网并提取会话文件 |
| `node upload-session.js <path> <url> <token>` | 将会话文件热上传到云端并等待激活 |
| `npm run audit:blogs` | 审计官方博客 API 完整性与可用性（执行 `scripts/audit-blog-api.js`） |
| `npm test` | 运行 Node.js 内置测试运行器 |
| `sh start-all.sh` | 按生产启动顺序拉起 API、Monitor 与媒体服务（需要可用的 POSIX shell） |
| `npm start` / `npm run monitor` | 分别在两个终端启动 API，以及 Monitor/媒体服务 |

---

> 💡 **相关阅读**：
> - 完整技术架构与各模块实现细节请阅读：[DEVELOPMENT.md](../DEVELOPMENT.md)
> - 云端容器快速部署与运维请阅读：[DEPLOYMENT.md](../DEPLOYMENT.md)
