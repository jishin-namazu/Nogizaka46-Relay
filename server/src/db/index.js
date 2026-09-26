import pg from 'pg';
import dotenv from 'dotenv';
import { recordError, setErrorLogDbWriter } from '../services/error-log.js';

dotenv.config();

const { Pool } = pg;

const DB_RETRY_ATTEMPTS = Math.max(
  Number.parseInt(process.env.DB_RETRY_ATTEMPTS || '5', 10),
  1,
);
const DB_RETRY_BASE_DELAY_MS = Math.max(
  Number.parseInt(process.env.DB_RETRY_BASE_DELAY_MS || '1000', 10),
  100,
);
const DB_RETRY_MAX_DELAY_MS = Math.max(
  Number.parseInt(process.env.DB_RETRY_MAX_DELAY_MS || '15000', 10),
  DB_RETRY_BASE_DELAY_MS,
);

function intFromEnv(value, fallback) {
  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function intAtLeast(value, fallback, minimum) {
  return Math.max(intFromEnv(value, fallback), minimum);
}

/**
 * 构造 PostgreSQL 连接池参数。默认不保留常驻空闲连接（min=0），并在上游
 * 约 30 分钟的空闲回收之前通过 maxLifetimeSeconds 主动重建连接，从源头
 * 消除周期性的 database.idle_client 断连。
 */
export function buildPoolConfig(env = process.env) {
  return {
    connectionString: env.DATABASE_URL,
    max: intAtLeast(env.DB_POOL_MAX, 20, 1),
    min: intAtLeast(env.DB_POOL_MIN, 0, 0),
    idleTimeoutMillis: intAtLeast(env.DB_POOL_IDLE_TIMEOUT_MS, 30_000, 1_000),
    connectionTimeoutMillis: intAtLeast(env.DB_CONNECTION_TIMEOUT_MS, 10_000, 1),
    // 0 表示关闭；默认 1200 秒（20 分钟）
    maxLifetimeSeconds: intAtLeast(env.DB_POOL_MAX_LIFETIME_SECONDS, 1_200, 0),
    keepAlive: true,
    keepAliveInitialDelayMillis: 10_000,
  };
}

// 预期内的空闲断连只在控制台按此间隔汇总一次，避免刷屏或被误判为故障。
const DB_IDLE_CLIENT_LOG_INTERVAL_MS = intAtLeast(
  process.env.DB_IDLE_CLIENT_LOG_INTERVAL_MS,
  60 * 60 * 1000,
  0,
);

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

function isTransientDatabaseError(error) {
  const code = String(error?.code || '').toUpperCase();
  const message = String(error?.message || '').toLowerCase();
  return [
    '57P01', // 管理员关闭（admin_shutdown）
    '57P02', // 崩溃关闭（crash_shutdown）
    '57P03', // 当前无法连接 / 启动中（cannot_connect_now / startup）
    '08000', // 连接异常（connection_exception）
    '08001', // SQL 客户端无法建立 SQL 连接（sqlclient_unable_to_establish_sqlconnection）
    '08003', // 连接不存在（connection_does_not_exist）
    '08004', // SQL 服务器拒绝建立连接（sqlserver_rejected_establishment）
    '08006', // 连接失败（connection_failure）
    '08007', // 事务解析状态未知（transaction_resolution_unknown）
    '08P01', // 协议违规（protocol_violation）
    'ECONNRESET',
    'ECONNREFUSED',
    'ETIMEDOUT',
    'EPIPE',
  ].includes(code)
    || /connection terminated|connection timeout|database system is starting up|server closed the connection|socket hang up|timeout expired|could not connect/.test(message);
}

function retryDelay(attempt) {
  return Math.min(DB_RETRY_MAX_DELAY_MS, DB_RETRY_BASE_DELAY_MS * (2 ** (attempt - 1)));
}

async function runWithRetry(operation, { scope, queryText = null, params = null } = {}) {
  let lastError;
  for (let attempt = 1; attempt <= DB_RETRY_ATTEMPTS; attempt++) {
    try {
      return await operation(attempt);
    } catch (error) {
      lastError = error;
      const transient = isTransientDatabaseError(error);
      if (!transient || attempt >= DB_RETRY_ATTEMPTS) {
        await recordError(scope, error, {
          attempt,
          max_attempts: DB_RETRY_ATTEMPTS,
          transient,
          query: queryText ? String(queryText).slice(0, 4_000) : null,
          parameter_count: Array.isArray(params) ? params.length : null,
        });
        throw error;
      }
      console.warn(`${scope} transient failure; retrying`, {
        attempt,
        max_attempts: DB_RETRY_ATTEMPTS,
        delay_ms: retryDelay(attempt),
        code: error.code || null,
        message: error.message,
      });
      await sleep(retryDelay(attempt));
    }
  }
  throw lastError;
}

// 数据库连接池
export const pool = new Pool(buildPoolConfig());

setErrorLogDbWriter(async entry => {
  await pool.query(
    `INSERT INTO error_logs (
       created_at, level, scope, message, error_name, error_code, stack,
       context, process_group, machine_id
     ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)`,
    [
      entry.created_at,
      entry.level,
      entry.scope,
      entry.message,
      entry.error?.name || null,
      entry.error?.code || null,
      entry.error?.stack || null,
      JSON.stringify({ error: entry.error, context: entry.context, process: entry.process }),
      entry.process.group,
      entry.process.machine_id,
    ],
  );
});

// 测试连接
pool.on('connect', () => {
  console.log('Connected to PostgreSQL database');
});

const EXPECTED_IDLE_DISCONNECT_MESSAGE = /connection terminated unexpectedly|connection terminated due to connection timeout|server closed the connection unexpectedly|socket hang up|connection reset by peer/i;
const EXPECTED_IDLE_DISCONNECT_CODES = new Set([
  'ECONNRESET',
  'EPIPE',
  'ETIMEDOUT',
  '08000', // connection_exception
  '08003', // connection_does_not_exist
  '08006', // connection_failure
  '57P01', // admin_shutdown
]);

/**
 * 判断空闲连接被对端回收是否属于预期内的自愈行为。
 * pg 连接池会自动剔除死连接并在下次查询时重建，因此这类事件不应写入审计日志。
 */
export function isExpectedIdleClientDisconnect(error) {
  const message = String(error?.message || '').toLowerCase();
  const code = String(error?.code || '').toUpperCase();
  return EXPECTED_IDLE_DISCONNECT_CODES.has(code)
    || EXPECTED_IDLE_DISCONNECT_MESSAGE.test(message);
}

let idleClientDisconnectsSinceLog = 0;
let lastIdleClientLogAt = 0;

/**
 * 处理连接池空闲客户端错误：预期内的断连只在控制台按间隔汇总输出，
 * 真正的连接异常仍会通过 recordError 持久化，避免审计日志被周期噪声淹没。
 */
export function handleIdlePoolError(error, { record = recordError, now = Date.now() } = {}) {
  if (!isExpectedIdleClientDisconnect(error)) {
    void record('database.idle_client', error);
    return 'recorded';
  }

  idleClientDisconnectsSinceLog += 1;
  if (now - lastIdleClientLogAt >= DB_IDLE_CLIENT_LOG_INTERVAL_MS) {
    // 使用 console.log（未被 error-log 拦截持久化），仅保留平台运行日志可观测性。
    console.log('[database.idle_client] 空闲连接被对端回收并已自动重建', {
      message: error.message,
      occurrences: idleClientDisconnectsSinceLog,
    });
    idleClientDisconnectsSinceLog = 0;
    lastIdleClientLogAt = now;
  }
  return 'suppressed';
}

pool.on('error', (err) => {
  handleIdlePoolError(err);
});

/**
 * 执行查询
 */
export async function query(text, params) {
  return await runWithRetry(async () => {
    const start = Date.now();
    const res = await pool.query(text, params);
    const duration = Date.now() - start;
    console.log('Executed query', { text, duration, rows: res.rowCount });
    return res;
  }, { scope: 'database.query', queryText: text, params });
}

/**
 * 获取单个结果
 */
export async function queryOne(text, params) {
  const res = await query(text, params);
  return res.rows[0] || null;
}

/**
 * 获取所有结果
 */
export async function queryAll(text, params) {
  const res = await query(text, params);
  return res.rows;
}

export default {
  query,
  queryOne,
  queryAll,
  pool
};

export { isTransientDatabaseError };
