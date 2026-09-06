# 服务器收尾操作计划（2026-07-28）

> **这份文档是给「有服务器 SSH 权限的 AI 助手」执行的操作手册。**
> 代码侧的修复已经全部完成并部署上线（提交 `8afc087`，CI 全绿）。
> 剩下 6 件事只能在服务器上做，仓库文件无法完成。
>
> 执行者请**严格按顺序**做，每一步都有「检查 → 执行 → 验证 → 回滚」。
> 任何一步出现和「期望输出」不一致的情况：**立刻停止，不要自己发挥，把实际输出原样报告给用户。**

---

## 0. 环境说明（先读完再动手）

### 0.1 两台服务器

| 代号 | 是哪台 | 上面跑什么 | 关键路径 |
|---|---|---|---|
| **前端机** | 域名 `www.lililiz.top` 解析到的那台（腾讯云） | nginx 1.18.0 (Ubuntu) | `/var/www/pulse/`（Pulse 前端静态文件）、`/etc/nginx/` |
| **后端机** | `149.13.91.133`（七牛云） | Java 后端(8080)、Python AI 网关(8000)、MySQL、Redis | `/opt/pulse/backend/`、`/opt/pulse/ai-side/`、`/opt/pulse/logs/` |

两台都用 `root` 登录。前端机的 nginx 通过 **公网** 反向代理到 `http://149.13.91.133:8080/api/`。

### 0.2 ⚠️ 最重要的一条：这个域名上有两个网站

```
https://www.lililiz.top/          → 用户的 Astro 技术博客   ← 【绝对不能碰】
https://www.lililiz.top/pulse/    → Pulse 应用（我们要改的）
https://www.lililiz.top/pulse/api/ → 反代到后端机 8080
```

所以：

- **不要**把仓库里的 `deploy/nginx-pulse-prod.conf` 或 `deploy/nginx-pulse-prod-tls.conf`
  整份覆盖到服务器上。那两个文件里有 `location = / { return 301 /pulse/; }`，
  覆盖上去**会把博客首页干掉**。
- 本文档采用的方式是：**只往现有配置里增加几行**（用 nginx snippet），
  博客那部分配置一行都不动。

### 0.3 现状实测结果（2026-07-28 01:14 UTC，从公网测的，可信）

| 项 | 现状 | 需要做什么 |
|---|---|---|
| HTTPS 证书 | ✅ **已经有了**，Let's Encrypt，`CN=www.lililiz.top`，TLS 1.3 正常 | **不要再跑 certbot！** 只需核对续期 |
| `http://` (80端口) | ❌ 不跳转 HTTPS，明文就能访问全站 | 任务 A |
| 安全响应头 | ❌ HTML 响应里没有 HSTS / CSP / X-Frame-Options / nosniff / Referrer-Policy | 任务 A |
| `/pulse/index.html` 缓存 | ❌ 没有 `no-cache`，浏览器可能缓存旧入口页导致白屏 | 任务 A |
| `/pulse/assets/` 缓存 | ✅ 已有 `public, immutable`，不用改 | — |
| `/pulse/api/` 反代 | ✅ 正常返回 200 JSON | — |
| 数据库可选索引 | ❓ 未知，要查 | 任务 B |
| 日志轮转 | ❌ 没装，`/opt/pulse/logs/*.log` 会无限增长 | 任务 C |

### 0.4 🚫 绝对禁止做的事

1. **不要** `rm -rf` 任何目录。
2. **不要**用 `pkill -f`（历史教训：`pkill -f 'pulse-backend.*\.jar'` 会匹配到脚本自己的命令行，
   把自己杀掉，曾经造成过 40 分钟生产事故）。需要停进程时用本文给的方法。
3. **不要**在 `nginx -t` 通过之前 `reload`/`restart` nginx。
4. **不要**修改 GitHub Secrets、**不要** `git push`、**不要**改任何代码文件。
5. **不要**跑 `certbot certonly` / `certbot --nginx` 重新签发证书（已经有有效证书了，
   重复签发可能撞 Let's Encrypt 频率限制）。
6. **不要**执行 `ufw enable`（详见任务 D，会有把自己 SSH 关在门外的风险）。
7. **不要**把 `.env` 里的密钥明文打印到聊天里（要报告时只报告长度和前 6 位）。
8. 命令里凡是要求「先备份」的，**必须**先备份。

### 0.5 开始前先建一个工作目录（两台机都建）

```bash
mkdir -p /root/pulse-ops-2026-07-28
```

后面所有备份文件都放这里。

---

## 任务 A（前端机）：强制 HTTPS + 补齐安全响应头 + 入口页不缓存

**为什么要做**：现在登录密码和 JWT 在 80 端口是明文传输；浏览器缺少 HSTS/CSP 保护；
入口页 `index.html` 可被缓存，用户可能拿着旧的 index.html 去请求已被清理的 JS 分片而白屏。

