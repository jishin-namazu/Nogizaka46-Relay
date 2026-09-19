# Nogi Relay 开发文档 (DEVELOPMENT.md)

本文档详细阐释系统的技术架构、服务端双进程设计、数据库模式演进、REST API 接口规范、Android 客户端底层实现原理（包括全屏来电、大模型多协议翻译管道、博客与媒体归档引擎、客户端归档导入导出）以及本地开发与调试指南。

---

## 目录

- [1. 系统架构全景](#1-系统架构全景)
  - [1.1 总体架构与数据流向](#11-总体架构与数据流向)
  - [1.2 技术栈概览](#12-技术栈概览)
- [2. 服务端架构与核心实现 (server/)](#2-服务端架构与核心实现-server)
  - [2.1 双进程架构与启动编排](#21-双进程架构与启动编排)
  - [2.2 官网浏览器监控与会话状态机 (nogi-browser.js)](#22-官网浏览器监控与会话状态机-nogi-browserjs)
  - [2.3 历史消息回填与分页算法 (Continuation Cursor)](#23-历史消息回填与分页算法-continuation-cursor)
  - [2.4 媒体归档引擎与流式媒体服务 (media.js & media-server.js)](#24-媒体归档引擎与流式媒体服务-mediajs--media-serverjs)
  - [2.5 公开 BLOG 监控与推送引擎 (blog-monitor.js)](#25-公开-blog-监控与推送引擎-blog-monitorjs)
  - [2.6 Firebase Cloud Messaging (FCM) 推送设计与 4KB 容灾策略](#26-firebase-cloud-messaging-fcm-推送设计与-4kb-容灾策略)
  - [2.7 结构化持久错误日志 (error-log.js)](#27-结构化持久错误日志-error-logjs)
- [3. 数据库设计与持久化模型](#3-数据库设计与持久化模型)
  - [3.1 PostgreSQL 生产表结构 (PostgreSQL)](#31-postgresql-生产表结构-postgresql)
  - [3.2 客户端 SQLite 本地数据库演化 (SQLite)](#32-客户端-sqlite-本地数据库演化-sqlite)
- [4. REST API 接口规范参考](#4-rest-api-接口规范参考)
  - [4.1 认证与通用规则](#41-认证与通用规则)
  - [4.2 基础与系统接口](#42-基础与系统接口)
  - [4.3 消息相关接口 (`/v1/messages`)](#43-消息相关接口-v1messages)
  - [4.4 设备注册接口 (`/v1/devices`)](#44-设备注册接口-v1devices)
  - [4.5 推送与测试接口 (`/v1/push`)](#45-推送与测试接口-v1push)
  - [4.6 会话管理与运维接口 (`/v1/admin`)](#46-会话管理与运维接口-v1admin)
- [5. Android 客户端架构与技术细节 (app/)](#5-android-客户端架构与技术细节-app)
  - [5.1 架构设计理念与轻量化选型](#51-架构设计理念与轻量化选型)
  - [5.2 拟真全屏语音来电系统](#52-拟真全屏语音来电系统)
  - [5.3 多大模型翻译流水线 (AI Translation System)](#53-多大模型翻译流水线-ai-translation-system)
  - [5.4 公开 BLOG 客户端直连与离线检索](#54-公开-blog-客户端直连与离线检索)
  - [5.5 归档导入导出与媒体补齐 (Data Transfer)](#55-归档导入导出与媒体补齐-data-transfer)
  - [5.6 通知渠道与后台保活](#56-通知渠道与后台保活)
- [6. 本地开发与调试指南](#6-本地开发与调试指南)
  - [6.1 服务端本地环境搭建](#61-服务端本地环境搭建)
  - [6.2 提取官网会话 (bootstrap-browser.js)](#62-提取官网会话-bootstrap-browserjs)
  - [6.3 Android 客户端本地构建与调试](#63-android-客户端本地构建与调试)
  - [6.4 常见故障排查 (Troubleshooting)](#64-常见故障排查-troubleshooting)
- [7. 许可证与法律声明](#7-许可证与法律声明)

---

## 1. 系统架构全景

### 1.1 总体架构与数据流向

Nogi Relay 针对乃木坂46（Nogizaka46）官方应用无法下载媒体文件、缺少高质量离线检索与翻译支持等痛点而设计。系统采用 **“私有中继中枢 + 原生智能终端”** 的分离式全栈架构。

```mermaid
flowchart TD
    subgraph Sources["1. 乃木坂46 官方数据源"]
        direction LR
        NogiMsg["私有消息 Web API<br/>(订阅制 / 动态 JWT 鉴权)"]
        NogiBlog["公开 BLOG API<br/>(无需登录 / JSONP 数据源)"]
    end

    subgraph RelayServer["2. Nogi Relay 服务端 (Docker 容器)"]
        direction TB
        subgraph MonitorProc["Monitor 监控进程"]
            Browser["Playwright 浏览器监控<br/>• 会话托管与 401 自动续期<br/>• Timeline 增量轮询与游标回填"]
            BlogMon["公开 BLOG 监控器<br/>• 增量追赶与 head_id 推进<br/>• 仅存元数据，不存博客正文"]
            MediaStore[("媒体内容归档<br/>• SHA-256 内容寻址<br/>• 多来电背景物理去重")]
            MediaSrv["专有受保护媒体服务 (:8081)<br/>• Bearer 鉴权流式直出"]
        end

        subgraph ApiProc["API 主进程"]
            RESTAPI["Express REST API<br/>(Fly :8080 / 本地 :3000)<br/>• 设备注册 / 历史同步<br/>• /health 探针 / 会话热更新"]
            DB[(PostgreSQL 数据库<br/>消息 / 博客元数据 / 设备 / 错误日志)]
        end

        FCMService["Firebase Admin SDK<br/>(纯数据高优先级 Payload / 4KB 容灾)"]

        Browser -->|"写入归档文件"| MediaStore
        MediaStore -->|"提供本地流"| MediaSrv
        Browser -->|"入库消息与状态"| DB
        BlogMon -->|"入库博客元数据"| DB
        DB ---|"读写数据"| RESTAPI
        Browser -->|"触发推送"| FCMService
        BlogMon -->|"触发推送"| FCMService
    end

    subgraph PushNetwork["3. 推送通道"]
        FCM["Google Firebase Cloud Messaging<br/>(Android Priority: High / Data-Only)"]
    end

    subgraph AndroidClient["4. Nogi Relay Android 客户端"]
        direction TB
        FCMReceiver["FCM 接收服务<br/>(NogiFirebaseMessagingService)"]

        subgraph InCallSys["拟真全屏语音呼叫系统"]
            PrepService["来电准备前台服务<br/>(静默预载语音与写真)"]
            CallActivity["全屏来电 Activity<br/>(锁屏唤醒 / 贴耳距离传感器息屏)"]
        end

        subgraph StorageSearch["本地离线存储与检索"]
            LocalDB[("本地 SQLite (v9)<br/>离线消息 / 博客 / 成员名录")]
            SearchEngine["全文检索与时间筛选器<br/>(词边界摘要 / 关键词高亮)"]
        end

        subgraph ExtEngines["扩展功能引擎"]
            BlogUI["博客直连阅读器<br/>(段落中日对照 / 多图批量下载)"]
            AITrans["大模型翻译引擎<br/>(11家厂商 / JSON Schema / 格式保真)"]
            Archive["归档导入导出<br/>(zip 导出 / 逐条回写导入 / 媒体补齐)"]
        end

        FCMReceiver -->|"语音来电"| PrepService
        PrepService -->|"就绪唤醒"| CallActivity
        FCMReceiver -->|"普通消息 / 博客更新"| LocalDB
        LocalDB --> SearchEngine
        LocalDB --> BlogUI
        LocalDB -.->|"按需翻译"| AITrans
        LocalDB -->|"导出记录与媒体"| Archive
        Archive -->|"逐条回写导入"| LocalDB
    end

    %% 主干链路 (从上至下平滑推进，避免交叉重叠)
    NogiMsg -->|"1. 监听请求 / 轮询时间线"| Browser
    NogiBlog -->|"1. 定期轮询新博客元数据"| BlogMon
    FCMService -->|"2. 下发数据推送"| FCM
    FCM -->|"3. 唤醒客户端"| FCMReceiver

    %% 客户端与服务端的补充同步链路 (从上层下发至客户端，顺向连接)
    RESTAPI -.->|"历史消息同步"| LocalDB
    MediaSrv -.->|"音频/背景直连拉取"| PrepService

    %% 客户端直连官网 (外侧顺向连接)
    NogiBlog -.->|"正文与大图直连拉取 (不经过服务器端)"| BlogUI
```

### 1.2 技术栈概览

| 维度 | 组件 / 技术 | 关键用途 |
| --- | --- | --- |
| **服务端运行环境** | Node.js (ES Modules, >=20), Ubuntu Linux | 原生支持 Fetch、Async Iterators |
| **服务端 Web 框架** | Express 4, Helmet, CORS, RateLimit | 极速冷启动 |
| **浏览器自动化** | Playwright (Chromium / Headless Shell) | 精准接管并模拟官网 SPA 会话，自动获取 OAuth/Bearer 凭证与执行续期 |
| **数据库存储** | PostgreSQL 15+ (`pg` 连接池) | JSONB 支持、行级排他锁、时间序列排序与完整事务保证 |
| **消息推送** | Firebase Admin SDK (`sendEachForMulticast`) | 高优先级数据消息 |
| **持久化媒体存储** | 本地文件系统 / Docker Volume，SHA-256 寻址 | 规避三方 CDN 授权失效问题，多成员多来电背景物理去重 |
| **Android 开发框架** | Kotlin 1.9+, Jetpack Compose (BOM 2024.05) | 声明式响应式 UI，高度定制 Material 3 组件 |
| **Android 离线存储** | 原生 `SQLiteOpenHelper` (9 次版本平滑迁移) | 零反射、无 Room 额外依赖，极低内存开销，针对 LIKE 与 strftime 深度定制 |
| **网络通信与流处理** | Java 原生 `HttpURLConnection` + Coroutines | 最小化 APK 体积，无 OkHttp/Retrofit 冗余运行时开销 |
| **AI 翻译矩阵** | 兼容 11 家大模型厂商，覆盖 3 种协议标准 | 全文单次上下文推理，JSON Schema 结构化约束，抑制思考模式，保持原文结构 |

---

## 2. 服务端架构与核心实现 (server/)

### 2.1 双进程架构与启动编排

为了确保外部健康检查、会话管理以及排障接口在浏览器渲染崩溃或高负载时不被阻塞，Nogi Relay 服务端在单个 Docker 容器内采用 **双进程物理隔离模型**：

1. **API 主进程 (`src/index.js`)**：
   - 监听内部端口 `8080`（通过 Fly.io 映射至公共端口 `80` 和 `443`）。
   - 职责：提供 REST API、设备注册、历史消息检索、手动推送测试、`/health` 健康探针、数据库自动兼容迁移（`ensureMessageMediaColumns`）。
2. **Monitor 监控进程 (`src/monitor/index.js`)**：
   - 内部启动专有媒体服务器 (`media-server.js`，监听端口 `8081`)。
   - 启动私有消息浏览器监控器 (`nogi-browser.js`)。
   - 启动公开博客监控器 (`blog-monitor.js`)。

#### 启动顺序控制 (`start-all.sh`)
```sh
#!/bin/sh
# 1. 优先启动 API 主进程
npm start &
API_PID=$!

cleanup() { kill "$API_PID" 2>/dev/null || true; }
trap cleanup INT TERM EXIT

# 2. 轮询检测本机 /health 接口直至返回 200 OK
until node -e "fetch('http://127.0.0.1:${PORT:-8080}/health').then(r => { if (!r.ok) process.exit(1); }).catch(() => process.exit(1))"; do
  if ! kill -0 "$API_PID" 2>/dev/null; then
    wait "$API_PID"
    exit $?
  fi
  sleep 1
done

echo "API health check passed; starting monitor services"
# 3. API 进程健康后，再启动包含 Chromium 的 Monitor 进程
npm run monitor
```
该编排策略有效避免了 Chromium 启动耗时导致的平台部署探针超时（如 Fly.io `grace_period` 期间的假死判定）。

---

### 2.2 官网浏览器监控与会话状态机 (`nogi-browser.js`)

#### 2.2.1 认证凭据捕获机制
乃木坂46官网移动端 Web 版采用 SPA 架构。用户登录态由浏览器 Cookie、`localStorage` 和 IndexedDB 中的会话数据共同维持，API 实际调用依赖短期 JWT Access Token。

`NogiBrowserMonitor` 不会直接在 Node.js 中逆向模拟 OAuth/AWS Cognito 签名算法，而是**直接运行一个无头 Chromium 实例托管官网前端**：
- Chromium 加载并持久化保存的 `nogi-browser-state.json` 会话（Cookies、LocalStorage 与 IndexedDB）。
- 通过 `context.on('request')` 监听并拦截页面流出的网络请求，只要命中 `https://api.message.nogizaka46.com` 且携带 `Authorization: Bearer <token>`，即在内存中更新 `this.accessToken`。
- 解码 JWT Payload 获得 `exp` 过期时间戳，并使用 `ACCESS_TOKEN_REFRESH_SKEW_MS = 9秒`，确保进入官网约 10 秒的刷新窗口后才要求新 token。
- `/v2/update_token` 成功后只安排一次后台浏览器状态保存；刷新主流程不等待状态序列化。状态抓取默认 10 秒超时，并禁止重叠执行。

#### 2.2.2 状态机流转与容错隔离

```mermaid
stateDiagram-v2
    [*] --> Starting: 进程初始化
    Starting --> Authenticated: 加载会话并截获有效 Token
    
    state Authenticated {
        [*] --> Polling: 轮询消息 / 导入历史
        Polling --> TokenExpiring: JWT 即将到期 / 接口 401
        TokenExpiring --> PageRefresh: 驱动页面触发 /v2/update_token
        PageRefresh --> Polling: 截获新 Token，重试原请求
    }

    Authenticated --> SignedOut: /v2/update_token 返回 400 (凭据彻底失效)
    Authenticated --> AuthPaused: 连续刷新失败 >= N 次 (网络/风控)
    
    state SignedOut {
        CloseBrowser: 立即关闭 Chromium 并停机轮询
        PeriodicLog: 每 5 分钟输出一次会话更新警告
        WaitNewSession: 等待 Admin API 热上传会话
    }

    state AuthPaused {
        SleepWait: 暂停轮询
        WaitRecovery: 等待手动干预或热更新
    }

    SignedOut --> Authenticated: POST /v1/admin/browser-session (激活并验证成功)
    AuthPaused --> Authenticated: POST /v1/admin/browser-session (激活并验证成功)
```

1. **`authenticated`（正常态）**：
   - 保持每 60 秒（`NOGI_POLL_INTERVAL_SECONDS`）轮询一次已订阅成员的时间轴。
2. **401 故障单次重试**：
   - 若轮询时官方 API 突然返回 `401 Unauthorized`，立即触发 `refreshFrontendSession()`。
   - 刷新后**仅允许针对当前失败的 API 重新发起单次重试**；若依旧 401，则向轮询状态机抛出鉴权错误。`consecutiveAuthFailures` 由 `/v2/update_token` 的失败响应累计。
3. **`signedOut`（登出态）**：
   - 若官方 `/v2/update_token` 接口明确响应 `400 Bad Request`，代表 Refresh Token 已被官方吊销或在其他设备登录被踢出。
   - **⚠️ 官方单会话限制机制**：乃木坂46 官方 Message Web 平台存在严格的**单会话互斥策略**。如果用户在外部设备（日常电脑或手机浏览器）再次登录官网网页版，官方后台会很快将前一个会话（服务端所在设备）的凭据注销。
   - **立即关闭 Chromium 实例并停止所有轮询**，阻止无意义的流量空耗；每 5 分钟在日志中输出一次标准提示：`[NOGI_SESSION_UPDATE_REQUIRED]`。
4. **`authPaused`（鉴权冻结态）**：
   - 若网络超时或非 400 异常导致连续失败达到阈值（默认 3 次），挂起轮询。
   - 进入 `signedOut` 或 `authPaused` 后会关闭 Chromium 并停止私信轮询，API 进程、健康检查和会话上传接口继续运行。由于 API 与 Monitor 仍共享同一 Fly Machine/cgroup，极端整机资源压力仍可能影响响应延迟。

#### 2.2.3 内存守护与主动重启策略
为防止 Headless Chromium 长期运行造成整机内存压力，Monitor 实行双重守护策略：
- **Linux cgroup 整机内存阈值**：每轮轮询读取 `/sys/fs/cgroup/memory.current` 与 `memory.max`，默认在 `NOGI_MACHINE_MEMORY_RESTART_MB=700` 时触发浏览器回收，不再使用 `process.memoryUsage().rss`。
- **Token 安全门**：token 剩余有效期超过 3 分钟时允许直接重启；不超过 3 分钟时等待进入 9 秒续期窗口，并依次确认新 access token 已截获、刷新后的浏览器状态已保存，之后才执行重启。保存失败或超时会暂缓重启。
- **定时重启（默认关闭）**：`NOGI_BROWSER_RESTART_INTERVAL_SECONDS` 支持配置定时重启周期（设为 `0` 时禁用定时重启，仅保留 cgroup 内存自愈）。

---

### 2.3 历史消息回填与分页算法 (Continuation Cursor)

在官方私有消息体系中，成员对话消息使用带分页游标的 timeline 接口：
`GET /v2/groups/:groupId/timeline?order=desc&count=200&continuation=:cursor`

#### 全量回填与增量轮询算法
1. **触发时机**：
   - Monitor 服务启动时（`NOGI_BACKFILL_ON_START=true`）；
   - 运行中新订阅的成员首次被发现时；
   - 外部通过 Admin API 上传新会话激活成功后。
2. **回填算法流**：
   - 针对指定群组调用 `/timeline` 获取首批 200 条消息。
   - 若接口返回中包含有效的 `continuation` 游标，则**持续沿游标深度遍历直至其为空或没有更多历史**，且沿途全部入库并触发媒体归档。
   - 在历史遍历过程中，新入库的历史消息均被标记为 `isNew=false`，**绝对不会向用户推送重复的 FCM 通知**。
3. **日常增量轮询**：
   - 完成回填的群组在后续周期中仅请求第一页（最新 200 条），对比本地已存在的最高消息 ID，遇已入库消息立即熔断轮询，极大地节省了上游带宽。

---

### 2.4 媒体归档引擎与流式媒体服务 (`media.js` & `media-server.js`)

#### 2.4.1 SHA-256 内容寻址与物理去重
官方消息中的图片、音频、视频，以及语音来电所使用的全屏背景图片，通常挂载于私有 AWS CloudFront 上，且直链带有过期时间或防盗链校验。

1. **去重存储结构**：
   - 所有归档文件保存在 `/data/nogi-media/objects/<SHA-256>.<ext>`。
   - 文件名即为其二进制内容的 SHA-256 哈希值。
2. **来电背景高效复用**：
   - 成员每次发起“语音来电”时，通常会重复使用相同的写真作为通话背景。
   - `MediaArchive` 在内存中建立 `url -> SHA-256` 映射缓存。对于已存在的哈希对象，直接在数据库 `messages` 记录中关联相同的 `phone_image_local_path`，**相同字节在磁盘上永远只存一份**。
3. **安全流式写入**：
   - 下载时先向 `/data/nogi-media/.tmp/` 写入唯一临时文件，全程以单向管道流计算哈希。
   - 检验体积不超过限制（默认 `MEDIA_MAX_BYTES = 100MB`）且网络请求完整后，通过原子重命名（`fs.rename`）移入 `objects/` 目录。

#### 2.4.2 独立受保护媒体服务 (`media-server.js`)
- 在 Monitor 进程中监听 `8081` 端口。
- 路由匹配：`GET /v1/messages/:id/media/:kind`（`:kind` 支持 `media`、`thumbnail`、`phone_image`）。
- **强制鉴权**：必须在 Header 中携带 `Authorization: Bearer <ACCESS_TOKEN>`，杜绝未经授权的公开盗链。
- **流式响应**：利用 Node.js `ReadStream` 配合动态 MIME 类型推导流式返回，设置 `Cache-Control: private, max-age=86400`，支持高并发音视频断点式流式播放。

---

### 2.5 公开 BLOG 监控与推送引擎 (`blog-monitor.js`)

#### 2.5.1 无凭据轻量级轮询
乃木坂46官方博客属于完全公开的内容，使用独立的 JSONP 接口，无需会员登录：
`GET https://www.nogizaka46.com/s/n46/api/list/blog?rw=:limit&st=:offset`
返回格式如：`res({"count":"12345","data":[...]});`

#### 2.5.2 边界推进算法与零缺口容错
为了避免像传统 RSS 监控那样在网络抖动时漏抓或在多页间产生跳空，系统在 `blog_sync_state` 表中持久化保存当前已确认同步的头部博客 ID（`head_id_v1`）：

```text
[首次建立基线 (Baseline)]
Step 1: 查询发现 head_id_v1 为空。
Step 2: 完整翻页抓取全部历史博客（从最新一直翻到第一篇），全量入库。
Step 3: 将全部历史博客的 notification_suppressed 置为 true（抑制推送）。
Step 4: 将当期最新博客 ID 固化为 head_id_v1。

[日常增量追赶 (Catch-up)]
Step 1: 从 offset=0 开始，每次拉取 5 篇（NOGI_BLOG_PAGE_SIZE=5）。
Step 2: 检查当前批次中是否包含 head_id_v1：
        ├─ 包含: 说明已追赶上历史进度，入库新增部分，并将本次首篇 ID 更新至 head_id_v1。
        └─ 不包含: 说明新增了 5 篇以上，入库当批并继续 offset += 5 向后追赶，直到触碰 head_id_v1。
Step 3: 触发 FCM 推送（仅对本次轮询新入库且未抑制的博客推送）。
```
> [!NOTE]
> **正文分离原则**：Relay 服务端在 `blog_posts` 表中仅保存用于去重、防漏和推送通知的基础元数据（ID、成员名、标题、头图、发布时间、链接），不持久化存储博客正文 HTML 或评论。Android 客户端在全量与增量内容同步时直接从官网获取正文并写入本地 SQLite。

---

### 2.6 Firebase Cloud Messaging (FCM) 推送设计与 4KB 容灾策略

推送模块位于 `src/services/firebase.js` 和 `src/services/push.js`。

#### 2.6.1 纯数据高优先级推送 (`Data-Only High Priority`)
所有 FCM 消息均不设置 `notification` 负载，只配置 `data` 字段，且声明 `android.priority = "high"`。
- **核心动机**：若携带 `notification` 载荷，当应用处于后台或被杀进程时，Android 系统托盘将自动弹出简易通知，跳过客户端代码逻辑，导致全屏来电唤醒失败、无法静默预下载媒体。纯数据消息则始终交由 `NogiFirebaseMessagingService.onMessageReceived` 处理。

#### 2.6.2 FCM 4096 字节限制与降级策略
FCM 规定单个消息的 `data` 键值对总长度不得超过 4096 字节。超长文本消息若硬塞入 payload 会导致整批推送遭 Google 拒绝。

`firebase.js` 中的 `buildDataPayload` 实现了精准的字节级安全防护：
```javascript
const FCM_DATA_LIMIT_BYTES = 4096;
const FCM_DATA_SAFETY_MARGIN_BYTES = 256; // 预留安全边际

export function buildDataPayload(message, includePayload) {
  const data = { message_id: message.id, type: message.type };
  if (!includePayload) return data;

  const payload = JSON.stringify(pushPayload(message));
  const candidate = { ...data, payload };
  
  // 按照 UTF-8 真实字节数（Buffer.byteLength）而非字符数计算
  const encodedBytes = Buffer.byteLength(JSON.stringify(candidate), 'utf8');
  if (encodedBytes <= FCM_DATA_LIMIT_BYTES - FCM_DATA_SAFETY_MARGIN_BYTES) {
    return candidate; // 正常内联完整数据
  }

  // 超出预算时：剔除 payload，仅发送 message_id 与 type
  console.log(`FCM inline payload omitted for ${message.id}: exceeds safe budget`);
  return data;
}
```
当 Android 客户端收到没有 `payload` 字段的消息时，会自动触发 `RelayClient.fetchMessage(messageId)` 通过标准 REST API 补全完整数据。

---

### 2.7 结构化持久错误日志 (`error-log.js`)

在 Fly.io 等无状态 PaaS 容器上，容器崩溃重启往往导致终端历史日志丢失，给复盘排查带来巨大困难。系统设计了双重持久化日志引擎：
1. **数据库落盘 (`error_logs` 表)**：将每一次未捕获异常、认证失效、FCM 报错序列化后异步写入 PostgreSQL。
2. **本地持久卷双写 (`/data/nogi-logs/errors-YYYY-MM-DD.jsonl`)**：即便数据库断连，也会记录到本地挂载卷；单文件达到上限后使用 `.1` 至 `.3` 后缀轮转。
3. **脱敏保护**：内置敏感字段过滤器，自动对 `authorization`、`cookie`、`token`、`password`、`private_key` 进行 `[REDACTED]` 脱敏。
4. **只读运维接口**：`GET /v1/admin/error-logs` 直接读取持久卷副本，支持数量、级别、scope、关键字与时间范围筛选，因此数据库异常时仍可用于排障。

---

## 3. 数据库设计与持久化模型

### 3.1 PostgreSQL 生产表结构 (PostgreSQL)

初始化结构位于 `server/database/schema.sql`。本地通过 `npm run db:setup` 执行，生产环境通过带 Bearer 鉴权的 `POST /init-db` 执行。

```mermaid
erDiagram
    devices {
        SERIAL id PK
        TEXT fcm_token UK
        VARCHAR platform
        VARCHAR label
        VARCHAR user_id
        TIMESTAMP last_seen_at
        TIMESTAMP created_at
    }

    messages {
        VARCHAR id PK
        VARCHAR member_id
        VARCHAR member_name
        TEXT member_avatar_url
        TEXT phone_image_url
        VARCHAR type
        TEXT text
        TEXT media_url
        TEXT thumbnail_url
        INTEGER duration_seconds
        TIMESTAMP sent_at
        VARCHAR incoming_call_from
        TEXT ringtone_url
        BOOLEAN is_played
        JSONB original_data
        TEXT media_local_path
        TEXT thumbnail_local_path
        TEXT phone_image_local_path
        TIMESTAMP created_at
    }

    push_logs {
        SERIAL id PK
        VARCHAR message_id FK
        INTEGER device_id FK
        TEXT fcm_message_id
        VARCHAR status
        TEXT error_message
        TIMESTAMP created_at
    }

    error_logs {
        BIGSERIAL id PK
        TIMESTAMPTZ created_at
        VARCHAR level
        VARCHAR scope
        TEXT message
        VARCHAR error_name
        VARCHAR error_code
        TEXT stack
        JSONB context
        VARCHAR process_group
        VARCHAR machine_id
    }

    blog_posts {
        VARCHAR id PK
        VARCHAR member_id
        VARCHAR member_name
        TEXT member_avatar_url
        TEXT title
        TEXT image_url
        TIMESTAMPTZ published_at
        TEXT post_url
        BOOLEAN notification_suppressed
        TIMESTAMPTZ notification_attempted_at
        TIMESTAMPTZ discovered_at
    }

    blog_sync_state {
        TEXT state_key PK
        TEXT state_value
        TIMESTAMPTZ updated_at
    }

    messages ||--o{ push_logs : "triggers"
    devices ||--o{ push_logs : "receives"
```

#### 关键索引设计
- `idx_messages_sent_at`：`messages(sent_at DESC)` —— 支撑客户端按时间倒序同步历史。
- `idx_messages_type`：`messages(type)` —— 区分媒体与文字类型。
- `idx_messages_is_played`：`messages(is_played) WHERE type = 'audio'` —— 部分索引，加速未播放语音统计。
- `idx_blog_posts_published_at`：`blog_posts(published_at DESC)` —— 优化博客时间线分页。

---

### 3.2 客户端 SQLite 本地数据库演化 (SQLite)

客户端通过 `app/.../data/MessageDatabase.kt` 维护独立的本地 SQLite 数据库（`messages.db`，版本 `DB_VERSION = 10`），无缝兼容平滑升级：

1. **核心数据表设计**：
   - `messages`：私信消息主表（消息 ID、成员标识与姓名、消息类型、正文内容、媒体/写真 URL、发送时间戳、未读状态 `is_unread`、已播放状态 `is_played` 等）。
   - `blog_posts`：官方博客表（博客 ID、成员信息、标题、HTML 正文、首图 URL、发布时间、译文内容与完成标记、未读标记 `is_unread` 等）。
   - `blog_members`：官方成员花名册（成员 ID `id`、姓名 `name`、期别 `category`、头像 `avatar_url`、展示顺序 `display_order`、最新发文时间 `latest_post_at`、卒業标记 `graduated`）。
   - `sync_state`：同步游标表（保存博客增量同步头部 `blog_sync_head_id_v2`、消息增量同步头部 `message_sync_head_id_v1` 等同步基线）。

2. **数据库版本演化路径 (v1 ~ v10)**：
   - **v1 - v3**：基础消息与媒体本地存储模型。
   - **v4**：引入私信未读状态字段 `is_unread` 与高性能复合索引 `idx_messages_unread_member`。
   - **v5**：新增公开博客表 `blog_posts` 与同步状态表 `sync_state`。
   - **v6**：博客表增加 `is_unread` 未读标记与倒序复合索引 `idx_blog_posts_unread`。
   - **v7**：新增 `blog_members` 成员目录表；重置旧版翻译缓存以适配最新段落骨架回填算法。
   - **v8**：成员表增加 `latest_post_at` 字段并维护最新发帖时间索引，支撑期别分类与活跃度排序。
   - **v9**：成员表增加 `graduated` 卒業标记字段，卒業状态随名册刷新与归档导入单向保留。
   - **v10**：清理早期导入遗留的补零博客 ID 重复行（旧爬虫镜像 `000295` 与官方接口 `295` 指向同一篇），并在写入前统一 ID 写法，避免重复导入再次产生重复文章与图片重复下载。

3. **高效聚合查询设计 (`latestMessagePerMember`)**：
   - 会话抽屉与列表采用 SQLite 原生分组聚合（`CASE WHEN TRIM(member_id) <> '' THEN member_id ELSE member_name END` 结合 `MAX(sent_at)`）；
   - 在数据库底层单次选出所有订阅成员各自最新的消息记录。

---

## 4. REST API 接口规范参考

### 4.1 认证与通用规则
除 `/health` 外，所有接口均须在 HTTP 请求头中提供 Bearer Token：
```http
Authorization: Bearer <ACCESS_TOKEN>
```
请求频率限制：默认 15 分钟内最多 100 次请求（由 `express-rate-limit` 拦截）。

---

### 4.2 基础与系统接口

#### `GET /health`
- **认证**：无需认证
- **响应示例**：
```json
{
  "status": "ok",
  "timestamp": "2026-09-14T10:00:00.000Z",
  "uptime": 12450.5
}
```

#### `POST /init-db`
- **认证**：必须
- **用途**：幂等执行全套表结构创建、索引补充与触发器绑定。首次搭建或手动升级时使用。

---

### 4.3 消息相关接口 (`/v1/messages`)

#### `GET /v1/messages`
分页获取历史消息列表。
- **查询参数**：
  - `limit` (int, 默认 50, 最大 1000): 拉取数量。
  - `offset` (int, 默认 0): 游标偏移。
  - `type` (string, 可选): 消息类型过滤 (`text` / `image` / `audio` / `video`)。
  - `member_id` (string, 可选): 按指定成员过滤。
- **响应示例**：
```json
{
  "success": true,
  "messages": [
    {
      "id": "123456789",
      "member_id": "48",
      "member_name": "井上 和",
      "member_avatar_url": "https://...",
      "phone_image_url": "https://relay.example.com:8081/v1/messages/123456789/media/phone_image",
      "type": "audio",
      "text": null,
      "media_url": "https://relay.example.com:8081/v1/messages/123456789/media/media",
      "thumbnail_url": null,
      "duration_seconds": 15,
      "sent_at": "2026-09-14T09:30:00.000Z",
      "incoming_call_from": "井上 和",
      "ringtone_url": null,
      "is_played": false,
      "created_at": "2026-09-14T09:30:05.000Z"
    }
  ],
  "pagination": { "limit": 50, "offset": 0, "count": 1 }
}
```

#### `GET /v1/messages/:id`
获取单条消息详情。

#### `GET /v1/messages/stats/summary`
获取服务端全量消息统计。
- **响应示例**：
```json
{
  "success": true,
  "stats": {
    "total": 3520,
    "by_type": { "text": 2100, "image": 1100, "audio": 280, "video": 40 },
    "unplayed_audio": 12
  }
}
```

#### `PATCH /v1/messages/:id/played`
将某条语音消息标记为已播放。

#### `GET /v1/messages/:id/media/:kind`
获取归档媒体流（由 API 进程或 Media Server 代理）。`:kind` 可取 `media`、`thumbnail`、`phone_image`。支持 `Range` 请求。

---

### 4.4 设备注册接口 (`/v1/devices`)

#### `POST /v1/devices`
向服务端注册/更新 Android 设备的 FCM Token。
- **请求体**：
```json
{
  "token": "dKjs8...fcm_token...",
  "platform": "android",
  "label": "Pixel 8 Pro"
}
```
- **响应状态**：新增返回 `201 Created`，已存在返回 `200 OK`。

#### `GET /v1/devices`
获取当前已登记的设备列表。

#### `DELETE /v1/devices/:id`
注销失效设备。

---

### 4.5 推送与测试接口 (`/v1/push`)

#### `POST /v1/push/test-message`
创建一条瞬态虚拟文字消息并立即推送到所有设备，**不写入数据库与推送日志**，专用于验收 FCM 端到端通路。
- **请求体**：
```json
{
  "member_name": "池田 瑛紗",
  "text": "这是一条联调测试消息"
}
```

#### `POST /v1/push/test-call`
生成一条包含合成音频的瞬态语音来电并推送到全部注册设备，用于测试 Android 客户端锁屏全屏呼叫、振动与音频路由。
- **请求体**：
```json
{ "member_name": "池田 瑛紗" }
```

#### `GET /v1/push/test-call-audio.wav`
返回一段程序合成的 440Hz 纯正弦波音频（带淡入淡出包络），配合 `test-call` 供客户端预下载使用。

#### `GET /v1/push/logs`
查看历史推送详情与失败原因排查。

---

### 4.6 会话管理与运维接口 (`/v1/admin`)

#### `POST /v1/admin/browser-session`
在线热更新 Playwright 浏览器状态（无需重新部署应用即可恢复鉴权）。
- **请求体**：
```json
{
  "session": {
    "cookies": [...],
    "origins": [...]
  }
}
```
- **机制**：原子写入临时文件并重命名为 `nogi-browser-state.json`（权限 `0600`），Monitor 监听文件变更后立即在无头浏览器中重载并校验。
- **响应**：返回 `202 Accepted` 及对应的 `requestId` 与 `version`。

#### `GET /v1/admin/browser-session/status`
查询服务端当前会话文件的状态、激活进度及最新报错。

#### `GET /v1/admin/error-logs`
读取最新的脱敏持久错误日志。支持 `limit`（1–500）、`level`、`scope`、`q`、`since` 和 `before` 查询参数；需要 Bearer Token 认证。

---

## 5. Android 客户端架构与技术细节 (app/)

### 5.1 架构设计理念与轻量化选型

Android 客户端采用轻量、高内聚的模块化架构设计，注重快速冷启动与低运行时开销：
1. **极速冷启动**：无代码生成开销（KSP/APT），无动态代理反射。
2. **轻量单例图 (`AppGraph.kt`)**：简单的全局上下文依赖持有者，提供 `database`、`settings`、`relayClient`、`blogClient` 与各类专用 `CoroutineDispatcher` 调度器。
3. **轻量 Entry 与职责单一**：
   - **`MainActivity.kt`**：极简 Android `ComponentActivity` 入口（精简至 180+ 行），仅聚焦于系统生命周期分发、耳部距离传感器息屏调度、Intent 路由与前台服务启动。
   - **`ContentSyncManager.kt`**：独立的内容同步中枢（位于 `data/sync/`），统筹消息与博客的全量回填、增量追赶及并发边界安全推进。
   - **`RelayApp.kt`**：根界面脚手架（位于 `ui/navigation/`），承载基于 `AppTab`（主页、消息、博客）的全局底部导航与圆角矩形指示器（`RelayNavigationBarItem`）。
4. **业务功能模块化划分**：
   - **`ui/home/`**：主页仪表盘、系统权限能力卡片与 FCM 连通状态（`HomeScreen`）。
   - **`ui/messages/`**：私信业务闭环（`MessagesViewModel`, `MessagesScreen`, `MemberInbox`, `MessageCard`），支持时间区间筛选与分页浏览。
   - **`ui/settings/`**：服务地址配置、昵称占位符与 11 家大模型翻译参数面板（`SettingsSection`）。

> 💡 **实机呈现参考**：
> - 主页仪表盘与权限卡片：[01_home_dashboard.jpg](docs/images/01_home_dashboard.jpg)
> - 消息主列表与最近收到头像：[03_messages_list.jpg](docs/images/03_messages_list.jpg)

---

### 5.2 拟真全屏语音来电系统

还原官方 App 真实的“电话呼入”体验，是本项目在移动端的核心工程亮点。

```mermaid
sequenceDiagram
    autonumber
    actor Idol as 成员发布语音
    participant FCM as FCM 推送通道
    participant Service as NogiFirebaseMessagingService
    participant Prep as IncomingCallPreparationService
    participant Downloader as MediaDownloader
    participant Activity as IncomingCallActivity
    participant Sensor as OfficialProximityScreenControl
    participant Player as VoicePlaybackService

    Idol ->> FCM: 产生音频消息 (type=audio)
    FCM ->> Service: onMessageReceived(data)
    Service ->> Prep: 启动前台准备服务 (dataSync)
    Prep ->> Downloader: 预下载音频与背景照片到本地缓存
    Downloader -->> Prep: 下载完成
    Prep ->> Activity: 触发全屏 Intent (showWhenLocked, turnScreenOn)
    
    rect rgb(245, 240, 255)
    Note over Activity: 锁屏唤醒，呈现呼叫界面
    Activity ->> Activity: 播放铃声 + 启动振动
    Activity ->> Sensor: 启用距离传感器监听
    end

    alt 用户接听
        Activity ->> Player: 启动音频播放前台服务 (mediaPlayback)
        Activity ->> Activity: 切换至通话计时界面 (PLAYING)
        Sensor ->> Activity: 靠近耳朵: 触发 PROXIMITY_SCREEN_OFF 息屏
        Sensor ->> Activity: 离开耳朵: 亮屏
        Player -->> Activity: 播放完毕，自动挂断退出
    else 用户拒接 / 超时
        Activity ->> Activity: 停止振动与铃声，关闭页面
    end
```

#### 关键技术实现要点：
1. **音频预下载保障机制 (`IncomingCallPreparationService`)**：
   - 采用 **“预载完成方才启动”** 策略：收到推送后，先启动前台准备服务静默完成音频及背景写真下载；一旦本地资源就绪，立刻唤醒 `IncomingCallActivity`；若网络异常下载失败，平滑降级为普通消息通知提醒重试，规避边播边下时因网络抖动产生的卡顿。
2. **全权限锁屏唤醒 (`AndroidManifest.xml` & `Activity`)**：
   - 配置 `android:showWhenLocked="true"`、`android:turnScreenOn="true"`、`android:excludeFromRecents="true"`。
   - Android 12+ (API 31+) 适配：使用 `setPendingIntentCreatorBackgroundActivityStartMode(MODE_BACKGROUND_ACTIVITY_START_ALLOWED)` 保证后台强行拉起能力。
   - Android 14+ (API 34+) 动态检查 `USE_FULL_SCREEN_INTENT` 特殊权限。
3. **距离传感器防误触 (`OfficialProximityScreenControl`)**：
   - 接听电话贴近耳旁时，关闭屏幕防止面部误触。
   - 底层使用 `PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK` 配合专有标记 `WAKE_LOCK_TAG = "com.sonydna.messages.app:VoicePlayerWakeLock"`，复刻官方应用的传感器休眠行为。
4. **音频外放与听筒无缝切换**：
   - 注册 `AudioDeviceCallback`，动态感知蓝牙耳机或有线耳机的拔插。
   - 默认通话根据扬声器开关动态路由音频流至 `STREAM_VOICE_CALL` 或 `STREAM_MUSIC`。

---

### 5.3 多大模型翻译流水线 (AI Translation System)

#### 5.3.1 支持厂商矩阵与协议抽象
客户端统一 `AIProvider` 接口，支持 11 家大模型平台，划分为 3 种协议标准：
- **`MESSAGES` 协议**：Anthropic Claude（官方 `/v1/messages`）。
- **`RESPONSES` (OpenAI 兼容协议)**：OpenAI、Kimi (Moonshot)、DeepSeek、智谱 GLM、通义千问 Qwen、xAI Grok、MiniMax、小米 MiMo、腾讯混元。
- **`GEMINI_GENERATE_CONTENT` 协议**：Google Gemini 原生 REST 接口。

#### 5.3.2 结构化输出保证 (`JsonOutputSupport`)
为了解决大模型在输出时带 Markdown 标记、前后缀客套话或打乱句子顺序：
1. **API 级严格模式**：
   - Claude、OpenAI Responses、Gemini 发送符合标准的 JSON Schema，强制模型仅能解码输出符合 `{"segments":[{"index":0,"text":"..."}]}` 的格式。
2. **Prompt 级强校验**：
   - 不支持 JSON Schema 的模型（如 DeepSeek），依靠严密的 Prompt 约束输出规范。
   - 客户端统一通过 `IndexedSegmentTranslations.parse` 校验：每个片段的 `index` 必须严格连续且不重不漏，任何片段数量缺失或额外包含非法换行均被视作损坏并直接拒绝重试。

#### 5.3.3 抑制思考模式 (Reasoning Control)
推理模型（如 o1、Claude 3.7 Thinking、DeepSeek-R1）的深度思考会大幅增加翻译延迟与 Token 消耗。
- Claude 请求统一设置 `thinking: { type: "disabled" }`。
- OpenAI 协议自动指定最低推理等级（`reasoning_effort: "low"` 或根据供应商规范关闭）。
- 请求输出上限统一为 `TRANSLATION_MAX_OUTPUT_TOKENS = 32768`，单次翻译超时 120 秒。

#### 5.3.4 原格式与换行无损还原 (`TranslationLayout.kt` & `BlogTranslationLayout.kt`)
- **原理**：`TranslationLayout` 在发送请求前，将原文拆解为 `Part.Literal`（保留原本的换行符 `\r\n`、`\n`、前后空格）与 `Part.Translated`（纯文字片段）。
- 大模型仅翻译带编号的纯文字片段；翻译完成后，客户端在本地以**拼接算法将译文回填至原有的空格与空行骨架**中。
- 译文完全重现原文换行位置与换行数量，专有名词及人名保留原样。

#### 5.3.5 昵称占位符智能替换 (`NicknameSubstitution.kt`)
官网API中昵称被替换为占位符 `%%%`。系统在列表和详情呈现时将其动态替换为用户设定的昵称，在调用大模型前亦做好占位保护，确保阅读体验。

> 💡 **实机呈现参考**：
> - 供应商配置与有效性校验：[02_settings_ai_translation.jpg](docs/images/02_settings_ai_translation.jpg)
> - 骨架回填与双语排版对比（Emoji 保真）：[04_message_detail_translation.jpg](docs/images/04_message_detail_translation.jpg)

---

### 5.4 公开 BLOG 客户端直连与离线检索

#### 5.4.1 客户端直连与混合架构
为了减轻中继服务器的带宽与存储压力，Android 客户端直接连接官方 API：
- `BlogClient.kt` 负责直接请求 `https://www.nogizaka46.com/s/n46/api/list/blog` 和 `list/member`。
- 解析官方 JSONP 包装体 `res(...)`，首次同步完整博客历史，后续按同步头部增量写入本地 SQLite 表 `blog_posts`。
- 博客成员筛选列表以**本地实际发过博客的作者**为基准，同时联表匹配官方成员目录补充期别分类（一期至六期、团体/运营）与头像排序，自动隐藏从未发过博客的成员。
- 期别分类在读取时统一归一化（`BlogMemberCategories`）：全角「３期生」「４期生」「５期生」「６期生」、集体帐号原名「新4期生」以及「研究生」都折回 `6期生 / 5期生 / 4期生 / 3期生 / 2期生 / 1期生 / 運営スタッフ` 这套标准写法。筛选按分类字符串全等分组，未归一化的写法会各自变成一个独立分区。
- 成员头像优先取 `blog_members.avatar_url`，为空时回落到 `blog_posts.member_avatar_url`（`MAX` 聚合）。博客列表读的是后者，两处因此始终一致。
- 官方 BLOG 的期别集体帐号 id：`40001` 新4期生、`40003` 運営スタッフ、`40004` ３期生、`40005` ４期生、`40006` 研究生、`40007` 5期生、`40008` 6期生。官方 `artist_img` 对它们返回 `files/46/assets/img/blog/none.png` 占位图。

#### 5.4.2 离线毫秒级全文检索与词边界高亮
在博客与消息时间线中，支持纯离线本地检索：
- 原生 SQL 检索，自动执行通配符转义：`escapeLike(query)`。
- **智能摘要生成 (`searchSnippet`)**：当搜索词命中长篇博客正文时，截取匹配词前约 12 字符、匹配词后约 28 字符并按词边界对齐生成简短摘要，保证关键字必在可视区内展示。
- 原文与译文分别独立检索，两处都命中则分别标注展示，并使用 `highlightMatches` 进行可视字符醒目高亮。

#### 5.4.3 图片批量下载管理器 (`BlogImageDownloadActivity.kt`)
针对下载照片的需求：
- 解析博客 HTML 正文提取全部大图。
- 博客详情将正文图片按原始顺序传给媒体查看器，支持左右滑动切换、双击缩放和当前图片单独下载。
- 提供“全选 / 清空 / 单选”网格交互，逐张下载至系统公共目录 `Environment.DIRECTORY_DOWNLOADS`。
- Android 10 及以上通过 MediaStore 写入，Android 9 及以下写入公共 Download 目录后触发媒体扫描。

> 💡 **实机呈现参考**：
> - 博客列表与关键词搜索：[05_blog_list.jpg](docs/images/05_blog_list.jpg)
> - 成员过滤与时间多维筛选：[06_blog_filter_modal.jpg](docs/images/06_blog_filter_modal.jpg)
> - 中日双语段落下嵌阅读：[07_blog_detail_reading.jpg](docs/images/07_blog_detail_reading.jpg)
> - 图片批量选择下载器：[08_blog_images_batch_download.jpg](docs/images/08_blog_images_batch_download.jpg)

---

### 5.5 归档导入导出与媒体补齐 (Data Transfer)

客户端在 `data/transfer/` 与 `ui/transfer/` 下实现离线归档体系：把本地消息或博客导出成 zip，或把 zip 合并回本地库。一次导出只覆盖一个 `kind`，消息与博客不混装。

#### 5.5.1 归档格式 (`ExportFormat.kt`)

```text
manifest.json          # 清单，先于载荷写出
data/messages.jsonl    # 或 data/blogs.jsonl，一行一个 JSON 对象
media/<sha256>.<ext>   # 内容寻址，重复媒体只存一份
data/skipped.jsonl     # 可选：被引用但本地没有缓存的媒体
```

- `manifest.json` 字段：`format`（固定 `nogirelay-export`）、`formatVersion`（当前 `1`）、`appVersionName`、`appVersionCode`、`exportedAt`、`kind`（`messages` / `blogs`）、`includesMedia`、`includesTranslations`、`members[]`（`id`、`name`、`category`、`avatar_url`、`display_order`、`directory`、`graduated`）。
- 导入防护：条目数上限 200,000（`MAX_ENTRIES`），单条目解压上限 100 MiB（`MAX_ENTRY_BYTES`）。
- 链接列规则（`explicitColumns`）：键缺失或 JSON `null` 表示归档未携带该信息，本地值不动；显式空串表示清空，导入重复记录时照写。消息列为 `member_avatar_url`、`phone_image_url`、`media_url`、`thumbnail_url`、`ringtone_url`；博客列为 `image_url`、`post_url`、`member_avatar_url`，另外 `body_html`、`member_id`、`member_name` 只在非空时覆盖。
- 媒体清单（`mediaCandidates`）：消息取主媒体，语音消息额外带全屏来电写真，缩略图不打包；博客取封面（正文已含则不重复）加全部正文大图，按 URL 去重。

#### 5.5.2 导出 (`DataExporter.kt`)

1. `estimate()` 用 `countMessagesForMembers` / `countBlogsForMembers` 取总数，再遍历记录一次，经 `MediaDownloader.cachedFileForUrl` 判断每份媒体是否已缓存，产出 `ExportEstimate`：记录数、引用媒体数、已缓存数、字节数、按角色统计、缺失清单。
2. `export()` 先写 `manifest.json`，再逐条写 `data/*.jsonl` 并逐条回调进度，然后逐条写入 `media/` 条目，最后写可选的 `data/skipped.jsonl`。
3. 导出不联网：只打包本地已缓存的媒体，缺失项记入 `data/skipped.jsonl`，记录本身完整写出。
4. 读库用单查询流式游标（`forEachMessageForMembers` / `forEachBlogForMembers`）逐条读取，与 `estimate()` 一致；**不用 `LIMIT/OFFSET` 分页** —— 分页会让 SQLite 为每一页重新用临时 B-tree 物化并排序整个结果集（本库 27k 条 BLOG 实测首页 0.20 s、末页 1.95 s，而一次性流式读完只要 0.96 s），记录会卡在 500 的整数倍上，进度显示因此每 500 条跳一格。

#### 5.5.3 导入 (`DataImporter.kt`)

1. 读 `manifest.json`：`kind` 决定载荷解释方式；`members[]` 中 `directory == true` 的行经 `insertMemberIfAbsent`（`CONFLICT_IGNORE`）写入 `blog_members`，分类先过 `BlogMemberCategories.normalizeCategory`。
2. 逐条回写：`data/*.jsonl` 每行解析后立刻在独立 SQLite 事务中落库 —— 消息走 `writeMessage()`（`insertImported`），博客走 `writeBlog()`（`insertBlogIfAbsent`）。不攒批，进程中断最多丢当前这一条。
3. 重复 id：`refreshImportedLinks` / `refreshImportedBlogLinks` 只在归档显式列出的值与本地不同时 UPDATE（`updateLinksIfDifferent` 的 WHERE 含 `COALESCE(列,'') <> ?`）；`backfillMessageTranslation` / `backfillBlogTranslation` 只在本地缺译文时补写。
4. 媒体条目：条目名 `media/<sha256>.<ext>` 的 sha256 与解压内容做摘要比对，通过后按引用它的每个 URL 写入媒体缓存；条目先于记录出现时先落暂存目录，记录解析完后按 `pathToUrls` 落位（`deferredPaths`）。
5. 单行解析失败计入 `invalid`，最多记录 10 条错误信息，不中断整次导入。
6. 进度逐条回调：记录 `onProgress("导入记录", processed, 0)`，媒体 `onProgress("导入媒体", mediaProcessed, 0)`。

#### 5.5.4 传输调度 (`DataTransferManager.kt`)

- 进程级单例，持有 `StateFlow<TransferState>`（`running`、`kind`、`operation`、`phase`、`done`、`total`、`outcome`、`error`），同一时刻只跑一个任务。
- `operation` 取 `EXPORT` / `IMPORT` / `BACKFILL`；`cancel()` 取消协程，导出被取消时删除半成品文件。
- 导出与导入完成后调用 `AppGraph.notifyDataChanged()` 刷新界面。

#### 5.5.5 媒体补齐 (`MediaBackfill.kt` + `MediaBackfillService.kt`)

- 补齐清单直接复用导出预览收集的 `missing` 列表，不重新遍历数据库。
- 每条先查缓存，命中计入 `reused`；否则下载，`HttpNotFoundException` 计入 `notFound`，下载器写入永久 404 标记，后续不再请求该 URL。
- 补齐期间启动前台服务 `MediaBackfillService` 维持后台下载，进度逐条上报。

#### 5.5.6 交互界面 (`ui/transfer/`)

- `DataTransferDrawer.kt`：按 `kind` 提供成员选择、时间范围、媒体/译文开关、导出预估、进度与结果卡片。
- `MemberPickerDialog.kt`：成员网格与 `memberGroups()`；分区顺序由 `BlogMemberCategories.STANDARD_CATEGORIES + "其他"` 派生（`MemberCategoryOrder`）。

---

### 5.6 通知渠道与后台保活

系统预先注册 5 个职责明确的通知渠道（`NotificationChannels.kt`）：

| Channel ID | 渠道名称 | 重要等级 | 提示音 / 振动 / 行为 |
| --- | --- | --- | --- |
| `incoming_calls_v2` | 来电通知 | `HIGH` (紧急) | 循环电话铃声（`R.raw.ringtone`），启动振动，允许锁屏全屏呼叫 |
| `incoming_call_prepare_v1` | 来电准备 | `LOW` (静默) | 无声无振动，用于前台下载音频时显示“正在准备来电...” |
| `member_messages_v1` | 消息通知 | `HIGH` (响铃) | 系统默认提示音与振动，支持点击直接跳转定位到特定成员的特定消息 |
| `member_blogs_v1` | BLOG 更新 | `HIGH` (响铃) | 成员博客更新提醒，点击直达博客详情 |
| `voice_playback_v1` | 语音播放 | `LOW` (静默) | 语音在前台播放时的常驻通知栏控制器 |

---

## 6. 本地开发与调试指南

### 6.1 服务端本地环境搭建

#### 前置依赖
- Node.js >= 20.x
- PostgreSQL >= 15 (本地运行或 Docker 运行)
- Google Chrome 或 Microsoft Edge（用于本地提取会话）

#### 1. 克隆项目与安装依赖
```bash
cd server
npm install
```

#### 2. 初始化本地数据库
确保本地已创建数据库 `nogi_relay`，然后执行脚本：
```bash
npm run db:setup
# 或使用 psql 手动执行:
psql -U postgres -d nogi_relay -f database/schema.sql
```

#### 3. 配置环境变量
在 `server/` 目录下创建 `.env` 文件（可参考 `.env.example`）：
```env
NODE_ENV=development
PORT=3000
DATABASE_URL=postgresql://postgres:password@localhost:5432/nogi_relay
ACCESS_TOKEN=dev-secret-token-123456

# Firebase 服务账号配置（可指定本地 json 文件）
FIREBASE_PROJECT_ID=your-firebase-project-id
FIREBASE_PRIVATE_KEY_PATH=./firebase-admin-key.json

# Relay 对外访问 URL
PUBLIC_BASE_URL=http://localhost:3000
PUBLIC_MEDIA_BASE_URL=http://localhost:8081

# 媒体与日志本地保存目录
MEDIA_STORAGE_DIR=./nogi-media
NOGI_MEDIA_PORT=8081
LOG_STORAGE_DIR=./logs

# 浏览器会话配置
NOGI_BROWSER_STATE_FILE=./nogi-browser-state.json
NOGI_ACCESS_TOKEN_STATE_FILE=./nogi-access-token.json
NOGI_BROWSER_HEADLESS=true
NOGI_BROWSER_SETTLE_SECONDS=8
NOGI_BROWSER_STORAGE_STATE_TIMEOUT_SECONDS=10
NOGI_MACHINE_MEMORY_RESTART_MB=700
NOGI_BROWSER_RESTART_INTERVAL_SECONDS=0

# Windows 本地可直接复用系统自带 Edge
# NOGI_BROWSER_EXECUTABLE_PATH=C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe
```

---

### 6.2 提取官网会话 (`bootstrap-browser.js`)

在首次开发或重新登录账号时，通过内置脚本提取登录态：

```bash
npm run bootstrap:browser
```
- 脚本将调起带界面的 Chromium / Edge 浏览器并导航至乃木坂46消息官网。
- 请在弹出的浏览器中手动完成登录，直到能正常看到已订阅成员的聊天消息。
- 回到终端敲击 **回车**，脚本将校验是否成功监听到 `Authorization: Bearer` 令牌，并将完整 cookies 与 storage 状态以 `0600` 私有权限保存到 `./nogi-browser-state.json`。

---


### 6.3 Android 客户端本地构建与调试

#### 前置环境
- Android Studio Ladybug (2024.2+) 或更高版本。
- Android SDK 34，JDK 17。

#### 1. 注入 Firebase 配置文件
将 Firebase 项目的 `google-services.json` 放置于 `app/` 目录下。

#### 2. 本地个性化配置 (`local.properties`)
从模板复制并编辑 `local.properties`：
```powershell
cp local.properties.example local.properties
```

在 `local.properties` 中按需填写：
```properties
sdk.dir=C:\\Users\\YOUR_USER\\AppData\\Local\\Android\\Sdk

# 可选：调试期直接注入服务端参数（免去在手机界面反复输入的麻烦）
relay.baseUrl=https://your-relay-host.example.com
relay.access.token=your-secret-access-token
```

#### 3. 命令行编译与安装
```powershell
# 编译标准 Debug APK
.\gradlew.bat :app:assembleDebug

# 针对个人单机测试包编译（启用简易模式，隐藏全屏来电测试等调试按钮，产物自动命名为 app-simple-debug.apk）
.\gradlew.bat :app:assembleDebug -PrelaySimpleUi=true --no-daemon

# 直接安装到连接的 Android 手机
.\gradlew.bat :app:installDebug
```

---

### 6.4 常见故障排查 (Troubleshooting)

#### Q1: 服务端启动后提示 `[NOGI_SESSION_UPDATE_REQUIRED]`？
- **原因**：官网刷新令牌失效，`/v2/update_token` 接口返回 400。
- **解决**：在本地运行 `npm run bootstrap:browser` 重新扫码/账密登录，再通过以下命令上传新会话：
  ```bash
  node upload-session.js ./nogi-browser-state.json https://YOUR_RELAY_SERVER YOUR_ACCESS_TOKEN
  ```

#### Q2: Android 手机收不到 FCM 推送或延迟极大？
- **排查**：
  1. 国内由于 GMS 限制，FCM 需要常驻连接；可能需要科学网络环境正常或系统已支持 FCM 唤醒。
  2. 检查系统权限：确认开启了 **自启动权限** 与 **电池无限制后台运行**。
  3. 通过服务端接口 `POST /v1/push/test-message` 测试单播连通性，查看终端的 `push_logs` 记录。

#### Q3: 语音来电无法弹出全屏接听界面？
- **排查**：
  1. 打开 Android 设置 -> 应用 -> Nogi Relay -> **全屏意图权限 (USE_FULL_SCREEN_INTENT)**，确保处于允许状态。
  2. 部分定制 ROM（如 MIUI / HyperOS、OriginOS、ColorOS）需要额外手动开启 **“锁屏显示”** 与 **“后台弹出界面”** 权限。
  3. 检查当前是否开启了系统全局“免打扰模式 (DND)”。

---

## 7. 许可证与法律声明

1. 本项目开源代码基于 **MIT License** 授权。
2. 乃木坂46（Nogizaka46）及其关联团体名称、图像、标识、音视频及官方消息著作权归 **Sony Music Entertainment (Japan) Inc. / Seed & Flower LLC** 及相关权利人所有。
3. 本项目为个人技术研究与私有粉丝中继工具，严禁用于任何商业目的、付费转播或侵犯版权的公开传播行为。部署与使用本项目须严格遵守相关法律法规及服务条款。
