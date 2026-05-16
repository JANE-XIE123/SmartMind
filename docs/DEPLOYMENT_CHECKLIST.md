# PaiSmart 腾讯云部署指南

## 📋 部署前检查清单

### 1. 服务器要求

- **操作系统**: Linux (推荐 Ubuntu 20.04+ / CentOS 7+)
- **CPU**: 至少 2 核（推荐 4 核）✅ 你的 4 核满足要求
- **内存**: 
  - **最低配置**: 4GB（已优化，适合小规模使用）
  - **推荐配置**: 8GB+（生产环境、多用户并发）
  - **当前配置**: 4GB ✅ 已通过内存限制优化
- **磁盘**: 至少 50GB 可用空间
- **Docker**: 已安装 Docker 和 Docker Compose v2

> ⚠️ **4GB 内存服务器注意事项**：
> - 已优化各服务内存限制（ES: 1GB, MySQL: 512MB, Redis: 256MB, Backend: 1GB）
> - 建议单用户使用或小团队使用，不适合高并发场景
> - 首次启动可能较慢，请耐心等待 3-5 分钟
> - 避免同时运行其他占用内存的服务

### 2. 安全组配置

确保腾讯云安全组已开放以下端口：

| 端口 | 协议 | 用途 | 是否必须 |
|------|------|------|----------|
| 80 | TCP | Nginx (前端访问) | ✅ 必须 |
| 8081 | TCP | Backend API (可选，通过 Nginx 代理) | ❌ 可选 |
| 3306 | TCP | MySQL (建议仅内网访问) | ⚠️ 谨慎开放 |
| 6379 | TCP | Redis (建议仅内网访问) | ⚠️ 谨慎开放 |
| 9200 | TCP | Elasticsearch (建议仅内网访问) | ⚠️ 谨慎开放 |
| 19000 | TCP | MinIO API | ❌ 可选 |
| 19001 | TCP | MinIO 控制台 | ❌ 可选 |
| 9092 | TCP | Kafka (仅内部使用) | ❌ 不建议开放 |

**推荐做法**：只开放 80 端口，其他服务通过 Docker 内部网络通信。

### 3. 配置文件检查

#### docs/.env 文件

已修复的问题：
- ✅ RERANK_API_MAX_DOCS 和 RERANK_API_TIMEOUT_SECONDS 格式已从冒号改为等号

需要确认的配置项：

```bash
# 数据库密码 - 建议使用强密码
MYSQL_ROOT_PASSWORD=PaiSmart2025
SPRING_DATASOURCE_PASSWORD=PaiSmart2025

# Redis 密码
SPRING_DATA_REDIS_PASSWORD=PaiSmart2025

# Elasticsearch 密码
ELASTICSEARCH_PASSWORD=PaiSmart2025

# MinIO 密钥
MINIO_SECRET_KEY=PaiSmart2025

# JWT 密钥 - 生产环境务必更换为随机生成的 Base64 字符串
JWT_SECRET_KEY=PXrQbuCwXwOZzkML/Vm2S5rSwt1iybvmKtGDzVEu+Hc=

# API Keys - 确认这些密钥有效且未过期
DEEPSEEK_API_KEY=e50c60f01acd4b20b9644d034f51042d.Wmypl3ZYzUEV0OnB
EMBEDDING_API_KEY=sk-mfmdrfmnzadkgafitzjuuowvukkgdrcrclyxsmjgccszkhxr
RERANK_API_KEY=sk-bfe238deed52488db1158076b7016c06

# MinIO 公网地址 - 替换为你的服务器 IP
MINIO_PUBLIC_URL=http://106.55.9.171:19000

# 允许的来源 - 替换为你的服务器 IP
SECURITY_ALLOWED_ORIGINS=http://106.55.9.171,http://106.55.9.171:*

# 管理员账号 - 首次启动后建议修改密码
ADMIN_BOOTSTRAP_USERNAME=admin
ADMIN_BOOTSTRAP_PASSWORD=admin123
```

### 4. 目录准备

在服务器上执行以下命令创建必要的目录：

