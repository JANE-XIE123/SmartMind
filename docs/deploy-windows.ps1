# ============================================
# PaiSmart Windows 部署脚本 (PowerShell)
# 用法: .\deploy-windows.ps1 -ServerIP "106.55.9.171"
# ============================================

param(
    [string]$ServerIP = "106.55.9.171",
    [string]$ServerUser = "root",
    [string]$RemoteDir = "/opt/PaiSmart"
)

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " PaiSmart Docker 部署 (Windows)" -ForegroundColor Cyan
Write-Host " 目标服务器: ${ServerUser}@${ServerIP}" -ForegroundColor Cyan
Write-Host " 远程目录:   ${RemoteDir}" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# Step 1: 检查 .env 文件
Write-Host "[1/5] 检查 .env 配置..." -ForegroundColor Yellow
$envFile = Join-Path $PSScriptRoot "docs\.env"
if (-not (Test-Path $envFile)) {
    Write-Host "错误: docs\.env 文件不存在！" -ForegroundColor Red
    exit 1
}

$envContent = Get-Content $envFile -Raw
if ($envContent -match "REPLACE_ME") {
    Write-Host "============================================" -ForegroundColor Yellow
    Write-Host " 警告: .env 中存在未填写的配置项!" -ForegroundColor Yellow
    Write-Host "============================================" -ForegroundColor Yellow
    Select-String -Path $envFile -Pattern "REPLACE_ME" | ForEach-Object {
        Write-Host "   $($_.Line)" -ForegroundColor Red
    }
    Write-Host ""
    Write-Host "必须填写: DEEPSEEK_API_KEY, EMBEDDING_API_KEY, JWT_SECRET_KEY" -ForegroundColor Red
    $confirm = Read-Host "是否继续部署? (y/N)"
    if ($confirm -ne "y" -and $confirm -ne "Y") {
        Write-Host "已取消。请先编辑 docs\.env 填入正确的配置。" -ForegroundColor Yellow
        exit 1
    }
}
Write-Host "✅ .env 配置检查通过" -ForegroundColor Green
Write-Host ""

# Step 2: 检查必要工具
Write-Host "[2/5] 检查部署工具..." -ForegroundColor Yellow

$hasScp = Get-Command scp -ErrorAction SilentlyContinue
$hasSsh = Get-Command ssh -ErrorAction SilentlyContinue

if (-not $hasScp -or -not $hasSsh) {
    Write-Host "❌ 未找到 scp 或 ssh 命令" -ForegroundColor Red
    Write-Host ""
    Write-Host "请安装以下工具之一：" -ForegroundColor Yellow
    Write-Host "  1. Git for Windows (包含 Git Bash)" -ForegroundColor White
    Write-Host "     https://git-scm.com/download/win" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  2. OpenSSH Client (Windows 10/11 内置)" -ForegroundColor White
    Write-Host "     设置 -> 应用 -> 可选功能 -> 添加功能 -> OpenSSH 客户端" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "  3. PuTTY 工具集 (包含 pscp)" -ForegroundColor White
    Write-Host "     https://www.chiark.greenend.org.uk/~sgtatham/putty/latest.html" -ForegroundColor Cyan
    Write-Host ""
    exit 1
}

Write-Host "✅ 部署工具检查通过" -ForegroundColor Green
Write-Host ""

# Step 3: 创建临时部署包
Write-Host "[3/5] 创建部署包..." -ForegroundColor Yellow

$tempDir = Join-Path $env:TEMP "PaiSmart-deploy-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

# 复制必要文件
Write-Host "   复制项目文件..." -ForegroundColor Gray
Copy-Item -Path ".\src" -Destination "$tempDir\src" -Recurse -Force
Copy-Item -Path ".\frontend" -Destination "$tempDir\frontend" -Recurse -Force -Exclude @("node_modules", "dist")
Copy-Item -Path ".\docs" -Destination "$tempDir\docs" -Recurse -Force
Copy-Item -Path ".\pom.xml" -Destination "$tempDir\pom.xml" -Force

# 压缩
$zipFile = Join-Path $env:TEMP "PaiSmart-deploy.zip"
if (Test-Path $zipFile) {
    Remove-Item $zipFile -Force
}

Write-Host "   压缩文件..." -ForegroundColor Gray
Compress-Archive -Path "$tempDir\*" -DestinationPath $zipFile -Force

# 清理临时目录
Remove-Item $tempDir -Recurse -Force

Write-Host "✅ 部署包创建完成: $zipFile" -ForegroundColor Green
Write-Host ""

# Step 4: 上传到服务器
Write-Host "[4/5] 上传到服务器 ${ServerIP}..." -ForegroundColor Yellow

