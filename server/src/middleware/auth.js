/**
 * Bearer Token 认证中间件
 */
export function authenticate(req, res, next) {
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

  const expectedToken = process.env.ACCESS_TOKEN || process.env.API_KEY;

  if (!token) {
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Missing Authorization header (Bearer token), X-API-Key, or token query parameter'
    });
  }

  if (!expectedToken || token !== expectedToken) {
    return res.status(401).json({
      error: 'Unauthorized',
      message: 'Invalid access token'
    });
  }

  next();
}

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
