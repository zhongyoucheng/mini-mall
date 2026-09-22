#!/bin/bash
# 容器内 JVM 监控（兼容 eclipse-temurin:17-jre，无 jps/jstat/jmap）

CONTAINER="mini-mall-gateway"

echo "=== JVM 进程命令行 ==="
docker exec $CONTAINER cat /proc/1/cmdline | tr '\0' ' '
echo ""

echo ""
echo "=== 容器资源概况（docker stats） ==="
docker stats $CONTAINER --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}"

echo ""
echo "=== GC 日志最近 20 行（分析 Full GC / G1  young GC） ==="
docker exec $CONTAINER tail -20 /app/gc.log 2>/dev/null

echo ""
echo "=== Full GC 统计 ==="
docker exec $CONTAINER grep -c "Pause Full" /app/gc.log 2>/dev/null || echo "0"

echo ""
echo "=== 堆初始/最大容量（GC 日志头部） ==="
docker exec $CONTAINER grep -E "Heap (Initial|Max) Capacity" /app/gc.log 2>/dev/null | head -2