try {
    # 使用 scp 上传
    Write-Host "   正在上传... (可能需要几分钟)" -ForegroundColor Gray
    scp $zipFile "${ServerUser}@${ServerIP}:/tmp/"
    
    if ($LASTEXITCODE -ne 0) {
        throw "SCP 上传失败"
    }
    
    Write-Host "✅ 上传成功" -ForegroundColor Green
} catch {
    Write-Host "❌ 上传失败: $_" -ForegroundColor Red
    Write-Host ""
    Write-Host "提示: 确保可以 SSH 连接到服务器" -ForegroundColor Yellow
    Write-Host "      测试连接: ssh ${ServerUser}@${ServerIP}" -ForegroundColor Cyan
    Remove-Item $zipFile -Force
    exit 1
}
Write-Host ""

# Step 5: 在服务器上部署
Write-Host "[5/5] 在服务器上部署..." -ForegroundColor Yellow

$deployScript = @"
#!/bin/bash
set -e

echo "解压项目..."
mkdir -p ${RemoteDir}
unzip -o /tmp/PaiSmart-deploy.zip -d ${RemoteDir}
rm -f /tmp/PaiSmart-deploy.zip

echo "创建必要目录..."
mkdir -p /data/docker/mysql/conf
mkdir -p /data/docker/redis
mkdir -p /data/docker/minio/config

echo "检查 Docker..."
if ! command -v docker &> /dev/null; then
    echo "安装 Docker..."
    curl -fsSL https://get.docker.com | bash
    systemctl start docker
    systemctl enable docker
fi

echo "构建并启动服务..."
cd ${RemoteDir}/docs
docker compose --env-file .env up -d --build

echo "等待服务启动..."
sleep 10

echo "检查服务状态..."
docker compose ps

echo ""
echo "=========================================="
echo " 部署完成!"
echo " 访问地址: http://${ServerIP}"
echo " MinIO 控制台: http://${ServerIP}:19001"
echo ""
echo "查看日志: docker compose logs -f"
echo "=========================================="
"@

# 将脚本写入服务器并执行
$scriptFile = Join-Path $env:TEMP "deploy-remote.sh"
Set-Content -Path $scriptFile -Value $deployScript -Encoding UTF8

try {
    scp $scriptFile "${ServerUser}@${ServerIP}:/tmp/deploy-paismart.sh"
    ssh "${ServerUser}@${ServerIP}" "chmod +x /tmp/deploy-paismart.sh && bash /tmp/deploy-paismart.sh"
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "============================================" -ForegroundColor Green
        Write-Host " ✅ 部署成功!" -ForegroundColor Green
        Write-Host "============================================" -ForegroundColor Green
        Write-Host ""
        Write-Host "📱 访问地址:" -ForegroundColor Cyan
        Write-Host "   前端: http://${ServerIP}" -ForegroundColor White
        Write-Host "   MinIO 控制台: http://${ServerIP}:19001" -ForegroundColor White
        Write-Host ""
        Write-Host "🔑 默认管理员账号:" -ForegroundColor Cyan
        Write-Host "   用户名: admin" -ForegroundColor White
        Write-Host "   密码: admin123" -ForegroundColor White
        Write-Host ""
        Write-Host "📋 常用命令:" -ForegroundColor Cyan
        Write-Host "   查看日志: ssh ${ServerUser}@${ServerIP} 'cd ${RemoteDir}/docs && docker compose logs -f'" -ForegroundColor White
        Write-Host "   重启服务: ssh ${ServerUser}@${ServerIP} 'cd ${RemoteDir}/docs && docker compose restart'" -ForegroundColor White
        Write-Host "   监控资源: ssh ${ServerUser}@${ServerIP} 'bash ${RemoteDir}/docs/monitor-memory.sh'" -ForegroundColor White
        Write-Host ""
        Write-Host "⚠️  首次启动可能需要 3-5 分钟，请耐心等待..." -ForegroundColor Yellow
        Write-Host "============================================" -ForegroundColor Green
    } else {
        throw "远程部署脚本执行失败"
    }
} catch {
    Write-Host "❌ 远程部署失败: $_" -ForegroundColor Red
    Write-Host ""
    Write-Host "请手动 SSH 到服务器执行:" -ForegroundColor Yellow
    Write-Host "  ssh ${ServerUser}@${ServerIP}" -ForegroundColor Cyan
    Write-Host "  cd ${RemoteDir}/docs" -ForegroundColor Cyan
    Write-Host "  docker compose --env-file .env up -d --build" -ForegroundColor Cyan
} finally {
    # 清理临时文件
    Remove-Item $zipFile -Force -ErrorAction SilentlyContinue
    Remove-Item $scriptFile -Force -ErrorAction SilentlyContinue
}
