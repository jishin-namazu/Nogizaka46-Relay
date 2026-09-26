import { timingSafeEqual } from 'node:crypto';

function matchesToken(token, expected) {
  if (!token || !expected) return false;
  const actualBuffer = Buffer.from(token);
  const expectedBuffer = Buffer.from(expected);
  return actualBuffer.length === expectedBuffer.length && timingSafeEqual(actualBuffer, expectedBuffer);
}

function configuredTokens() {
  const adminTokens = [process.env.ADMIN_TOKEN].filter(Boolean);
  const clientToken = process.env.CLIENT_TOKEN;
  if (clientToken && adminTokens.some(token => matchesToken(clientToken, token))) {
    throw new Error('CLIENT_TOKEN must differ from administrator tokens');
  }
  return { adminTokens, clientToken };
}

function isAdminToken(token) {
  return configuredTokens().adminTokens.some(expected => matchesToken(token, expected));
}

function isClientToken(token) {
  const { clientToken } = configuredTokens();
  return isAdminToken(token) || matchesToken(token, clientToken);
}

function suppliedToken(req) {
  const authHeader = req.headers.authorization;
  const xApiKey = req.headers['x-api-key'];
  const queryToken = req.query?.token || req.query?.access_token || req.query?.api_key;

  let token = null;
  if (authHeader && authHeader.startsWith('Bearer ')) {
    token = authHeader.substring(7).trim();
  } else if (xApiKey) {
    token = String(xApiKey).trim();
  } else if (queryToken) {
    token = String(queryToken).trim();
  }

  return token;
}

function authorize(req, res, next, allowClient) {
  const token = suppliedToken(req);

  if (!token) {
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Missing Authorization header (Bearer token), X-API-Key, or token query parameter'
    });
  }

  const { adminTokens, clientToken } = configuredTokens();
  if (!adminTokens.some(expected => matchesToken(token, expected)) &&
      !(allowClient && matchesToken(token, clientToken))) {
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Invalid access token'
    });
  }

  next();
}

export function authenticate(req, res, next) {
  return authorize(req, res, next, false);
}

export function authenticateClient(req, res, next) {
  return authorize(req, res, next, true);
}

export { configuredTokens, isAdminToken, isClientToken };

/**
 * 错误处理中间件
 */
export function errorHandler(err, req, res, next) {
  console.error('Error:', err);

  // 处理特定错误类型
  if (err.name === 'ValidationError') {
    return res.status(400).json({
      error: 'Validation Error',
      message: err.message,
      details: err.details,
    });
  }

  if (err.name === 'UnauthorizedError') {
    return res.status(401).json({
      error: 'Unauthorized',
      message: err.message,
    });
  }

  // 默认 500 错误
  res.status(err.status || 500).json({
    error: 'Internal Server Error',
    message: process.env.NODE_ENV === 'production'
      ? 'Something went wrong'
      : err.message,
  });
}

/**
 * 404 处理中间件
 */
export function notFound(req, res) {
  res.status(404).json({
    error: 'Not Found',
    message: `Cannot ${req.method} ${req.path}`,
  });
}

/**
 * 请求日志中间件
 */
export function requestLogger(req, res, next) {
  const start = Date.now();

  res.on('finish', () => {
    const duration = Date.now() - start;
    console.log(
      `${req.method} ${req.path} ${res.statusCode} - ${duration}ms`
    );
  });

  next();
}