```bash
# 创建数据持久化目录
sudo mkdir -p /data/docker/mysql/conf
sudo mkdir -p /data/docker/redis
sudo mkdir -p /data/docker/minio/config
sudo mkdir -p /data/docker/kafka
sudo mkdir -p /data/docker/es

# 设置权限（如果需要）
sudo chmod -R 755 /data/docker
```

### 5. Docker 环境准备

在腾讯云服务器上安装 Docker 和 Docker Compose：

```bash
# 安装 Docker
curl -fsSL https://get.docker.com | bash

# 启动 Docker
sudo systemctl start docker
sudo systemctl enable docker

# 验证安装
docker --version
docker compose version

# 将当前用户加入 docker 组（避免每次都用 sudo）
sudo usermod -aG docker $USER
# 重新登录使生效
```

## 🚀 部署步骤

### 方法一：Windows PowerShell 自动部署（推荐）

#### 前提条件

确保你的 Windows 已安装 **OpenSSH Client**：

1. **Windows 10/11 内置 OpenSSH**：
   - 设置 → 应用 → 可选功能 → 查看已安装的功能
   - 如果没有，点击“添加功能” → 搜索“OpenSSH 客户端” → 安装

2. **或者安装 Git for Windows**（包含 Git Bash 和 SSH 工具）：
   - 下载地址：https://git-scm.com/download/win

#### 执行部署

在 **PowerShell** 中执行：

```powershell
# 进入项目目录
cd E:\HM-project\Paichongming\PaiSmart

# 执行部署脚本（替换为你的服务器 IP）
.\docs\deploy-windows.ps1 -ServerIP "106.55.9.171"
```

脚本会自动：
1. ✅ 检查 .env 配置
2. ✅ 创建部署包（排除不必要文件）
3. ✅ 上传到服务器
4. ✅ 在服务器上解压、构建并启动 Docker 容器
5. ✅ 显示部署结果

---

### 方法二：使用 Git Bash（Linux 风格）

如果你安装了 Git for Windows，可以使用 Git Bash：

1. **右键点击项目文件夹** → 选择 "Git Bash Here"
2. 在 Git Bash 中执行：

```bash
cd /e/HM-project/Paichongming/PaiSmart
chmod +x docs/deploy.sh
./docs/deploy.sh 106.55.9.171
```

---

### 方法三：手动部署（通用）

#### 在本地 Windows 上使用 Git Bash 或 WSL：

```bash
# 进入项目根目录
cd E:\HM-project\Paichongming\PaiSmart

# 赋予执行权限
chmod +x docs/deploy.sh

# 执行部署（替换为你的服务器 IP）
./docs/deploy.sh 106.55.9.171
```

脚本会自动：
1. 检查 .env 配置
2. 上传项目文件到服务器
3. 在服务器上构建并启动 Docker 容器
4. 检查服务状态

---

### 方法四：完全手动部署

#### Step 1: 上传项目文件

```bash
# 在本地使用 scp 或 rsync
rsync -avz --progress \
    --exclude 'target/' \
    --exclude 'frontend/node_modules/' \
    --exclude 'frontend/dist/' \
    --exclude '.git/' \
    --exclude '.idea/' \
    ./ root@106.55.9.171:/opt/PaiSmart/
```

#### Step 2: 在服务器上构建和启动

```bash
# SSH 连接到服务器
ssh root@106.55.9.171

# 进入部署目录
cd /opt/PaiSmart/docs

# 构建并启动所有服务
docker compose --env-file .env up -d --build

# 查看服务状态
docker compose ps

# 查看日志
docker compose logs -f
```

## 🔍 验证部署

### 1. 检查容器状态

```bash
docker compose ps
```

所有服务应该显示 `Up` 状态。

### 2. 检查后端健康状态

```bash
curl http://localhost:8081/actuator/health
```

应该返回：
```json
{"status":"UP"}
```

### 3. 访问前端

在浏览器中打开：
```
http://106.55.9.171
```

### 4. 测试登录

使用管理员账号登录：
- 用户名：`admin`
- 密码：`admin123`

### 5. 检查各服务连接