### A1. 只读盘点（先看清楚，不要改）

```bash
nginx -v
nginx -T > /root/pulse-ops-2026-07-28/nginx-effective-before.conf 2>&1
echo "---- 哪些文件提到 lililiz ----"
grep -rlE "server_name[^;]*lililiz" /etc/nginx/ 2>/dev/null
echo "---- 站点目录 ----"
ls -la /etc/nginx/sites-enabled/ /etc/nginx/conf.d/ 2>/dev/null
```

然后把每个提到 `lililiz` 的配置文件**完整打印出来**：

```bash
for f in $(grep -rlE "server_name[^;]*lililiz" /etc/nginx/ 2>/dev/null); do
  echo "=================== $f ==================="
  cat -n "$f"
done
```

**你需要从输出里确认 4 件事，确认不了就停下报告：**

1. 哪个文件里有 `listen 443`（这是 HTTPS 主配置，记为 **文件H**）。
2. **文件H** 的那个 443 `server { }` 里，有没有 `location /pulse/ { ... }`？
3. 哪个文件里有 `listen 80`（记为 **文件P**；可能和文件H是同一个）。
4. **文件H** 的 443 块里，`location /` 是不是指向博客（Astro，root 可能是
   `/var/www/blog` 之类，或者有 `try_files` 到别的目录）。

> ❗ 如果 **文件H 的 443 块里找不到 `location /pulse/`**（例如 `/pulse/` 是被
> `location ~* \.(js|css)$` 之类的正则或 `location /` 兜住的），
> **停止执行任务 A**，把整个 443 server 块贴给用户，等用户指示。
> 因为这种情况下插入位置需要人来判断，猜错会让 Pulse 或博客其中之一挂掉。

### A2. 备份

```bash
cp -a /etc/nginx /root/pulse-ops-2026-07-28/nginx-backup
ls -la /root/pulse-ops-2026-07-28/nginx-backup/sites-available/ 2>/dev/null | head
```

### A3. 创建安全头 snippet

```bash
mkdir -p /etc/nginx/snippets
cat > /etc/nginx/snippets/pulse-security.conf <<'EOF'
# Pulse 安全响应头（只被 /pulse/ 相关 location include，不影响同域名下的博客）
#
# 注意：HSTS 故意没有加 includeSubDomains。加上之后 *.lililiz.top 的所有子域名
# 在浏览器里都会被强制 HTTPS，如果有子域名还没配证书就会直接打不开。
# 等用户确认「所有子域名都已启用 HTTPS」后，再手动追加 includeSubDomains。
add_header Strict-Transport-Security "max-age=31536000" always;
add_header X-Content-Type-Options "nosniff" always;
add_header X-Frame-Options "SAMEORIGIN" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
add_header Permissions-Policy "geolocation=(), microphone=(), camera=()" always;
add_header Content-Security-Policy "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com data:; img-src 'self' data: blob:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'self'; form-action 'self'; upgrade-insecure-requests" always;
EOF
cat /etc/nginx/snippets/pulse-security.conf
```

> 这份 CSP 已经和前端实际用的资源核对过：入口页只有一个外部脚本
> `/pulse/theme-init.js`（没有 inline script），字体来自 `fonts.googleapis.com` /
> `fonts.gstatic.com`，接口是同源 `/pulse/api/`，前端代码里没有 `<img>` 渲染外链图片。
> 所以 `script-src 'self'` 不会破坏页面。

### A4. 改 443 块里的 `location /pulse/`

用文本编辑工具打开 **文件H**，找到 443 `server { }` 里的 `location /pulse/ { ... }`，
**在它的第一行插入这两行**：

```nginx
        include snippets/pulse-security.conf;
        add_header Cache-Control "no-cache" always;
```

改完应该长这样（`try_files` 那行是原来就有的，保持不动）：

```nginx
    location /pulse/ {
        include snippets/pulse-security.conf;
        add_header Cache-Control "no-cache" always;

        try_files $uri $uri/ /pulse/index.html;    # ← 原有内容，别动
    }
```

**两个注意点：**

- 如果这个 `location /pulse/` 块里**原来已经有** `add_header Cache-Control ...`，
  把原来那一行删掉（只保留我们新加的 `no-cache`），否则会返回两个 Cache-Control 头。
- 这里的 `no-cache` 只影响 `/pulse/` 下的 HTML 入口页；`/pulse/assets/` 有自己更精确的
  location，不受影响，哈希资源仍然长期缓存。

**可选（如果 443 块里确实存在 `location /pulse/assets/`）**：在它第一行也加一行
`include snippets/pulse-security.conf;`（**不要**在这里加 Cache-Control，会和现有的
`immutable` 冲突）。如果找不到这个 location，跳过，不影响验收。

### A5. 改 80 块：全部跳转到 HTTPS（先确认证书续期方式！）

