# Pulse Summary 文档中心

> 所有 summary-agent 的工作记录归档位置

## 目录结构

```
pulse-summary/
├── progress/          # 进度追踪
│   ├── phase1_progress.md         # Phase 1 整体进度
│   └── cross_agent_dependencies.md # 跨 Agent 依赖图
├── reports/           # 完成报告
│   ├── done/                      # 已完成模块
│   │   ├── java_phase1_complete.md
│   │   ├── python_phase1_complete.md
│   │   └── frontend_phase1_complete.md
│   ├── decisions/                 # 技术决策记录
│   │   ├── token_atomic_update.md
│   │   └── apikey_encryption.md
│   └── blocking/                  # 阻塞项历史
├── guides/            # 操作指南
│   ├── startup_guide.md           # 项目启动指南
│   └── phase2_tasks.md            # Phase 2 任务清单
├── summary/           # 最终总结
│   ├── phase1_final_summary.md    # Phase 1 完整总结
│   └── MEMORY.md                  # 项目状态索引
└── README.md                       # 本文件
```

## 快速导航

| 模块 | 文档路径 | 说明 |
|------|----------|------|
| 进度追踪 | [progress/](progress/) | 查看各 Agent 完成状态 |
| 技术决策 | [reports/decisions/](reports/decisions/) | 关键技术决策记录 |
| 启动指南 | [guides/startup_guide.md](guides/startup_guide.md) | 如何运行项目 |
| Phase 2 任务 | [guides/phase2_tasks.md](guides/phase2_tasks.md) | 下一步工作 |
| 最终总结 | [summary/phase1_final_summary.md](summary/phase1_final_summary.md) | Phase 1 全貌 |

## Phase 1 完成状态

| Agent | 状态 | 文件数 |
|-------|------|--------|
| Java-Backend-Agent | DONE | 60 |
| Python-AI-Side-Agent | DONE | 14 |
| Frontend-Agent | DONE | ~20 |

**整体进度：90%**（剩余 10% 为集成测试和 Docker 编排）

---

**创建时间：** 2026-03-31
**维护者：** summary-agent