```bash
# 测试 MySQL
docker exec -it mysql mysql -uroot -pPaiSmart2025 -e "SHOW DATABASES;"

# 测试 Redis
docker exec -it redis redis-cli -a PaiSmart2025 ping

# 测试 Elasticsearch
curl http://localhost:9200/_cluster/health?pretty

# 测试 MinIO
curl http://localhost:19000/minio/health/live
```

## 🛠️ 常见问题排查

### 问题 1: 容器启动失败

```bash
# 查看具体服务的日志
docker compose logs backend
docker compose logs es
docker compose logs kafka
```

### 问题 2: Elasticsearch 启动慢

ES 首次启动需要安装 IK 分词器插件，可能需要 2-5 分钟。耐心等待 healthcheck 通过。

```bash
# 监控 ES 启动进度
docker compose logs -f es
```

### 问题 3: 内存不足

如果服务器内存小于 4GB，可能导致 ES 或 Kafka 启动失败。

**针对 4GB 服务器的优化**：
- ✅ Elasticsearch 已降至 1GB（原 2GB）
- ✅ MySQL 限制为 512MB
- ✅ Redis 限制为 256MB
- ✅ Backend 限制为 1GB
- 总内存占用约 3.5GB，留 500MB 给系统和 Kafka

**如果仍然内存不足**：
1. 添加 Swap 交换空间（临时解决方案）：
   ```bash
   # 创建 2GB Swap 文件
   sudo fallocate -l 2G /swapfile
   sudo chmod 600 /swapfile
   sudo mkswap /swapfile
   sudo swapon /swapfile
   
   # 永久生效
   echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
   
   # 验证
   free -h
   ```

2. 或者升级服务器配置到 8GB

### 问题 4: 端口被占用

```bash
# 检查端口占用
sudo netstat -tlnp | grep -E '80|8081|3306|6379|9200'

# 停止冲突的服务
sudo systemctl stop nginx  # 如果已有 Nginx
```

### 问题 5: 前端无法访问后端

检查 Nginx 配置是否正确代理到后端：

```bash
# 查看 Nginx 日志
docker compose logs frontend

# 测试 API 连通性
curl http://106.55.9.171/api/v1/health
```

### 问题 6: WebSocket 连接失败

确保 Nginx 配置了 WebSocket 支持（已包含在 nginx.conf 中）。

检查浏览器控制台是否有 WebSocket 连接错误。

## 📝 维护命令

### 查看日志

```bash
# 查看所有服务日志
docker compose logs -f

# 查看特定服务日志
docker compose logs -f backend
docker compose logs -f frontend
```

### 重启服务

```bash
# 重启所有服务
docker compose restart

# 重启单个服务
docker compose restart backend
```

### 更新部署

```bash
# 拉取最新代码
git pull

# 重新构建并启动
docker compose --env-file .env up -d --build
```

### 停止服务

```bash
# 停止所有服务
docker compose down

# 停止并删除数据卷（谨慎使用！）
docker compose down -v
```

### 备份数据

```bash
# 备份 MySQL
docker exec mysql mysqldump -uroot -pPaiSmart2025 PaiSmart > backup_$(date +%Y%m%d).sql

# 备份 MinIO 数据
docker run --rm -v minio-data:/data -v $(pwd):/backup alpine tar czf /backup/minio-backup.tar.gz /data
```

## 🔒 安全建议

1. **修改默认密码**：首次登录后立即修改 admin 密码
2. **更换 JWT 密钥**：生成新的随机 Base64 字符串
3. **限制端口访问**：只开放必要的端口（建议仅 80）
4. **启用 HTTPS**：生产环境建议使用 Let's Encrypt 配置 SSL
5. **定期备份**：设置定时任务备份数据库和文件
6. **监控日志**：定期检查错误日志和安全日志

## 📞 获取帮助

如果遇到问题：

1. 查看容器日志：`docker compose logs -f [service_name]`
2. 检查容器状态：`docker compose ps`
3. 验证网络连接：`docker network inspect pai_smart_default`
4. 查阅项目文档：`docs/paismart.md`

---

**最后更新时间**: 2026-05-15