**先查续期用的是什么方式：**

```bash
cat /etc/letsencrypt/renewal/www.lililiz.top.conf 2>/dev/null | grep -iE "authenticator|webroot_path|installer"
ls /etc/letsencrypt/live/
```

按结果分两种情况：

**情况 1：`authenticator = webroot`** → 记下 `webroot_path` 的值（比如 `/var/www/html`），
把 **文件P** 的 80 `server { }` 整块替换成下面内容（`WEBROOT_PATH` 换成刚查到的真实路径）：

```nginx
server {
    listen 80;
    server_name www.lililiz.top lililiz.top;

    # 保留 ACME 校验路径，否则证书自动续期会失败
    location ^~ /.well-known/acme-challenge/ {
        root WEBROOT_PATH;
        default_type "text/plain";
    }

    location / {
        return 301 https://$host$request_uri;
    }

    server_tokens off;
}
```

**情况 2：`authenticator = nginx`**（certbot 自己临时改配置校验）→ 同上，但**不要**加
`.well-known` 那个 location，只留 `return 301`。

**情况 3：查不到 `/etc/letsencrypt/renewal/` 下的文件** → **停止任务 A5**（A3/A4 可以保留），
报告给用户：证书不是 certbot 管的，80→443 跳转需要用户确认后再做。

> ⚠️ 如果 80 块里还有除 Pulse 之外的其他 location（比如别的项目、webhook 回调），
> **不要**整块替换，停下来报告。已知情况是 80 端口目前只把 `/` 跳到 `/pulse/`，只服务 Pulse。

### A6. 验证（必须全部通过才算完成）

```bash
nginx -t
```
期望：`syntax is ok` + `test is successful`。**失败就直接跳到 A7 回滚，不要 reload。**

```bash
systemctl reload nginx
sleep 2
systemctl is-active nginx
```
期望：`active`

```bash
echo "== 1) 80 应该 301 到 https =="
curl -sI http://www.lililiz.top/pulse/ | head -3

echo "== 2) HTTPS 入口页应有安全头 + no-cache =="
curl -sI https://www.lililiz.top/pulse/ | grep -iE "^HTTP/|strict-transport|content-security|x-frame|x-content-type|referrer-policy|permissions-policy|cache-control"

echo "== 3) 博客首页必须仍然正常（关键回归检查）=="
curl -s -o /dev/null -w "blog root: %{http_code}\n" https://www.lililiz.top/
curl -s https://www.lililiz.top/ | grep -c "Astro"

echo "== 4) API 仍然正常 =="
curl -s -o /dev/null -w "api: %{http_code}\n" "https://www.lililiz.top/pulse/api/v1/posts?page=1&size=1"

echo "== 5) 静态资源仍然长缓存 =="
ASSET=$(curl -s https://www.lililiz.top/pulse/ | grep -oE '/pulse/assets/[A-Za-z0-9._-]+\.js' | head -1)
echo "asset=$ASSET"
curl -sI "https://www.lililiz.top$ASSET" | grep -iE "^HTTP/|cache-control|content-type"

echo "== 6) 证书续期仍然可用 =="
certbot renew --dry-run 2>&1 | tail -5
systemctl list-timers 2>/dev/null | grep -i certbot
```

**期望结果：**

1. `HTTP/1.1 301` + `Location: https://www.lililiz.top/pulse/`
2. `200`，且能看到 `Strict-Transport-Security`、`Content-Security-Policy`、
   `X-Frame-Options: SAMEORIGIN`、`X-Content-Type-Options: nosniff`、
   `Cache-Control: no-cache`
3. `blog root: 200`，且 `grep -c "Astro"` ≥ 1（博客没坏）
4. `api: 200`
5. `Cache-Control: public, immutable`（保持原样），`Content-Type: application/javascript`
6. `Congratulations, all simulated renewals succeeded` 之类的成功字样

最后**用浏览器实际打开** `https://www.lililiz.top/pulse/square`，确认页面正常渲染、
浏览器控制台没有 CSP 报错（形如 `Refused to load ... because it violates the following
Content Security Policy directive`）。**如果有 CSP 报错，把报错原文完整贴出来并执行 A7 回滚。**

### A7. 回滚（只要上面任何一步不对就执行）

```bash
rm -rf /etc/nginx
cp -a /root/pulse-ops-2026-07-28/nginx-backup /etc/nginx
nginx -t && systemctl reload nginx
curl -sI https://www.lililiz.top/pulse/ | head -3
```

（这是本文档唯一允许 `rm -rf` 的地方，且必须紧跟着 `cp -a` 恢复备份。
如果不放心，可以改成 `mv /etc/nginx /etc/nginx.bad` 再 `cp -a` 恢复。）

---

## 任务 B（后端机）：数据库可选索引/字段迁移

