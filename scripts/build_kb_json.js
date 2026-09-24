#!/usr/bin/env node
/**
 * 把 markdown 知识库（YAML frontmatter 分块）组装成灌库 JSON。
 *
 * 用法：node scripts/build_kb_json.js <kb目录> [输出json]
 * 例：  node scripts/build_kb_json.js src/test/resources/kb/workout-kb
 *       # → src/test/resources/kb/workout-kb/workout-kb.json（缺省输出到 md 同目录，以目录名命名）
 *
 * 背景：知识库文件每个块 = `---` frontmatter（肌群/模块/动作）+ 正文，天然是一个检索 chunk。
 * 本脚本把每块拼成一个字符串（frontmatter 保留在正文里，参与嵌入、也作为 LLM 上下文），
 * 输出 `{"documents": [{"text": ..., "metadata": {...}}]}`——正是
 * POST /api/v1/collections/{name}/documents 的请求体。
 *
 * metadata（T6.5）：把 frontmatter 字段结构化单独存一份，供检索层标识、过滤与溯源。
 * 正文里那份 frontmatter 仍然保留——冗余是有意的：正文那份服务嵌入（带上下文前缀检索更准），
 * metadata 这份服务过滤与溯源（content 是可变的文本，metadata 才是稳定的标识）。
 *
 *   source       文件名，如 01-动作要领.md
 *   label        动作名，如 坐姿钢线划船 —— 评测集与引用溯源用它定位到具体知识单元
 *   muscleGroup  肌群
 *   module       模块
 */
const fs = require('fs');
const path = require('path');

const dir = process.argv[2];
if (!dir) {
  console.error('用法: node scripts/build_kb_json.js <kb目录> [输出json]');
  process.exit(1);
}
const out = process.argv[3] || path.join(dir, `${path.basename(dir)}.json`);

/** frontmatter 中文键 → metadata 英文键；未列出的键忽略。 */
const FIELD_MAP = { 肌群: 'muscleGroup', 模块: 'module', 动作: 'label' };

/** 解析 frontmatter 文本为 metadata；值为空的键不出现（不塞空串）。 */
function parseFrontmatter(fm) {
  const metadata = {};
  for (const line of fm.split('\n')) {
    const sep = line.indexOf(':');
    if (sep <= 0) continue;
    const key = FIELD_MAP[line.slice(0, sep).trim()];
    const value = line.slice(sep + 1).trim();
    if (key && value) metadata[key] = value;
  }
  return metadata;
}

const documents = [];
for (const file of fs.readdirSync(dir).filter(f => f.endsWith('.md')).sort()) {
  const text = fs.readFileSync(path.join(dir, file), 'utf8');
  // 结构：HEADER \n---\n FM \n---\n CONTENT \n---\n FM \n---\n CONTENT ...
  const parts = text.split('\n---\n');
  for (let i = 1; i + 1 < parts.length; i += 2) {
    const fm = parts[i].trim();
    const content = parts[i + 1].trim();
    if (content) {
      documents.push({
        text: `${fm}\n\n${content}`,
        metadata: { source: file, ...parseFrontmatter(fm) },
      });
    }
  }
}

fs.writeFileSync(out, JSON.stringify({ documents }, null, 2));
console.log(`共 ${documents.length} 个 chunk，已写入 ${out}`);
