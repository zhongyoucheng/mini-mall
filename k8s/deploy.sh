#!/bin/bash
set -e

CHART_DIR="$(cd "$(dirname "$0")" && pwd)/mini-mall-chart"
EXTERNAL_SERVICES="$(cd "$(dirname "$0")" && pwd)/external-services.yaml"
RELEASE_NAME="mini-mall"
NAMESPACE="default"

# 获取宿主机 IP
# 优先用默认路由对应的接口，否则尝试常见接口
get_host_ip() {
    # macOS
    if command -v ipconfig >/dev/null 2>&1; then
        # 先尝试默认路由接口
        local default_if
        default_if=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
        if [[ -n "$default_if" ]]; then
            local ip
            ip=$(ipconfig getifaddr "$default_if")
            if [[ -n "$ip" ]]; then
                echo "$ip"
                return
            fi
        fi
        # 兜底：枚举常见接口
        for if in en0 en1 en2 en3 eth0 eth1; do
            local ip
            ip=$(ipconfig getifaddr "$if" 2>/dev/null)
            if [[ -n "$ip" ]]; then
                echo "$ip"
                return
            fi
        done
    fi

    # Linux
    if command -v ip >/dev/null 2>&1; then
        local ip
        ip=$(ip route get 1.1.1.1 2>/dev/null | awk '/src/{print $7; exit}')
        if [[ -n "$ip" ]]; then
            echo "$ip"
            return
        fi
    fi

    echo ""
}

HOST_IP="${HOST_IP:-$(get_host_ip)}"

if [[ -z "$HOST_IP" ]]; then
    echo "ERROR: 无法自动获取宿主机 IP"
    echo "请手动设置环境变量后重试：HOST_IP=192.168.x.x ./deploy.sh"
    exit 1
fi

echo "================================"
echo "部署 mini-mall Helm Chart"
echo "宿主机 IP: $HOST_IP"
echo "Chart 目录: $CHART_DIR"
echo "================================"

# 可选：应用 ExternalName Service（把外部基础设施抽象成 Service）
# 如果要用 ExternalName 方案，取消下面两行的注释
# echo "应用 ExternalName Services..."
# kubectl apply -f "$EXTERNAL_SERVICES"

# 渲染检查
echo "Helm 渲染检查..."
helm template "$RELEASE_NAME" "$CHART_DIR" --set config.hostIp="$HOST_IP" > /dev/null

# 部署/升级
echo "执行 Helm upgrade --install..."
helm upgrade --install "$RELEASE_NAME" "$CHART_DIR" \
    --namespace "$NAMESPACE" \
    --set config.hostIp="$HOST_IP" \
    --wait \
    --timeout 300s

echo ""
echo "部署完成，当前 Pod 状态："
kubectl get pods -n "$NAMESPACE" | grep -E "NAME|gateway|user-service|product-service|order-service"

echo ""
echo "提示："
echo "  - 如果 IP 以后变了，直接重新运行 ./deploy.sh 即可"
echo "  - 也可以手动指定：HOST_IP=192.168.x.x ./deploy.sh"
echo "  - 若想用 ExternalName 方案，取消脚本中 kubectl apply -f external-services.yaml 的注释"
