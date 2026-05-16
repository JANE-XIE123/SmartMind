#!/bin/bash
#
# PaiSmart 一键部署脚本
# 用法: ./deploy.sh [服务器IP]
#
# 示例: ./deploy.sh 106.55.9.171
#

set -e

SERVER_IP="${1:-106.55.9.171}"
SERVER_USER="root"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
PROJECT_NAME="PaiSmart"
REMOTE_DIR="/opt/${PROJECT_NAME}"

echo "============================================"
echo " PaiSmart Docker 部署"
echo " 目标服务器: ${SERVER_USER}@${SERVER_IP}"
echo " 远程目录:   ${REMOTE_DIR}"
echo "============================================"
echo ""

# Step 1: 检查 .env 文件
echo "[1/4] 检查 .env 配置..."
if [ ! -f "${PROJECT_DIR}/docs/.env" ]; then
    echo "错误: docs/.env 文件不存在，请先创建！"
    exit 1
fi

if grep -q "REPLACE_ME" "${PROJECT_DIR}/docs/.env"; then
    echo "============================================"
    echo " 警告: .env 中存在未填写的配置项!"
    echo " 请编辑 docs/.env 替换以下占位符:"
    echo "============================================"
    grep "REPLACE_ME" "${PROJECT_DIR}/docs/.env" || true
    echo ""
    echo "必须填写: DEEPSEEK_API_KEY, EMBEDDING_API_KEY, JWT_SECRET_KEY"
    read -rp "是否继续部署? (y/N) " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        echo "已取消。请先编辑 docs/.env 填入正确的配置。"
        exit 1
    fi
fi
echo ""

# Step 2: 上传项目到服务器
echo "[2/4] 上传项目到服务器..."

# 创建临时部署包
echo "   创建部署包..."
TEMP_DIR=$(mktemp -d)
PROJECT_BASE="$(cd "$(dirname "$0")/.." && pwd)"

# 复制必要文件（排除不必要的目录）
cp -r "${PROJECT_BASE}/src" "${TEMP_DIR}/src"
cp -r "${PROJECT_BASE}/frontend" "${TEMP_DIR}/frontend"
cp -r "${PROJECT_BASE}/docs" "${TEMP_DIR}/docs"
cp "${PROJECT_BASE}/pom.xml" "${TEMP_DIR}/pom.xml"

# 清理不需要的文件
rm -rf "${TEMP_DIR}/frontend/node_modules"
rm -rf "${TEMP_DIR}/frontend/dist"
rm -rf "${TEMP_DIR}/target"
rm -rf "${TEMP_DIR}/.git"
rm -rf "${TEMP_DIR}/.idea"

# 压缩
echo "   压缩文件..."
cd "${TEMP_DIR}"
tar czf "${TEMP_DIR}/paismart-deploy.tar.gz" .

# 上传到服务器
echo "   上传到服务器... (这可能需要几分钟)"
scp "${TEMP_DIR}/paismart-deploy.tar.gz" "${SERVER_USER}@${SERVER_IP}:/tmp/"

if [ $? -ne 0 ]; then
    echo "❌ 上传失败"
    rm -rf "${TEMP_DIR}"
    exit 1
fi

# 在服务器上解压
echo "   在服务器上解压..."
ssh "${SERVER_USER}@${SERVER_IP}" "mkdir -p ${REMOTE_DIR} && tar xzf /tmp/paismart-deploy.tar.gz -C ${REMOTE_DIR} && rm -f /tmp/paismart-deploy.tar.gz"

# 清理临时文件
rm -rf "${TEMP_DIR}"

echo "✅ 文件上传完成"
echo ""

# Step 3: 服务器上构建并启动
echo "[3/4] Docker Compose 构建并启动..."
ssh "${SERVER_USER}@${SERVER_IP}" "cd ${REMOTE_DIR}/docs && docker compose --env-file .env up -d --build"
echo ""

# Step 4: 检查服务状态
echo "[4/4] 检查服务状态..."
sleep 10
ssh "${SERVER_USER}@${SERVER_IP}" "cd ${REMOTE_DIR}/docs && docker compose ps"
echo ""

echo "============================================"
echo " 部署完成!"
echo " 访问地址: http://${SERVER_IP}"
echo " MinIO 控制台: http://${SERVER_IP}:19001"
echo ""
echo " 首次启动可能需要等待几分钟。查看日志:"
echo "   ssh ${SERVER_USER}@${SERVER_IP}"
echo "   cd ${REMOTE_DIR}/docs"
echo "   docker compose logs -f"
echo "============================================"
