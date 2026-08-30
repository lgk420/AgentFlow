#!/usr/bin/env bash
# 把 testdata/workflows 下所有 .dot 渲染成 .png
# 前置：graphviz（dot 命令）。Windows 上用 winget 装的路径：
#   DOT="/c/Program Files/Graphviz/bin/dot.exe" bash scripts/render_dags.sh
# 也可设环境变量 GRAPHVIZ_DOT 指向 dot.exe 后直接跑。
set -euo pipefail

DOT="${GRAPHVIZ_DOT:-dot}"
DIR="$(cd "$(dirname "$0")/../src/test/resources/testdata/workflows" && pwd)"

echo ">> 用 ${DOT} 递归渲染 $DIR 下全部 .dot（valid/ 与 invalid/ 子目录）"
while IFS= read -r f; do
  "$DOT" -Tpng "$f" -o "${f%.dot}.png"
  echo "   ✓ $(basename "${f%.dot}").png"
done < <(find "$DIR" -name '*.dot' | sort)
echo "完成"
