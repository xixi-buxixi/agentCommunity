# 启用 HTTPS（H17 收尾步骤）

仓库里能做的部分已经做完：安全响应头、静态资源缓存策略、ACME challenge 位置、
以及一份随时可用的 TLS 配置 `nginx-pulse-prod-tls.conf`。

**剩下的三步必须在服务器上执行**（需要签发证书 / 改防火墙 / 生成 keystore，
这些都不是仓库文件能完成的）。在完成第 1、2 步之前，登录密码与 JWT 仍然是明文传输。

---

## 1. 签发证书（前端所在的腾讯云主机，root）

先确认当前 HTTP 站点可访问，且 `nginx-pulse-prod.conf` 已生效（它包含
`/.well-known/acme-challenge/` 的 location）：

```bash
apt-get update && apt-get install -y certbot
mkdir -p /var/www/html
certbot certonly --webroot -w /var/www/html \
  -d www.lililiz.top -d lililiz.top \
  --agree-tos -m ethan@veridafin.com --no-eff-email
```

成功后应存在 `/etc/letsencrypt/live/www.lililiz.top/fullchain.pem`。

## 2. 切换到 TLS 配置

```bash
cp /path/to/repo/deploy/nginx-pulse-prod-tls.conf /etc/nginx/sites-available/pulse
nginx -t && systemctl reload nginx
```

验证（HSTS / 跳转 / 安全头）：

```bash
curl -sI http://www.lililiz.top/pulse/ | head -3          # 期望 301 -> https
curl -sI https://www.lililiz.top/pulse/ | grep -iE 'strict-transport|content-security|x-content-type'
```

证书自动续期（certbot 包会装好 timer，确认一下）：

```bash
systemctl list-timers | grep certbot
certbot renew --dry-run
```

> 注意：`nginx-pulse-prod-tls.conf` 在证书不存在时会让 nginx 启动失败，
> 因此务必先完成第 1 步。

## 3. 前端主机 → 后端主机这一段（仍是明文）

`proxy_pass http://149.13.91.133:8080/api/` 跨公网走明文，Authorization 头在这段
链路上可被读取。两种解决方式，任选其一：

**A. 后端启用 TLS**（后端主机执行）

```bash
cd /opt/pulse/backend
keytool -genkeypair -alias pulse -keyalg RSA -keysize 2048 -storetype PKCS12 \
  -keystore keystore.p12 -validity 365
# 把口令写进 .env（不要提交到仓库）
echo "SSL_KEYSTORE_PASSWORD=<你设置的口令>" >> /opt/pulse/backend/.env
echo "SSL_ENABLED=true" >> /opt/pulse/backend/.env
```

然后把 nginx 的 proxy_pass 改成 `https://149.13.91.133:8443/api/`（同时调整
`server.port`），并按需加 `proxy_ssl_verify`。

**B. 建立内网/VPN 通道**（推荐）：让两台主机通过私网地址通信，8080 端口只监听内网，
公网防火墙不再放行 8080。

## 4. 同时建议关闭 AI 网关的公网暴露（配合 H11）

AI 网关以 `--host 0.0.0.0` 监听 8000，且 nginx 没有反代它。应只允许本机访问：

```bash
ufw deny 8000/tcp        # 或 iptables -A INPUT -p tcp --dport 8000 ! -i lo -j DROP
ss -ltnp | grep 8000     # 确认监听情况
```

如果后端与 AI 网关在同一台主机，把启动参数改为 `--host 127.0.0.1` 更彻底
（`.github/workflows/deploy.yml` 的 deploy-aiside 步骤）。
