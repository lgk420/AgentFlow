#!/usr/bin/env bash
# AgentFlow 本地环境操作脚本 —— 在 WSL Ubuntu 内运行
# ============================================================
# 注意：仓库在 Windows 盘（/mnt/c）上，drvfs 不保留执行位，
#       所以请用 `bash scripts/dev.sh ...` 调用，而不是 `./scripts/dev.sh`。
#
# 用法：
#   bash scripts/dev.sh up            # 启动 redis + postgres，等待健康检查通过
#   bash scripts/dev.sh up --llm      # 额外启动 ollama（本地 LLM）
#   bash scripts/dev.sh status        # 查看容器状态与健康检查
#   bash scripts/dev.sh logs [svc]    # 跟踪某个容器日志（默认 redis）
#   bash scripts/dev.sh down          # 停止环境（保留数据卷）
#   bash scripts/dev.sh reset         # 停止并清空数据卷（⚠ 数据会丢，相当于恢复出厂）
# ============================================================

set -euo pipefail

cd "$(dirname "$0")/.."        # 切到仓库根目录

PROFILE_ARGS=()

case "${1:-help}" in
  up)
    shift
    if [[ "${1:-}" == "--llm" ]]; then
      PROFILE_ARGS+=(--profile local-llm)
      echo ">> 启动本地 LLM 组件（ollama）"
    fi
    echo ">> docker compose up -d --wait  # --wait 会等健康检查通过"
    docker compose "${PROFILE_ARGS[@]}" up -d --wait
    docker compose ps
    ;;
  status)
    docker compose ps
    echo "--- 健康检查（各容器 STATUS 列应含 healthy） ---"
    docker compose ps --format 'table {{.Name}}\t{{.Status}}\t{{.Ports}}'
    ;;
  logs)
    docker compose logs -f --tail=200 "${2:-redis}"
    ;;
  down)
    docker compose down
    ;;
  reset)
    echo "!! 即将停止并删除全部数据卷（redis/postgres/ollama 数据将清空）"
    read -r -p "确认？输入 yes 继续: " ans
    [[ "$ans" == "yes" ]] || { echo "已取消"; exit 0; }
    docker compose down -v
    ;;
  *)
    echo "用法: $0 {up [--llm]|status|logs [svc]|down|reset}"
    exit 1
    ;;
esac