**背景**：部署流水线会用**应用自己的数据库账号**执行 `schema.sql`。如果这个账号没有
`ALTER`/`CREATE` 权限，几个「可选」的性能对象就没建出来。程序**不会报错**，会自动降级：

| 缺失对象 | 降级行为 | 代价 |
|---|---|---|
| `posts.hot_score` | 排行榜用原始表达式排序 | 全表扫描 + filesort |
| `agents.last_dispatched_at` | agent 选取回退到 `ORDER BY RAND()` | 全表扫描，且可能有 agent 长期轮不到 |
| `shedlock` 表 | 调度器分布式锁关闭 | 单实例安全；多实例会重复烧用户的 LLM token |

### B1. 先查到底缺不缺（很可能什么都不用做）

```bash
grep -a "Schema capabilities" /opt/pulse/logs/backend.log | tail -3
```

期望看到类似：
```
Schema capabilities: posts.hot_score=true, agents.last_dispatched_at=true, shedlock=true
```

- **三个都是 `true`** → **任务 B 直接跳过**，在报告里写「数据库迁移已生效，无需操作」。
- **有 `false`**，或者 grep 不到这一行 → 继续 B2。

（也可以直接查库，`<DB_USER>`/`<DB_PASS>` 取自 `/opt/pulse/backend/.env` 的
`DB_USERNAME` / `DB_PASSWORD`；注意 `-p` 和密码之间没有空格）

```bash
set -a; . /opt/pulse/backend/.env; set +a
mysql -u "$DB_USERNAME" -p"$DB_PASSWORD" pulse_db -e "
SELECT 'hot_score' AS obj, COUNT(*) AS found FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='pulse_db' AND TABLE_NAME='posts' AND COLUMN_NAME='hot_score'
UNION ALL SELECT 'last_dispatched_at', COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='pulse_db' AND TABLE_NAME='agents' AND COLUMN_NAME='last_dispatched_at'
UNION ALL SELECT 'active_name', COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='pulse_db' AND TABLE_NAME='agents' AND COLUMN_NAME='active_name'
UNION ALL SELECT 'shedlock', COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA='pulse_db' AND TABLE_NAME='shedlock';"
```

`found=1` 表示已存在。

### B2. 备份数据库（**必做，不许跳过**）

```bash
set -a; . /opt/pulse/backend/.env; set +a
mysqldump -u "$DB_USERNAME" -p"$DB_PASSWORD" --single-transaction --routines \
  pulse_db > /root/pulse-ops-2026-07-28/pulse_db-before-migration.sql
ls -lh /root/pulse-ops-2026-07-28/pulse_db-before-migration.sql
```

期望：文件存在且大小 > 10KB。**如果 mysqldump 失败或文件是空的，停止任务 B 并报告。**

### B3. 确认有一个有 DDL 权限的账号

先看应用账号自己有没有权限：

```bash
set -a; . /opt/pulse/backend/.env; set +a
echo "DB_USERNAME=$DB_USERNAME"
mysql -u "$DB_USERNAME" -p"$DB_PASSWORD" -e "SHOW GRANTS FOR CURRENT_USER();"
```

- 如果 grants 里有 `ALL PRIVILEGES` 或包含 `ALTER, CREATE`（对 `pulse_db.*` 或 `*.*`）
  → 用这个账号即可，进入 B4。
- 如果权限不足 → 需要 root。**不要猜 root 密码，不要暴力尝试。**
  先试一下 socket 免密（Ubuntu 上 root 常配 `auth_socket`）：
  ```bash
  mysql -u root -e "SELECT 'root ok' AS status;"
  ```
  能出 `root ok` → 后面用 `mysql -u root`。
  报 `Access denied` → **停止任务 B**，报告：「需要用户提供 MySQL root 密码才能执行迁移；
  在此之前程序按降级模式运行，功能正常，只是排行榜/agent 调度是全表扫描」。

### B4. 写入迁移 SQL 文件

这个文件在服务器上不存在（部署不会上传它），所以直接创建：

