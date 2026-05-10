# Pulse 项目清理计划

**生成时间**: 2026-04-19  
**基于**: 四个子Agent模块报告 + 主Agent跨模块分析

---

## 一、清理目标

将项目从当前混乱状态规整为清晰明了的结构，消除以下问题：
1. 前端框架重复（React模板 + Vue项目）
2. 文档目录分散（6个文档目录）
3. 配置文件重复（AGENTS.md = CLAUDE.md）
4. README无实际内容

---

## 二、需删除的文件/目录

### 2.1 React模板残留（根目录）

这些文件是空的React项目模板，与实际使用的Vue项目完全冲突：

| 文件/目录 | 操作 | 原因 |
|-----------|------|------|
| `src/` (根目录) | **删除** | React空模板，无实际内容 |
| `package.json` (根) | **删除** | React项目配置 |
| `vite.config.ts` (根) | **删除** | React项目配置 |
| `tailwind.config.js` (根) | **删除** | React项目配置 |
| `postcss.config.js` (根) | **删除** | React项目配置 |
| `tsconfig.json` (根) | **删除** | React项目配置 |
| `eslint.config.js` (根) | **删除** | React项目配置 |
| `index.html` (根) | **删除** | React项目入口 |
| `public/` (根) | **删除** | React项目资源 |

### 2.2 重复/冗余文件

| 文件 | 操作 | 原因 |
|------|------|------|
| `AGENTS.md` | **删除** | 与CLAUDE.md内容完全相同 |
| `README.md` (根) | **重写** | 当前是Vite模板默认内容 |
| `.ruff_cache/` | **删除** | Python缓存，应加入gitignore |
| `.trae/` | **检查后删除** | 未知用途目录 |

### 2.3 文档目录合并

将分散的文档合并到统一位置：

| 源目录 | 目标位置 | 操作 |
|--------|----------|------|
| `done/` | `docs/history/done/` | 移动并合并 |
| `problem/` | `docs/problems/` | 移动 |
| `md/需求文档/` | `docs/requirements/` | 移动 |
| `md/接口文档/` | `docs/api/` | 移动 |
| `md/技术栈/` | `docs/guides/` | 移动 |
| `md/页面风格/` | `docs/design/` | 移动 |
| `md/prompt/` | `docs/prompt/` | 移动 |
| `pulse-summary/` | `docs/` | 合并（保留高价值文档） |
| `docs/` (现有) | `docs/` | 作为统一文档目录 |

---

## 三、目标结构

清理后的项目结构：

```
agentCommunity/
│
├── pulse-backend/              # Java后端 (唯一后端)
│   ├── src/main/java/com/pulse/
│   ├── src/main/resources/
│   └── pom.xml
│
├── pulse-frontend/             # Vue前端 (唯一前端)
│   ├── src/
│   ├── public/
│   ├── package.json
│   └── vite.config.js
│
├── pulse-ai-side/              # Python AI服务 (唯一AI服务)
│   ├── app/
│   ├── tests/
│   ├── requirements.txt
│   └── Dockerfile
│
├── docs/                       # 统一文档目录
│   ├── README.md              # 文档入口
│   ├── requirements/          # 需求文档
│   ├── api/                   # API文档
│   ├── guides/                # 开发指南
│   │   ├── startup_guide.md   # 启动指南
│   │   ├── tech_stack.md      # 技术栈
│   │   └── phase2_tasks.md    # Phase 2任务
│   ├── design/                # UI/设计文档
│   ├── decisions/             # 技术决策记录
│   │   ├── token_atomic_update.md
│   │   ├── apikey_encryption.md
│   │   └── dislike-view-feature.md
│   ├── progress/              # 进度记录
│   ├── reports/               # 报告存档
│   │   ├── optimization/      # 优化报告
│   │   ├── bugfix/           # Bug修复历史
│   │   └── feature/          # 功能实现记录
│   └── history/               # 历史归档
│       └── phase1/           # Phase 1完成报告
│
├── summary/                   # 本次分析报告
│   ├── main-report.md         # 主报告
│   ├── backend-report.md      # 后端报告
│   ├── frontend-report.md     # 前端报告
│   ├── ai-side-report.md      # AI服务报告
│   ├── summary-report.md      # 文档现状报告
│   └── cleanup-plan.md        # 本清理计划
│
├── .claude/                   # Claude配置
├── .gitnexus/                 # GitNexus索引
│
├── CLAUDE.md                  # 项目指令 (唯一)
├── README.md                  # 项目介绍 (重写)
├── .gitignore                 # Git忽略配置
├── .mcp.json                  # MCP配置
│
└── (删除: src/, package.json等React残留)
└── (删除: done/, problem/, md/, pulse-summary/ 等分散文档目录)
└── (删除: AGENTS.md)
```

