# Nogi Relay 生产部署指南 (DEPLOYMENT.md)

本文档提供在云端（以 **[Fly.io](https://fly.io)** 为例）快速部署与运维 Nogi Relay 生产服务器的极简实操步骤。开发与架构细节请查看 [DEVELOPMENT.md](DEVELOPMENT.md)。

---

## 目录

- [1. 准备工作](#1-准备工作)
- [2. 快速部署流程](#2-快速部署流程)
  - [步骤 1：创建应用与持久化存储卷](#步骤-1创建应用与持久化存储卷)
  - [步骤 2：设置生产密钥 (Secrets)](#步骤-2设置生产密钥-secrets)
  - [步骤 3：一键部署镜像](#步骤-3一键部署镜像)
  - [步骤 4：初始化生产数据库](#步骤-4初始化生产数据库)
  - [步骤 5：上传官网会话](#步骤-5上传官网会话)
  - [步骤 6：手机客户端连接与推送验收](#步骤-6手机客户端连接与推送验收)
- [3. 会话日常运维与热更新](#3-会话日常运维与热更新)
- [4. 核心生产环境变量参考](#4-核心生产环境变量参考)
- [5. 常用运维与排障命令](#5-常用运维与排障命令)

---

## 1. 准备工作

在开始部署前，请确保准备好以下资源与工具：

1. **[Fly.io 账号与 CLI](https://fly.io/docs/hands-on/install-flyctl/)**：本地已安装 `flyctl` 并完成登录（`fly auth login`）。
2. **PostgreSQL 数据库**：一个外网可访问的 PostgreSQL 数据库连接串（可免费使用 [Neon](https://neon.tech)、[Supabase](https://supabase.com) 或 Fly Postgres）。
3. **Firebase 项目与服务账号密钥**：
   - 在 Firebase 控制台创建项目，启用 Cloud Messaging；
   - 在「项目设置 -> 服务账号」中生成私钥文件（`firebase-admin-key.json`）；
   - 将该文件内容整体转换为 Base64 编码字符串备用：
     ```bash
     # Linux / macOS
     base64 -w 0 firebase-admin-key.json

     # Windows PowerShell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("firebase-admin-key.json"))
     ```

---

## 2. 快速部署流程

> [!TIP]
> **关于应用名称与服务器地址**：
> - 应用名称（`<YOUR_APP_NAME>`）由用户自定义（例如 `my-nogi-relay`）。
> - 部署成功后，服务器公网地址将**自动根据应用名称生成**为：`https://<YOUR_APP_NAME>.fly.dev`，媒体流地址为 `https://<YOUR_APP_NAME>.fly.dev:8081`。
> - 下文命令中的 `<YOUR_APP_NAME>` 请统一替换为自己创建的应用名称。

### 步骤 1：配置与创建应用

在项目根目录下执行：

```bash
# 1. 复制配置模板为 fly.toml，并编辑将其中的 your-app-name 替换为你自定义的应用名
cp fly.toml.example fly.toml

# 2. 创建 Fly 应用（<YOUR_APP_NAME> 自定义；区域推荐日本东京 nrt，距离官网延迟最低）
fly apps create <YOUR_APP_NAME>

# 3. 创建 3GB 持久存储卷（用于保存媒体归档、官网会话和日志）
fly volumes create nogi_media --region nrt --size 3 --app <YOUR_APP_NAME>
```

### 步骤 2：设置生产密钥 (Secrets)

设置必须的生产级凭据（替换为你自己的真实值）：

```bash
fly secrets set \
  ACCESS_TOKEN="自定义token" \
  DATABASE_URL="postgresql://user:pass@host:5432/dbname" \
  FIREBASE_PROJECT_ID="your-firebase-project-id" \
  FIREBASE_PRIVATE_KEY_BASE64="Firebase密钥Base64单行字符串" \
  --app <YOUR_APP_NAME>
```

### 步骤 3：一键部署镜像

```bash
fly deploy --app <YOUR_APP_NAME>
```

> [!NOTE]
> 平台会自动基于根目录的 `Dockerfile` 构建无头浏览器生产镜像，并在容器内部先后启动主 API 服务与 Monitor 监控进程。

### 步骤 4：初始化生产数据库

部署完成后，调用初始化端点完成数据表与索引创建：

```bash
curl -X POST https://<YOUR_APP_NAME>.fly.dev/init-db \
  -H "Authorization: Bearer 自定义token"
```

返回 `{"success": true, "message": "Database initialized successfully"}` 即表示数据库建表完成。

### 步骤 5：上传官网会话

因为云端无头服务器无法手动登录，请在本地电脑提取登录态后上传：

```bash
# 1. 在本地 server 目录安装依赖并提取会话
cd server
npm install
npm run bootstrap:browser
```
- 在自动弹出的浏览器中登录乃木坂46消息账号，确认可看到已订阅成员页面后，回到终端按 **回车**，本地将生成 `nogi-browser-state.json`。

```bash
# 2. 一键上传至云端生产服务器
node upload-session.js ./nogi-browser-state.json https://<YOUR_APP_NAME>.fly.dev 自定义token
```

输出 `✓ Session activated` 即表示云端已成功接管官网会话并启动消息轮询。

> [!WARNING]
> **⚠️重要提示：乃木坂 Message Web 端仅限单一会话（多端互斥）**  
> 乃木坂46 官方 Message 的 Web 端**同时只允许一个设备处于登录态**。会话上传至云端后，**切勿在日常电脑或手机浏览器中再次登录官网 Web 版**。一旦其他设备登录 Web，云端当前持有的 Token 将很快被官方注销，导致服务端被迫停止轮询。日常查看消息请通过 Nogi Relay 原生 App 或 官方移动端 APP。

### 步骤 6：手机客户端连接与推送验收

1. **Android 端连接**：
   - 打开 Nogi Relay App，进入「主页」->「设置」；
   - **服务器地址**：`https://<YOUR_APP_NAME>.fly.dev`（填入你的自定义应用域名）；
   - **访问令牌**：输入前面设置的 `ACCESS_TOKEN`；
   - 保存后主页云朵图标变为绿色连接状态。
2. **端到端测试推送**：
   ```bash
   # 测试普通文本推送
   curl -X POST https://<YOUR_APP_NAME>.fly.dev/v1/push/test-message \
     -H "Authorization: Bearer 自定义token" \
     -H "Content-Type: application/json" \
     -d '{"member_name": "乃木坂46", "text": "生产环境部署与推送验收成功！"}'

   # 测试全屏语音来电
   curl -X POST https://<YOUR_APP_NAME>.fly.dev/v1/push/test-call \
     -H "Authorization: Bearer 自定义token" \
     -H "Content-Type: application/json" \
     -d '{"member_name": "乃木坂46"}'
   ```
   手机收到通知或弹出全屏来电即表示全链路通畅

---

## 3. 会话日常运维与热更新

由于官网 Web 端**严格限制单会话**，如果用户在外部浏览器（如个人电脑或手机浏览器）再次登录了官网网页版，云端服务端的会话就会很快被官方吊销，导致 Token 刷新失败并在日志中输出：
`[NOGI_SESSION_UPDATE_REQUIRED]`。此时服务端会停止轮询并保护系统，**无需重新部署或重启容器**，只需按以下步骤热更新会话：

1. 本地重新提取会话：
   ```bash
   cd server
   npm run bootstrap:browser
   ```
2. 执行上传命令：
   ```bash
   node upload-session.js ./nogi-browser-state.json https://<YOUR_APP_NAME>.fly.dev 自定义token
   ```
服务端会自动无缝重新加载新会话并恢复轮询。

---

## 4. 核心生产环境变量参考

常规部署只需配置前文步骤中的 4 个 Secret。如需自定义，可在 `fly.toml` 的 `[env]` 区块按需覆盖：

| 变量名 | 默认值 / 示例 | 作用与说明 |
| :--- | :--- | :--- |
| `PUBLIC_BASE_URL` | `https://<YOUR_APP_NAME>.fly.dev` | 对外主 API 域名（跟随自定义应用名更改，用于生成媒体与测试直链） |
| `PUBLIC_MEDIA_BASE_URL` | `https://<YOUR_APP_NAME>.fly.dev:8081` | 对外受保护媒体服务访问地址（端口 8081） |
| `NOGI_POLL_INTERVAL_SECONDS` | `60` | 私有消息轮询周期（秒，最低 15s） |
| `NOGI_BLOG_POLL_INTERVAL_SECONDS` | `60` | 公开 BLOG 轮询周期（秒） |
| `NOGI_BACKFILL_ON_START` | `true` | 服务启动时是否沿游标自动回填历史过去消息 |
| `NOGI_MACHINE_MEMORY_RESTART_MB` | `700` | 基于 Linux cgroup `memory.current` 的整机内存重启阈值（MB） |
| `NOGI_BROWSER_SETTLE_SECONDS` | `8` | 官网更新 token 后，后台保存浏览器状态前的等待时间 |
| `NOGI_BROWSER_STORAGE_STATE_TIMEOUT_SECONDS` | `10` | IndexedDB 浏览器状态抓取的最长等待时间 |
| `NOGI_BROWSER_RESTART_INTERVAL_SECONDS` | `0` | 可选定时重启周期；`0` 表示仅使用内存阈值 |

Monitor 的 access token 预刷新窗口固定为 9 秒。内存触发重启时，如果 token 距离到期不超过 3 分钟，Monitor 会等待续期完成，并确认新 token 与刷新后的浏览器状态均已保存后再重启。

> [!NOTE]
> 如果你在 `fly.toml` 中将顶部的 `app = "..."` 直接修改为了你的 `<YOUR_APP_NAME>`，则在执行 `fly deploy` 或 `fly secrets set` 时可省略 `--app <YOUR_APP_NAME>` 参数。

---

## 5. 常用运维与排障命令

```bash
# 查看云端实时日志
fly logs --app <YOUR_APP_NAME>

# 查看实例运行状态与健康检查
fly status --app <YOUR_APP_NAME>

# 查看容器 cgroup 当前/最大内存字节数
fly ssh console --app <YOUR_APP_NAME> -C "cat /sys/fs/cgroup/memory.current"
fly ssh console --app <YOUR_APP_NAME> -C "cat /sys/fs/cgroup/memory.max"

# 检查当前会话激活状态
curl -X GET https://<YOUR_APP_NAME>.fly.dev/v1/admin/browser-session/status \
  -H "Authorization: Bearer 自定义token"

# 查询最近的持久化错误日志
curl -X GET "https://<YOUR_APP_NAME>.fly.dev/v1/admin/error-logs?limit=100&level=error" \
  -H "Authorization: Bearer 自定义token"

# 进入容器终端（如需排查文件）
fly ssh console --app <YOUR_APP_NAME>

# 存储卷在线扩容（如需要）
fly volumes extend nogi_media --size 5 --app <YOUR_APP_NAME>
```