```bash
cat > /root/pulse-ops-2026-07-28/2026-07-27-optimization.sql <<'SQLEOF'
-- Pulse optimization migration (2026-07-27)
-- 每条语句都是幂等的，重复执行安全。要求 MySQL 5.7+（生成列）。

-- posts: 作者时间线复合索引
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE posts ADD INDEX idx_author_created (author_type, author_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND INDEX_NAME = 'idx_author_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- comments
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE comments ADD INDEX idx_post_author (post_id, author_type, author_id)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'comments' AND INDEX_NAME = 'idx_post_author');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- bounty_tasks
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE bounty_tasks ADD INDEX idx_agent_created (agent_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bounty_tasks' AND INDEX_NAME = 'idx_agent_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE bounty_tasks ADD INDEX idx_status_deadline (status, deadline)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bounty_tasks' AND INDEX_NAME = 'idx_status_deadline');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- agent_logs
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agent_logs ADD INDEX idx_agent_created (agent_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_logs' AND INDEX_NAME = 'idx_agent_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- sys_ledger
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE sys_ledger ADD INDEX idx_user_created (user_id, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_ledger' AND INDEX_NAME = 'idx_user_created');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- posts.hot_score：物化的热度分（可索引）
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE posts ADD COLUMN hot_score INT AS (COALESCE(like_count,0) * 3 + COALESCE(comment_count,0) * 5 + COALESCE(view_count,0)) STORED',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND COLUMN_NAME = 'hot_score');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE posts ADD INDEX idx_hot_score (hot_score, created_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'posts' AND INDEX_NAME = 'idx_hot_score');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- agents.last_dispatched_at：轮转调度，替代 ORDER BY RAND()
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN last_dispatched_at DATETIME NULL',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'last_dispatched_at');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD INDEX idx_dispatch_order (status, deleted, last_dispatched_at)',
    'SELECT 1')
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND INDEX_NAME = 'idx_dispatch_order');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- agents：同一 owner 下活跃 agent 名字唯一（软删除行的生成列为 NULL，不参与唯一约束）
SET @ddl = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE agents ADD COLUMN active_name VARCHAR(100) AS (IF(deleted = 0, name, NULL)) STORED',
    'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND COLUMN_NAME = 'active_name');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 只有现有数据满足约束时才加唯一键，否则跳过（留给运维先去重）
SET @dupes = (SELECT COUNT(*) FROM (
    SELECT owner_id, name FROM agents WHERE deleted = 0
    GROUP BY owner_id, name HAVING COUNT(*) > 1) AS d);
SET @has_key = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agents' AND INDEX_NAME = 'uk_owner_active_name');
SET @ddl = IF(@dupes = 0 AND @has_key = 0,
    'ALTER TABLE agents ADD UNIQUE KEY uk_owner_active_name (owner_id, active_name)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ShedLock：保证 @Scheduled 任务只跑一次
CREATE TABLE IF NOT EXISTS shedlock (
    name VARCHAR(64) NOT NULL COMMENT 'Lock name',
    lock_until TIMESTAMP(3) NOT NULL COMMENT 'Lock held until',
    locked_at TIMESTAMP(3) NOT NULL COMMENT 'Lock acquired at',
    locked_by VARCHAR(255) NOT NULL COMMENT 'Instance holding the lock',
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Distributed scheduler locks';
SQLEOF
wc -l /root/pulse-ops-2026-07-28/2026-07-27-optimization.sql
```

期望：约 110 行。

### B5. 执行迁移

用 B3 里确定可用的账号（下面以 root 为例；如果应用账号权限够，把 `-u root` 换成
`-u "$DB_USERNAME" -p"$DB_PASSWORD"`）：

```bash
mysql -u root pulse_db < /root/pulse-ops-2026-07-28/2026-07-27-optimization.sql
echo "exit code: $?"
```

期望 `exit code: 0`，没有任何 `ERROR` 输出。

> `posts` 表加 `hot_score` STORED 生成列会重建整表。如果 posts 数据量很大（>百万行），
> 执行期间会有短暂写阻塞。先看一下量：
> `mysql -u root pulse_db -e "SELECT COUNT(*) FROM posts;"`
> 几万行以内几秒钟就完事，不用担心。

### B6. 验证

```bash
mysql -u root pulse_db -e "SHOW INDEX FROM posts;" | awk '{print $3}' | sort -u
mysql -u root pulse_db -e "DESC posts;" | grep hot_score
mysql -u root pulse_db -e "DESC agents;" | grep -E "last_dispatched_at|active_name"
mysql -u root pulse_db -e "SHOW TABLES LIKE 'shedlock';"
```

期望：能看到 `idx_hot_score`、`idx_author_created`、`hot_score` 列、
`last_dispatched_at` 列、`active_name` 列、`shedlock` 表。

**然后重启后端让程序重新探测能力**（用下面这个安全的重启方式，**不要用 pkill -f**）：

```bash
# 1) 找到当前后端进程（应该只有 1 个）
pgrep -af "pulse-backend-1.0.0-SNAPSHOT.jar"

# 2) 优雅停止（把上面看到的 PID 填进去；有多个就逐个 kill）
kill <PID>
sleep 8
pgrep -af "pulse-backend-1.0.0-SNAPSHOT.jar" || echo "已停止"

# 3) 启动（命令必须和部署脚本一致，特别是 additional-location 那一行）
cd /opt/pulse/backend
set -a; . /opt/pulse/backend/.env; set +a
JAVA_BIN=$(command -v java)
nohup "$JAVA_BIN" -Xms512m -Xmx768m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
  -jar /opt/pulse/backend/pulse-backend-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=prod \
  --spring.config.additional-location=file:/opt/pulse/backend/ \
  > /opt/pulse/logs/backend.log 2>&1 &
echo $! > /opt/pulse/backend/backend.pid

# 4) 等它起来（最多等 90 秒）
#    注意：这里探测的是 /actuator/health/liveness，不是 /actuator/health。
#    /actuator/health 会聚合 MySQL + Redis 的状态，Redis 抖一下就返回 503，
#    但接口其实完全可用 —— 用它当判断依据会误杀正常进程。
for i in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 \
    http://127.0.0.1:8080/actuator/health/liveness 2>/dev/null || echo 000)
  echo "try $i: $code"
  [ "$code" = "200" ] && break
  sleep 3
done
grep -a "Started PulseApplication" /opt/pulse/logs/backend.log | tail -1
grep -a "Schema capabilities" /opt/pulse/logs/backend.log | tail -1
```

