# 🚀 PaiSmart 快速部署指南（Windows → 腾讯云）

## ⚡ 3 分钟快速开始

### 前提条件检查

在开始之前，请确认：

- ✅ 已准备好腾讯云服务器（4核4G，IP: 106.55.9.171）
- ✅ 已在 `docs/.env` 中配置好所有密钥
- ✅ Windows 已安装 OpenSSH Client 或 Git for Windows

---

## 📝 步骤 1：检查 OpenSSH 是否可用

打开 **PowerShell**，执行：

```powershell
ssh -V
```

如果显示版本信息（如 `OpenSSH_for_Windows_8.x`），说明已安装，跳到步骤 2。

如果报错，需要安装：

### 安装 OpenSSH Client

1. 打开 **设置** → **应用** → **可选功能**
2. 点击 **查看已安装的功能** 或 **添加功能**
3. 搜索 **OpenSSH Client**
4. 点击 **安装**

或者安装 [Git for Windows](https://git-scm.com/download/win)（推荐，包含更多工具）

---

## 📝 步骤 2：执行一键部署脚本

在 **PowerShell** 中执行：

```powershell
# 进入项目目录
cd E:\HM-project\Paichongming\PaiSmart

# 执行部署（自动完成所有步骤）
.\docs\deploy-windows.ps1 -ServerIP "106.55.9.171"
```

脚本会询问你是否继续（如果 .env 中有占位符），输入 `y` 继续。

---

## ⏳ 步骤 3：等待部署完成

整个过程约需 **5-10 分钟**，包括：

1. 创建部署包（~30秒）
2. 上传到服务器（~1-2分钟，取决于网速）
3. 服务器上构建 Docker 镜像（~3-5分钟）
4. 启动所有服务（~2-3分钟）

你会看到实时进度输出。

---

## ✅ 步骤 4：验证部署

部署成功后，你会看到：

```
============================================
 ✅ 部署成功!
============================================

📱 访问地址:
   前端: http://106.55.9.171
   MinIO 控制台: http://106.55.9.171:19001

🔑 默认管理员账号:
   用户名: admin
   密码: admin123
```

### 测试访问

1. **打开浏览器**，访问：http://106.55.9.171
2. **登录系统**：
   - 用户名：`admin`
   - 密码：`admin123`
3. **创建第一个知识库**，上传文档测试

---

## 🔍 如果遇到问题

### 问题 1：SCP/SSH 连接失败

```powershell
# 测试 SSH 连接
ssh root@106.55.9.171

# 如果无法连接，检查：
# 1. 服务器安全组是否开放 22 端口
# 2. 服务器 SSH 服务是否运行
# 3. 防火墙设置
```

### 问题 2：部署过程中断

```powershell
# SSH 连接到服务器查看状态
ssh root@106.55.9.171

# 查看容器状态
cd /opt/PaiSmart/docs
docker compose ps

# 查看日志
docker compose logs -f backend

# 如果部分服务未启动，重新启动
docker compose up -d
```

### 问题 3：内存不足导致容器启动失败

```bash
# SSH 到服务器
ssh root@106.55.9.171

# 添加 2GB Swap
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

# 重启服务
cd /opt/PaiSmart/docs
docker compose restart
```

### 问题 4：前端无法访问

```bash
# 检查 Nginx 容器状态
ssh root@106.55.9.171
docker compose logs frontend

# 检查后端是否正常
curl http://localhost:8081/actuator/health

# 重启前端
docker compose restart frontend
```

---

## 📊 监控服务器资源

部署完成后，可以定期监控资源使用：

```powershell
# 在 PowerShell 中执行
ssh root@106.55.9.171 "bash /opt/PaiSmart/docs/monitor-memory.sh"
```

这会显示：
- 系统内存使用情况
- 各 Docker 容器内存占用
- CPU 负载
- 磁盘使用
- OOM 事件检查

---

## 🛠️ 常用维护命令

### 查看日志

```powershell
# 查看所有服务日志
ssh root@106.55.9.171 "cd /opt/PaiSmart/docs && docker compose logs -f"

# 查看特定服务日志
ssh root@106.55.9.171 "cd /opt/PaiSmart/docs && docker compose logs -f backend"
```

### 重启服务

```powershell
# 重启所有服务
ssh root@106.55.9.171 "cd /opt/PaiSmart/docs && docker compose restart"

# 重启单个服务
ssh root@106.55.9.171 "cd /opt/PaiSmart/docs && docker compose restart backend"
```

### 更新部署

修改代码后重新部署：

```powershell
# 重新执行部署脚本
.\docs\deploy-windows.ps1 -ServerIP "106.55.9.171"
```

脚本会自动覆盖旧文件并重新构建。

### 备份数据

```powershell
# 备份 MySQL 数据库
ssh root@106.55.9.171 "docker exec mysql mysqldump -uroot -pPaiSmart2025 PaiSmart > /tmp/backup_$(Get-Date -Format 'yyyyMMdd').sql"

# 下载备份文件
scp root@106.55.9.171:/tmp/backup_*.sql .\
```

---

## 🎯 下一步

部署成功后：

1. **修改管理员密码**：登录后立即修改默认密码
2. **创建组织**：根据团队结构创建组织
3. **邀请成员**：通过邀请码邀请团队成员
4. **创建知识库**：上传文档建立知识库
5. **测试问答**：体验 AI 问答功能

---

## 📞 获取帮助

如果遇到任何问题：

1. 查看详细文档：[DEPLOYMENT_CHECKLIST.md](./DEPLOYMENT_CHECKLIST.md)
2. 查看容器日志：`docker compose logs -f [service_name]`
3. 检查服务器资源：`bash docs/monitor-memory.sh`
4. 查阅项目文档：[paismart.md](./paismart.md)

---

**祝你部署顺利！** 🎉
