# Nogi Relay 通用生产部署指南 (DEPLOYMENT.md)

本文档提供在任意 Linux 服务器（VPS / 云主机，如 GCP、AWS、Oracle Cloud、Vultr 等）或 Docker 容器环境中部署与运维 Nogi Relay 生产服务端的完整实操步骤。

项目概览与本地开发运行请查看 [README.md](README.md)，服务端运维命令与 REST API 操作请查看 [server/README.md](server/README.md)。

---

## 目录

- [1. 准备工作](#1-准备工作)
- [2. 方式一：Linux VPS / 云主机部署（推荐）](#2-方式一linux-vps--云主机部署推荐)
  - [步骤 1：安装基础运行环境](#步骤-1安装基础运行环境)
  - [步骤 2：准备代码、持久化目录与依赖](#步骤-2准备代码持久化目录与依赖)
  - [步骤 3：配置生产环境变量 (`.env`)](#步骤-3配置生产环境变量-env)
  - [步骤 4：配置 `systemd` 托管服务](#步骤-4配置-systemd-托管服务)
  - [步骤 5：配置反向代理与 HTTPS (Nginx)](#步骤-5配置反向代理与-https-nginx)
- [3. 方式二：Docker 容器部署](#3-方式二docker-容器部署)
- [4. 初始化数据库、上传官网会话与客户端验收](#4-初始化数据库上传官网会话与客户端验收)
  - [步骤 1：初始化生产数据库](#步骤-1初始化生产数据库)
  - [步骤 2：上传并激活官网会话](#步骤-2上传并激活官网会话)
  - [步骤 3：Android 客户端连接与推送验收](#步骤-3android-客户端连接与推送验收)
- [5. 会话日常运维与热更新](#5-会话日常运维与热更新)
- [6. 核心生产环境变量参考](#6-核心生产环境变量参考)
- [7. 常用运维与排障命令](#7-常用运维与排障命令)

---

## 1. 准备工作

在开始部署前，请确保准备好以下资源：

1. **一台具备公网 IP 的 Linux 服务器**（推荐 Debian 12 / Ubuntu 22.04+，1 vCPU / 1 GB 内存起步；建议选择日本节点，访问乃木坂46官网延迟最低；若为 1 GB 内存小机，建议启用 1 GB Swap）。
2. **HTTPS 域名与证书**：Android 客户端要求同步地址使用有效 HTTPS。可使用自有域名（配合 Let's Encrypt / Certbot 或 Caddy 自动签发），或使用 `<IP横杠形式>.sslip.io` 申请 Let's Encrypt 免费证书。
3. **PostgreSQL 15+ 数据库**：可直接在同台服务器安装本地 PostgreSQL，也可使用外部托管 PostgreSQL（如 Neon、Supabase、RDS 等）。
4. **Firebase 项目与服务账号密钥**：
   - 在 Firebase 控制台创建项目并启用 Cloud Messaging；
   - 在「项目设置 -> 服务账号」中生成私钥 JSON 文件（`firebase-admin-key.json`）；
   - 可直接将私钥文件放到服务端目录（如 `/opt/nogi-relay/firebase-admin-key.json`），或将其转换为 Base64 单行字符串写入环境变量 `FIREBASE_PRIVATE_KEY_BASE64`：
     ```bash
     # Linux / macOS
     base64 -w 0 firebase-admin-key.json

     # Windows PowerShell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("firebase-admin-key.json"))
     ```
5. **两组不同的访问令牌**：生成两个高强度随机字符串分别作为 `ADMIN_TOKEN`（管理员权限）和 `CLIENT_TOKEN`（手机客户端只读/注册权限），两者**必须不同**。
   ```bash
   openssl rand -hex 32
   ```

---

## 2. 方式一：Linux VPS / 云主机部署（推荐）

以 Debian / Ubuntu 为例，将服务端部署至 `/opt/nogi-relay`，持久化媒体、日志与会话统一放在 `/data`。

### 步骤 1：安装基础运行环境

```bash
# 安装 Node.js 20 LTS、PostgreSQL、Nginx、Certbot
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get update
sudo apt-get install -y nodejs postgresql nginx certbot python3-certbot-nginx

# 创建本地数据库与用户（如使用外部托管 PostgreSQL 可跳过此步）
sudo -u postgres psql -c "CREATE USER nogi WITH PASSWORD '请替换为强密码';"
sudo -u postgres psql -c "CREATE DATABASE nogi_relay OWNER nogi;"
```

### 步骤 2：准备代码、持久化目录与依赖

```bash
# 克隆仓库或上传 server/ 目录到 /opt/nogi-relay
sudo mkdir -p /opt/nogi-relay /data/nogi-media /data/nogi-logs
# 示例：将项目中的 server/ 目录内容复制到 /opt/nogi-relay
# sudo cp -r ./server/. /opt/nogi-relay/

cd /opt/nogi-relay
sudo npm ci --omit=dev
sudo chmod +x /opt/nogi-relay/start-all.sh
```

如使用私钥文件方式配置 Firebase，将 `firebase-admin-key.json` 上传至 `/opt/nogi-relay/firebase-admin-key.json` 并收紧权限：

```bash
sudo chmod 600 /opt/nogi-relay/firebase-admin-key.json
```

### 步骤 3：配置生产环境变量 (`.env`)

在 `/opt/nogi-relay/.env` 中写入生产配置（注意替换域名、数据库密码、令牌与 Firebase 配置）：

```ini
NODE_ENV=production
PORT=8080
NOGI_MEDIA_PORT=8081
TRUST_PROXY_HOPS=1

DATABASE_URL=postgresql://nogi:请替换为强密码@127.0.0.1:5432/nogi_relay

ADMIN_TOKEN=请替换为管理员随机Token
CLIENT_TOKEN=请替换为客户端随机Token

FIREBASE_PROJECT_ID=your-firebase-project-id
# 任选其一：文件路径、Base64 或 JSON 字符串
FIREBASE_PRIVATE_KEY_PATH=/opt/nogi-relay/firebase-admin-key.json
# FIREBASE_PRIVATE_KEY_BASE64=Firebase密钥Base64单行字符串

# 对外访问的 HTTPS 根地址（不要带末尾斜杠）
PUBLIC_BASE_URL=https://relay.example.com
PUBLIC_MEDIA_BASE_URL=https://relay.example.com

# 持久化存储目录
MEDIA_STORAGE_DIR=/data/nogi-media
MEDIA_MAX_BYTES=104857600
LOG_STORAGE_DIR=/data/nogi-logs
LOG_MAX_FILE_BYTES=26214400
LOG_RETENTION_DAYS=30

# 乃木坂46 官网轮询配置
NOGI_WEB_URL=https://message.nogizaka46.com
NOGI_API_URL=https://api.message.nogizaka46.com
NOGI_APP_ID=jp.co.sonymusic.communication.nogizaka 2.5
NOGI_APP_PLATFORM=web
NOGI_ORGANIZATION_ID=1
NOGI_POLL_INTERVAL_SECONDS=60
NOGI_MEMBER_SYNC_INTERVAL_SECONDS=1800
NOGI_BACKFILL_ON_START=true
NOGI_BLOG_POLL_INTERVAL_SECONDS=60
NOGI_BLOG_PAGE_SIZE=5
NOGI_BROWSER_STATE_FILE=/data/nogi-browser-state.json
NOGI_ACCESS_TOKEN_STATE_FILE=/data/nogi-access-token.json
NOGI_MAX_TOKEN_REFRESH_FAILURES=3
```

收紧 `.env` 权限：

```bash
sudo chmod 600 /opt/nogi-relay/.env
```

### 步骤 4：配置 `systemd` 托管服务

创建 `/etc/systemd/system/nogi-relay.service`（内置 `start-all.sh` 会同时拉起主 API 进程与 Monitor 监控/媒体进程，任一进程退出时自动由 `systemd` 重启）：

```ini
[Unit]
Description=Nogizaka46 Relay Server & Monitor
After=network.target postgresql.service
Wants=postgresql.service

[Service]
Type=simple
WorkingDirectory=/opt/nogi-relay
EnvironmentFile=/opt/nogi-relay/.env
ExecStart=/bin/sh /opt/nogi-relay/start-all.sh
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

启用并启动服务：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now nogi-relay
sudo systemctl status nogi-relay
```

### 步骤 5：配置反向代理与 HTTPS (Nginx)

在 `/etc/nginx/sites-available/nogi-relay` 写入站点配置（将主 API 与媒体流统一暴露在同一个 HTTPS 域名下，客户端无需额外放行 8081 端口）：

```nginx
server {
    listen 80;
    server_name relay.example.com;

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl http2;
    server_name relay.example.com;

    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    client_max_body_size 10m;

    # 将媒体流路由转发至 Monitor 独立媒体服务 (NOGI_MEDIA_PORT=8081)
    location ~ ^/v1/messages/[^/]+/media/(media|thumbnail|phone_image)$ {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
    }

    # 其余 API、/health 与 /admin 管理后台转发至主 API 服务 (PORT=8080)
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

申请证书并重载 Nginx：

```bash
sudo certbot certonly --nginx -d relay.example.com
sudo ln -sf /etc/nginx/sites-available/nogi-relay /etc/nginx/sites-enabled/nogi-relay
sudo nginx -t && sudo systemctl reload nginx
```

> [!TIP]
> 如果你使用 **Caddy**，只需在 `Caddyfile` 中配置反代规则，Caddy 会自动完成 HTTPS 证书签发与续期。

---

## 3. 方式二：Docker 容器部署

仓库根目录已提供基于 `node:20-slim` 的通用 `Dockerfile`，可在任何支持 Docker / Docker Compose 的主机上运行。

1. 在项目根目录构建镜像：
   ```bash
   docker build -t nogi-relay:latest .
   ```
2. 准备宿主机持久化目录与 `.env` 文件（内容同方式一步骤 3，注意容器内 `DATABASE_URL` 不能写 `127.0.0.1`，应填写宿主机可达地址或外部数据库地址；若采用文件方式挂载 Firebase 密钥，请额外 `-v /path/to/firebase-admin-key.json:/app/firebase-admin-key.json:ro`）：
   ```bash
   mkdir -p /data/nogi-media /data/nogi-logs
   ```
3. 启动容器：
   ```bash
   docker run -d \
     --name nogi-relay \
     --restart unless-stopped \
     --env-file /opt/nogi-relay/.env \
     -v /data:/data \
     -p 127.0.0.1:8080:8080 \
     -p 127.0.0.1:8081:8081 \
     nogi-relay:latest
   ```
4. 按 [步骤 5：配置反向代理与 HTTPS (Nginx)](#步骤-5配置反向代理与-https-nginx) 在宿主机前置 Nginx / Caddy 提供 HTTPS 入口即可。

---

## 4. 初始化数据库、上传官网会话与客户端验收

下文示例统一以 `https://relay.example.com` 表示你的生产服务根地址，请替换为实际域名。

### 步骤 1：初始化生产数据库

服务启动后会自动幂等补齐增量列与表；首次部署建议再显式调用一次初始化端点确认所有表与索引就绪：

```bash
curl -X POST https://relay.example.com/init-db \
  -H "Authorization: Bearer 管理员token"
```

返回 `{"success": true, "message": "Database initialized successfully"}` 即表示初始化成功。

### 步骤 2：上传并激活官网会话

在本地电脑提取官方登录态：

```bash
cd server
npm install
npm run bootstrap:browser
```

- 在弹出的浏览器中登录乃木坂46消息账号，确认可看到已订阅成员页面后，回到终端按 **回车**，本地将生成 `nogi-browser-state.json`。

然后任选以下一种方式上传到服务器：

- **方式 A（命令行脚本上传）**：
  ```bash
  node upload-session.js ./nogi-browser-state.json https://relay.example.com 管理员token
  ```
  输出 `✓ Session activated` 即表示服务端已成功接管官网会话并开始轮询。
- **方式 B（Web 管理后台上传）**：
  访问 `https://relay.example.com/admin`，输入 `ADMIN_TOKEN` 登录后，在「多账号管理」页直接拖拽上传 `nogi-browser-state.json`。

> [!WARNING]
> **乃木坂 Message Web 端单会话限制**
> 官方 Message Web 端同时只允许一个设备处于登录态。会话上传至服务器后，请勿在其他浏览器再次登录官网 Web 版，否则服务端当前会话将被官方注销。

### 步骤 3：Android 客户端连接与推送验收

1. **Android 端连接**：
   - 按 [Android 客户端构建与安装](README.md#3-android-客户端构建与安装) 配置与服务端相同 Firebase 项目的 `google-services.json` 并安装 App；
   - 打开「主页」→「设置」→「连接」→「FCM 推送服务」；
   - **同步服务地址**：`https://relay.example.com`（必须使用 HTTPS）；
   - **访问令牌**：填写 `CLIENT_TOKEN`，用于注册设备、读取消息与媒体；下方管理端测试命令使用 `ADMIN_TOKEN`；
   - 点击「保存并注册推送」，等待提示「设备已注册，系统推送已就绪」；
   - 在主页检查「通知权限」「全屏来电」「后台弹出界面」和「系统推送」，按提示开启相应权限。
2. **端到端测试推送**：
   ```bash
   curl -X POST https://relay.example.com/v1/push/test-message \
     -H "Authorization: Bearer 管理员token" \
     -H "Content-Type: application/json" \
     -d '{"member_name": "乃木坂46", "text": "生产环境部署与推送验收成功！"}'
   ```
   手机收到上述通知后，再用一条新收到的真实订阅语音验证下载、来电显示与接听播放。

   标准版设置中的「测试全屏来电」使用本机测试资源，只检查来电界面与播放。服务端合成语音测试另见 [模拟发送测试全屏来电](server/README.md#74-模拟发送测试全屏来电)。

---

## 5. 会话日常运维与热更新

当官网账号在外部设备登录导致服务端会话失效时，日志中会输出 `[NOGI_SESSION_UPDATE_REQUIRED]`。此时无需重启服务，直接热更新即可：

1. 本地重新提取会话：
   ```bash
   cd server
   npm run bootstrap:browser
   ```
2. 通过脚本或在 `https://relay.example.com/admin` 重新上传：
   ```bash
   node upload-session.js ./nogi-browser-state.json https://relay.example.com 管理员token
   ```
服务端会自动验证并重新加载新会话、恢复轮询。

---

## 6. 核心生产环境变量参考

`ADMIN_TOKEN` 与 `CLIENT_TOKEN` 必须不同；旧 `ACCESS_TOKEN`/`API_KEY` 已不再用于鉴权。完整本地开发模板见 [server/.env.example](server/.env.example)。

| 变量名 | 默认值 / 示例 | 作用与说明 |
| :--- | :--- | :--- |
| `PORT` | `8080`（本地默认 `3000`） | 主 API 与 `/admin` 管理台监听端口 |
| `NOGI_MEDIA_PORT` | `8081` | Monitor 内置独立媒体流服务监听端口 |
| `DATABASE_URL` | `postgresql://...` | PostgreSQL 数据库连接串 |
| `ADMIN_TOKEN` | 必填 | 管理员凭据（管理接口、会话上传、推送测试） |
| `CLIENT_TOKEN` | 必填 | 客户端凭据（设备注册、消息与媒体读取） |
| `FIREBASE_PROJECT_ID` | 必填 | Firebase 项目 ID |
| `FIREBASE_PRIVATE_KEY_PATH` / `_BASE64` / `_JSON` | 三选一 | Firebase Admin SDK 服务账号私钥 |
| `PUBLIC_BASE_URL` | `https://relay.example.com` | 对外主 API 根地址（用于生成测试音频与媒体直链） |
| `PUBLIC_MEDIA_BASE_URL` | `https://relay.example.com` | 对外受保护媒体访问地址（反代合并到同域名时与 `PUBLIC_BASE_URL` 相同） |
| `MEDIA_STORAGE_DIR` | `/data/nogi-media` | 持久化媒体归档目录（含 `objects/` 与增量来电封面 `phone-images/`） |
| `NOGI_POLL_INTERVAL_SECONDS` | `60` | 私有消息轮询周期（秒，最低 `15`） |
| `NOGI_MEMBER_SYNC_INTERVAL_SECONDS` | `1800` | 成员信息与来电封面定期增量同步周期（秒，最低 `60`） |
| `NOGI_BLOG_POLL_INTERVAL_SECONDS` | `60` | 公开 BLOG 轮询周期（秒） |
| `NOGI_BACKFILL_ON_START` | `true` | 服务启动时是否沿游标自动回填历史消息（回填不触发推送） |
| `NOGI_BROWSER_STATE_FILE` | `/data/nogi-browser-state.json` | 兼容单账号模式的会话凭证路径 |
| `NOGI_ACCESS_TOKEN_STATE_FILE` | `/data/nogi-access-token.json` | 访问令牌缓存文件路径 |
| `NOGI_MAX_TOKEN_REFRESH_FAILURES` | `3` | 连续刷新失败触发安全挂起的最大重试次数 |
| `TRUST_PROXY_HOPS` | `1` | 反向代理跳数（单层 Nginx/Caddy 为 `1`，再叠加 CDN 时调大） |

---

## 7. 常用运维与排障命令

### systemd 托管环境

```bash
# 查看服务状态与实时日志
sudo systemctl status nogi-relay
sudo journalctl -u nogi-relay -f

# 更新代码后运行测试并重启
cd /opt/nogi-relay && sudo npm test
sudo systemctl restart nogi-relay

# 检查磁盘与增量封面/媒体归档用量
df -h /data
du -sh /data/nogi-media/objects /data/nogi-media/phone-images
```

### Docker 容器环境

```bash
# 查看容器状态与实时日志
docker ps -f name=nogi-relay
docker logs -f nogi-relay

# 进入容器排障
docker exec -it nogi-relay sh
```

### 通用远程健康检查与接口排查

```bash
# 主服务健康检查
curl -sS https://relay.example.com/health

# 检查当前会话激活状态
curl -sS https://relay.example.com/v1/admin/browser-session/status \
  -H "Authorization: Bearer 管理员token"

# 查询最近的持久化错误日志
curl -sS "https://relay.example.com/v1/admin/error-logs?limit=100&level=error" \
  -H "Authorization: Bearer 管理员token"
```