期望最后一行三个都是 `true`：
```
Schema capabilities: posts.hot_score=true, agents.last_dispatched_at=true, shedlock=true
```

> ⚠️ 如果 90 秒还起不来，`tail -60 /opt/pulse/logs/backend.log` 看报错并**立刻报告**。
> 常见原因：`.env` 没被加载导致 `Could not resolve placeholder 'JWT_SECRET'`
> —— 那说明第 3 步的 `set -a; . .env` 或 `--spring.config.additional-location` 漏了。

### B7. 回滚

索引和字段都是**加法**，不影响原有数据，正常不需要回滚。真要回滚：

```bash
mysql -u root pulse_db -e "
ALTER TABLE posts DROP INDEX idx_hot_score;
ALTER TABLE posts DROP COLUMN hot_score;
ALTER TABLE agents DROP INDEX idx_dispatch_order;
ALTER TABLE agents DROP COLUMN last_dispatched_at;
ALTER TABLE agents DROP INDEX uk_owner_active_name;
ALTER TABLE agents DROP COLUMN active_name;
DROP TABLE IF EXISTS shedlock;"
```
（程序在这些对象不存在时会自动走降级路径，所以回滚是安全的。数据全量备份在
`/root/pulse-ops-2026-07-28/pulse_db-before-migration.sql`。）

---

## 任务 C（后端机）：安装日志轮转

**为什么**：后端和 AI 网关都通过 `nohup ... > /opt/pulse/logs/*.log` 写日志，
没有任何轮转，磁盘会被慢慢写满。

### C1. 先看现在多大了

```bash
ls -lh /opt/pulse/logs/
df -h /
```

### C2. 安装

```bash
cat > /etc/logrotate.d/pulse <<'EOF'
/opt/pulse/logs/*.log {
    daily
    rotate 14
    maxsize 100M
    missingok
    notifempty
    compress
    delaycompress
    # 进程通过 shell 重定向一直持有文件句柄，所以必须原地截断而不是改名，
    # 否则进程继续往被改名的 inode 里写，实时日志文件会一直是空的。
    copytruncate
    create 0640 root root
}
EOF
cat /etc/logrotate.d/pulse
```

### C3. 验证

```bash
logrotate -d /etc/logrotate.d/pulse 2>&1 | tail -20
```
期望：输出里有 `considering log /opt/pulse/logs/backend.log`，且**没有** `error:` 字样。
（`-d` 是演练模式，不会真的动文件。）

```bash
systemctl list-timers | grep -i logrotate
```
期望：能看到 `logrotate.timer`（Ubuntu 默认有）。

### C4. 回滚

```bash
rm -f /etc/logrotate.d/pulse
```

---

## 任务 D（后端机）：端口暴露体检 —— **只诊断，默认不改**

### D1. 看现在监听情况

```bash
ss -ltnp
echo "---- 防火墙状态 ----"
ufw status verbose 2>/dev/null || echo "no ufw"
iptables -S 2>/dev/null | head -30
```

**逐项核对并记录：**

| 端口 | 期望 | 说明 |
|---|---|---|
| 8000（AI 网关） | 应该是 `127.0.0.1:8000` | 最新部署已改成 `--host 127.0.0.1`。如果显示 `0.0.0.0:8000`，说明还在跑改动前的老进程，见 D2 |
| 8080（后端） | `0.0.0.0:8080` | 必须让前端机能连上，属于当前架构的必要暴露 |
| 3306（MySQL） | 最好是 `127.0.0.1:3306` | 如果是 `0.0.0.0`，记录下来报告给用户 |
| 6379（Redis） | 最好是 `127.0.0.1:6379` | 同上。Redis 暴露公网且无密码是高危，务必报告 |

### D2. 如果 8000 还在 `0.0.0.0` 上监听

说明 AI 网关还是老进程。安全地重启它：

```bash
pgrep -af "app.main:app"
kill <PID>
sleep 5
pgrep -af "app.main:app" || echo "已停止"

cd /opt/pulse/ai-side
mkdir -p /opt/pulse/logs
nohup venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1 \
  --log-level info > /opt/pulse/logs/ai-side.log 2>&1 &
sleep 5
curl -fsS --max-time 5 http://127.0.0.1:8000/health && echo " <- health ok"
ss -ltnp | grep 8000
```

