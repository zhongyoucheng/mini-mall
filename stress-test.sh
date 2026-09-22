#!/bin/bash
# mini-mall 压测脚本

BASE_URL="http://127.0.0.1:8090"

echo "========== mini-mall 压测 =========="
echo ""

# 1. 健康检查接口（无 Token，最轻量）
echo "=== 1. /users/health（无 Token） ==="
ab -n 1000 -c 10 -l "$BASE_URL/users/health" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Complete requests"

echo ""
echo "=== 2. /products/health（无 Token） ==="
ab -n 1000 -c 10 -l "$BASE_URL/products/health" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Complete requests"

echo ""
echo "=== 3. 登录接口（POST + JSON Body） ==="
# 先注册一个压测用户
curl -s -X POST "$BASE_URL/users/register" \
  -H "Content-Type: application/json" \
  -d '{"username":"benchuser","password":"123456"}' > /dev/null 2>&1

ab -n 500 -c 10 -l -p /tmp/login.json -T "application/json" "$BASE_URL/users/login" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Complete requests"

echo ""
echo "=== 4. 带 Token 查商品列表（经过鉴权 + 路由 + downstream） ==="
TOKEN=$(curl -s -X POST "$BASE_URL/users/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"benchuser","password":"123456"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)

ab -n 500 -c 10 -l -H "Authorization: Bearer $TOKEN" "$BASE_URL/products" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Complete requests"

echo ""
echo "========== 压测完成 =========="
