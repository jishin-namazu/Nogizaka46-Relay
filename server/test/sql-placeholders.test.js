import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SRC = path.resolve(__dirname, '../src');

// 反模式：模板字符串里写成 ${params.length}，插值出的是数字而不是 $n 占位符，
// pg 会报 “bind message supplies N parameters, but prepared statement requires 0”。
const ANTI_PATTERNS = [
  /= \$\{params\.length\}/,
  /ILIKE \$\{params\.length\}/,
  /\? \$\{params\.length\}/,
  /LIMIT \$\{limitIdx\}/,
  /OFFSET \$\{offsetIdx\}/,
];

async function collectFiles(dir) {
  const out = [];
  for (const entry of await fs.readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...await collectFiles(full));
    else if (entry.isFile() && entry.name.endsWith('.js')) out.push(full);
  }
  return out;
}

test('SQL placeholders in server sources are dollar-prefixed', async () => {
  const files = await collectFiles(SRC);
  const offenders = [];
  for (const file of files) {
    const lines = (await fs.readFile(file, 'utf8')).split('\n');
    lines.forEach((line, index) => {
      if (ANTI_PATTERNS.some(re => re.test(line))) {
        offenders.push(path.relative(SRC, file) + ':' + (index + 1) + ': ' + line.trim());
      }
    });
  }
  assert.deepEqual(offenders, [], 'params placeholders must be dollar-prefixed; offenders:\n' + offenders.join('\n'));
});