期望：`ss` 显示 `127.0.0.1:8000`。

### D3. 关于防火墙：**不要自己动手**

收紧 8080（只允许前端机访问）确实是有价值的加固，但远程改防火墙有把 SSH 关在门外的
经典风险。所以：

- **`ufw status` 显示 `inactive` 时**：**什么都不要做**。只在报告里给出建议命令，
  让用户自己在保留一个 SSH 会话的情况下执行。
- **`ufw status` 显示 `active` 时**：也**先不要执行**，把当前规则列表贴出来，
  连同下面的建议一起交给用户确认。

建议（供用户自己决定，前端机 IP 从 `/opt/pulse/backend/.env` 的 `TRUSTED_PROXIES` 取，
那是部署时自动写入的前端机地址）：

```bash
# 先确认 SSH 一定放行，否则会把自己关在外面
ufw allow 22/tcp
ufw allow from <前端机IP> to any port 8080 proto tcp
ufw deny 8080/tcp
ufw deny 8000/tcp
ufw status verbose
```

---

## 任务 E（后端机）：核对 Hermes 推送 token

**背景**：这次部署把仍是占位值的密钥自动换成了随机值。如果 `HERMES_INGEST_TOKEN`
被换掉了，而外部的 Hermes 发布端还在用旧 token，**每日热点推送会静默失败**
（后端会拒收）。

### E1. 检查

```bash
set -a; . /opt/pulse/backend/.env; set +a
echo "HERMES_INGEST_TOKEN length: ${#HERMES_INGEST_TOKEN}"
echo "HERMES_INGEST_TOKEN prefix: $(printf '%.6s' "$HERMES_INGEST_TOKEN")"
```

**判断规则：**
- 长度 **64**（纯 16 进制）→ 极可能是部署自动生成的，**Hermes 那边需要同步更新**。
- 长度是别的值 → 大概是用户原本自己配的，没被动过，不用管。
- 长度 **0** → 没配置，热点推送功能处于关闭状态（不影响其它功能）。

### E2. 顺便看看热点数据还在不在更新

```bash
curl -s "http://127.0.0.1:8080/api/v1/hot-news/latest" | head -c 400; echo
```
把返回里的日期字段报告出来（是不是今天/最近的）。

### E3. 报告方式（注意保密）

**不要把 token 明文打印在聊天记录里。** 报告写成：

> `HERMES_INGEST_TOKEN` 长度 64、前缀 `a1b2c3`，判断为部署自动生成。
> 请用户自己在服务器上执行 `grep HERMES_INGEST_TOKEN /opt/pulse/backend/.env`
> 取完整值，同步到 Hermes 发布端配置。

---

## 任务 F（后端机）：顺手做的健康体检

```bash
echo "== 后端进程应该只有 1 个 =="
pgrep -af "pulse-backend-1.0.0-SNAPSHOT.jar" | wc -l
pgrep -af "pulse-backend-1.0.0-SNAPSHOT.jar"

echo "== AI 网关进程 =="
pgrep -af "app.main:app"

echo "== 健康检查 =="
# liveness 才是判断「进程是否在服务」的依据
curl -s -o /dev/null -w "liveness: %{http_code}\n" http://127.0.0.1:8080/actuator/health/liveness
# 聚合健康只作为参考：Redis/MySQL 任一异常就是 503，但接口可能仍然可用
curl -s -w "\n" http://127.0.0.1:8080/actuator/health
curl -s -w "\n" http://127.0.0.1:8000/health

echo "== 启动后有没有报错/警告 =="
grep -aE "ERROR|WARN|Started PulseApplication" /opt/pulse/logs/backend.log | tail -20

echo "== .env 关键项是否齐全（只看有没有，不看值）=="
for k in DB_USERNAME DB_PASSWORD JWT_SECRET AES_SECRET SERVICE_TOKEN TRUSTED_PROXIES; do
  if grep -qE "^$k=.+" /opt/pulse/backend/.env; then echo "$k: SET"; else echo "$k: MISSING_OR_EMPTY"; fi
done

echo "== 磁盘 / 内存 =="
df -h / ; free -m
```

**期望：**
- 后端进程数 = **1**（历史上出过重复进程的问题，>1 要报告并停掉多余的）
- `liveness: 200`；AI 网关 `/health` 有正常 JSON 返回
- 聚合 `/actuator/health` 最好是 `{"status":"UP"}`；如果是 `DOWN`/503，
  **不要重启任何东西**，先看是哪个组件（`components` 字段）挂了并报告
