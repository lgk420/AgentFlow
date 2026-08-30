#!/usr/bin/env node
/**
 * 把 markdown 知识库（YAML frontmatter 分块）组装成灌库 JSON。
 *
 * 用法：node scripts/build_kb_json.js <kb目录> [输出json]
 * 例：  node scripts/build_kb_json.js src/test/resources/kb/workout-kb
 *       # → src/test/resources/kb/workout-kb/workout-kb.json（缺省输出到 md 同目录，以目录名命名）
 *
 * 背景：知识库文件每个块 = `---` frontmatter（肌群/模块/动作）+ 正文，天然是一个检索 chunk。
 * 本脚本把每块拼成一个字符串（frontmatter 保留在正文里，供检索和 LLM 上下文），
 * 输出 `{"documents": [...]}`——正是 POST /api/v1/collections/{name}/documents 的请求体。
 */
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
if (!dir) {
  console.error('用法: node scripts/build_kb_json.js <kb目录> [输出json]');
  process.exit(1);
}
const out = process.argv[3] || path.join(dir, `${path.basename(dir)}.json`);

const documents = [];
for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.md')).sort()) {
  const text = fs.readFileSync(path.join(dir, file), 'utf8');
  // 结构：HEADER \n---\n FM \n---\n CONTENT \n---\n FM \n---\n CONTENT ...
  const parts = text.split('\n---\n');
  for (let i = 1; i + 1 < parts.length; i += 2) {
    const fm = parts[i].trim();
    const content = parts[i + 1].trim();
    if (content) {
      documents.push(`${fm}\n\n${content}`);
    }
  }
}

fs.writeFileSync(out, JSON.stringify({ documents }, null, 2));
console.log(`共 ${documents.length} 个 chunk，已写入 ${out}`);
