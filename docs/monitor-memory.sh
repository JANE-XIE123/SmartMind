#!/bin/bash
#
# PaiSmart 内存监控脚本
# 用于监控 4GB 服务器的资源使用情况
#

echo "=========================================="
echo " PaiSmart 服务器资源监控"
echo " 时间: $(date '+%Y-%m-%d %H:%M:%S')"
echo "=========================================="
echo ""

# 系统总体内存使用
echo "📊 系统内存使用情况:"
free -h | grep -E "Mem|Swap"
echo ""

# Docker 容器内存使用
echo "🐳 Docker 容器内存使用:"
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}" \
  | grep -E "NAME|pai-smart|mysql|redis|kafka|es|minio|frontend" || echo "未找到运行中的容器"
echo ""

# 检查是否有容器因内存不足被杀死
echo "⚠️  检查 OOM (Out Of Memory) 事件:"
dmesg | grep -i "oom\|out of memory" | tail -5 || echo "✅ 未发现 OOM 事件"
echo ""

# 各服务状态
echo "🔍 服务状态:"
cd "$(dirname "$0")"
docker compose ps 2>/dev/null || echo "❌ 无法获取服务状态（可能在其他目录）"
echo ""

# Swap 使用情况（如果启用）
echo "💾 Swap 使用情况:"
swapon --show 2>/dev/null || echo "未启用 Swap"
echo ""

# 磁盘使用
echo "💿 磁盘使用情况:"
df -h | grep -E "/$|/data"
echo ""

# CPU 负载
echo "🖥️  CPU 负载:"
uptime
echo ""

# 建议
echo "=========================================="
echo "💡 优化建议:"
echo "=========================================="

MEM_USAGE=$(free | grep Mem | awk '{printf "%.0f", $3/$2 * 100}')
if [ "$MEM_USAGE" -gt 90 ]; then
    echo "⚠️  警告: 内存使用率 ${MEM_USAGE}%，接近上限！"
    echo "   建议："
    echo "   1. 添加 Swap 交换空间"
    echo "   2. 升级服务器到 8GB 内存"
    echo "   3. 停止不必要的服务"
elif [ "$MEM_USAGE" -gt 75 ]; then
    echo "⚡ 注意: 内存使用率 ${MEM_USAGE}%，处于较高水平"
    echo "   建议监控系统稳定性"
else
    echo "✅ 内存使用率 ${MEM_USAGE}%，运行正常"
fi

echo ""
echo "提示: 可以将此脚本添加到 crontab 定期执行"
echo "      crontab -e"
echo "      */5 * * * * /opt/PaiSmart/docs/monitor-memory.sh >> /var/log/paismart-monitor.log 2>&1"
echo "=========================================="