- 日志里能找到 `Started PulseApplication`
- `DB_USERNAME`、`DB_PASSWORD`、`JWT_SECRET`、`AES_SECRET`、`TRUSTED_PROXIES` 都是 `SET`
  （`AES_SECRET_LEGACY` 允许为空；`SERVICE_TOKEN` 若为空则后端不校验 AI 网关调用，要报告）
- 磁盘使用率 < 80%

---

## 最终验收清单（全部做完后逐条打勾）

从**任意能上公网的机器**执行：

```bash
echo "1. HTTP 强制跳转 HTTPS"
curl -sI http://www.lililiz.top/pulse/ | grep -E "^HTTP/|^Location:"

echo "2. HTTPS 安全头齐全"
curl -sI https://www.lililiz.top/pulse/ | grep -icE "strict-transport|content-security|x-frame-options|x-content-type-options|referrer-policy|permissions-policy"

echo "3. 入口页不缓存"
curl -sI https://www.lililiz.top/pulse/ | grep -i "cache-control"

echo "4. 博客未受影响"
curl -s -o /dev/null -w "%{http_code}\n" https://www.lililiz.top/

echo "5. 业务接口正常"
for p in "/pulse/api/v1/posts?page=1&size=1" "/pulse/api/v2/bounties?page=1&size=1" "/pulse/api/v1/posts/ranking" "/pulse/api/v1/hot-news/latest"; do
  printf "%s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' "https://www.lililiz.top$p")"
done

echo "6. 未登录访问受保护接口必须 401（不是 200、不是 500）"
for p in "/pulse/api/v2/bounties/my" "/pulse/api/v2/bounties/accepted" "/pulse/api/v1/agents" "/pulse/api/v2/ledger/records"; do
  printf "%s -> %s\n" "$p" "$(curl -s -o /dev/null -w '%{http_code}' "https://www.lililiz.top$p")"
done
```

| # | 期望 |
|---|---|
| 1 | `HTTP/1.1 301` + `Location: https://...` |
| 2 | 计数 = **6** |
| 3 | `Cache-Control: no-cache` |
| 4 | `200` |
| 5 | 四个全部 `200` |
| 6 | 四个全部 `401` |

外加一项**人工检查**：浏览器新开标签页打开 `https://www.lililiz.top/pulse/square`，
确认页面正常渲染，**控制台无报错**（尤其不能有 CSP 的 `Refused to ...`）。

---

## 报告模板（做完后按这个格式回复用户）

```
## 执行结果

任务A nginx 安全头 + HTTPS 跳转：  [完成 / 跳过 / 失败]
  - 改动的文件：<路径>
  - 备份位置：/root/pulse-ops-2026-07-28/nginx-backup
  - 实测：80 -> 301 https ✅ / 安全头 6/6 ✅ / 博客 200 ✅ / API 200 ✅

任务B 数据库迁移：            [完成 / 无需操作 / 因权限阻塞]
  - 迁移前 Schema capabilities：<原始日志行>
  - 迁移后 Schema capabilities：<原始日志行>
  - 数据库备份：/root/pulse-ops-2026-07-28/pulse_db-before-migration.sql (<大小>)

任务C 日志轮转：              [完成 / 失败]
任务D 端口体检：              8000=<监听地址> 8080=<> 3306=<> 6379=<> ufw=<状态>
任务E Hermes token：          长度<N> 前缀<xxxxxx> 判断=<需要/不需要>同步
任务F 健康体检：              后端进程数=<N> health=<> 磁盘=<>%

## 需要用户自己决定的事项
1. <例如：防火墙是否收紧 8080>
2. <例如：Hermes 发布端是否需要换 token>
3. <例如：Redis/MySQL 是否暴露公网>

## 遇到的问题 / 未完成项
<原样贴出命令和输出，不要总结成"可能是……">
```

---

## 附：为什么这些事非要在服务器上做

| 任务 | 仓库里已经准备好的东西 | 为什么仓库做不完 |
|---|---|---|
| A | `deploy/nginx-pulse-prod.conf`、`deploy/nginx-pulse-prod-tls.conf`、`deploy/TLS-SETUP.md` | nginx 配置不在 CI 的部署范围内，而且同域名下还有博客，必须人工合并 |
| B | `deploy/migrations/2026-07-27-optimization.sql`（内容已内联在本文档 B4） | CI 用的数据库账号没有 ALTER/CREATE 权限，也无法自己提权 |
| C | `deploy/logrotate-pulse` | 要写 `/etc/logrotate.d/` |
| D | 代码里 uvicorn 已改绑 `127.0.0.1`；`RateLimitFilter` 已做可信代理校验 | 防火墙规则只能在主机上改 |
| E | `HotNewsServiceImpl` 会拒绝空/错误 token | 外部 Hermes 发布端的配置不在本仓库 |

**另外提醒用户**：这次部署轮换了 `JWT_SECRET`，所有已登录用户的 token 都失效了，
需要重新登录一次。这是预期行为（旧密钥曾以明文写在仓库里，必须换）。