---

## 四、清理步骤

### Phase 1: 删除React模板残留

```bash
# 删除根目录React模板
rm -rf src/
rm -f package.json vite.config.ts tailwind.config.js postcss.config.js
rm -f tsconfig.json eslint.config.js index.html
rm -rf public/
```

### Phase 2: 删除重复文件

```bash
# 删除重复的AGENTS.md
rm -f AGENTS.md

# 删除缓存目录
rm -rf .ruff_cache/
rm -rf .trae/
```

### Phase 3: 创建统一docs目录结构

```bash
# 创建docs子目录
mkdir -p docs/requirements
mkdir -p docs/api
mkdir -p docs/guides
mkdir -p docs/design
mkdir -p docs/prompt
mkdir -p docs/decisions
mkdir -p docs/progress
mkdir -p docs/reports/optimization
mkdir -p docs/reports/bugfix
mkdir -p docs/reports/feature
mkdir -p docs/history/phase1
```

### Phase 4: 移动文档文件

```bash
# 从md/目录移动
mv md/需求文档/* docs/requirements/
mv md/接口文档/* docs/api/
mv md/技术栈/* docs/guides/
mv md/页面风格/* docs/design/
mv md/prompt/* docs/prompt/

# 从pulse-summary/目录合并高价值文档
mv pulse-summary/guides/startup_guide.md docs/guides/
mv pulse-summary/guides/phase2_tasks.md docs/guides/
mv pulse-summary/reports/decisions/* docs/decisions/
mv pulse-summary/summary/phase1_final_summary.md docs/history/phase1/
mv pulse-summary/OPTIMIZATION_REPORT_2026-04-11.md docs/reports/optimization/
mv pulse-summary/reports/bugfix/* docs/reports/bugfix/
mv pulse-summary/reports/feature/* docs/reports/feature/
mv pulse-summary/reports/done/* docs/history/phase1/

# 从done/目录移动
mv done/* docs/history/done/

# 从problem/目录移动
mv problem/* docs/problems/
```

### Phase 5: 删除空目录

```bash
# 删除合并后的空目录
rm -rf md/
rm -rf pulse-summary/
rm -rf done/
rm -rf problem/
rm -rf docs/
```

### Phase 6: 重写README.md

创建新的项目介绍文档，包含：
- 项目名称和简介
- 技术架构（Vue + Spring Boot + FastAPI）
- 模块说明
- 快速启动指南
- 文档导航

---

## 五、更新.gitignore

添加以下忽略项：

```gitignore
# Python缓存
__pycache__/
*.py[cod]
.ruff_cache/

# 临时目录
.trae/

# IDE
.idea/
*.iml
```

---

## 六、执行顺序

1. **Phase 1**: 删除React模板（立即执行，无风险）
2. **Phase 2**: 删除重复文件（立即执行）
3. **Phase 3**: 创建docs目录结构
4. **Phase 4**: 移动文档文件
5. **Phase 5**: 删除空目录
6. **Phase 6**: 重写README.md

---

## 七、风险评估

| 操作 | 风险等级 | 说明 |
|------|----------|------|
| 删除React模板 | **低** | 无实际内容，不影响项目 |
| 删除AGENTS.md | **低** | 与CLAUDE.md重复 |
| 合并文档目录 | **中** | 需确保所有文档正确移动 |
| 删除pulse-summary | **中** | 需先确认高价值文档已保留 |

---

## 八、执行状态

### 清理阶段 (已完成)

| Phase | 任务 | 状态 |
|-------|------|------|
| Phase 1 | 删除React模板残留 | ✅ 完成 |
| Phase 2 | 删除重复文件 | ✅ 完成 |
| Phase 3 | 创建docs目录结构 | ✅ 完成 |
| Phase 4 | 移动文档文件 | ✅ 完成 |
| Phase 5 | 删除空目录 | ✅ 完成 |
| Phase 6 | 重写README.md | ✅ 完成 |

### 优化阶段 (已完成)

详见: [optimization-summary.md](optimization-summary.md)

| 任务 | 状态 |
|------|------|
| JWT/AES密钥安全化 | ✅ 完成 |
| 积分并发安全修复 | ✅ 完成 |
| N+1查询优化 | ✅ 完成 |
| BountyGuild.vue拆分 | ✅ 完成 |
| formatTime统一 | ✅ 完成 |
| HTTPS配置指南 | ✅ 完成 |
| AI验证冗余清理 | ✅ 完成 |

---

**项目当前状态**: ✅ 清理完成 + ✅ 优化完成 = 生产就绪