# Pulse 项目文档中心

## 文档目录结构

```
docs/
├── api/               # API接口文档
├── design/            # UI设计文档
├── guides/            # 开发指南
│   ├── startup_guide.md    # 项目启动指南
│   ├── phase2_tasks.md     # Phase 2任务清单
│   └── tech_stack.md       # 技术栈说明
├── history/           # 历史归档
│   └── phase1/        # Phase 1完成报告
├── MEMORY.md          # 项目状态索引
├── problems/          # 问题分析记录
├── progress/          # 进度追踪
├── prompt/            # Prompt设计文档
├── reports/           # 报告存档
│   ├── bugfix/        # Bug修复历史
│   ├── feature/       # 功能实现记录
│   └── optimization/  # 优化报告
└── requirements/      # 需求文档
```

## 快速导航

### 开发指南
- [启动指南](guides/startup_guide.md) - 项目运行必备
- [Phase 2任务](guides/phase2_tasks.md) - 下阶段规划

### 核心文档
- [最新优化报告](reports/optimization/OPTIMIZATION_REPORT_2026-04-11.md) - 最新功能优化记录
- [项目状态索引](MEMORY.md) - 快速导航索引

### 架构参考
- [Phase 1完成总结](history/phase1/phase1_final_summary.md)
- [API文档](api/) - 接口协议
- [需求文档](requirements/) - 功能需求

### 问题与决策
- [问题分析](problems/) - 问题记录
- [技术决策](decisions/) - 决策记录（待整理）

## 文档价值分级

| 级别 | 说明 | 文档 |
|------|------|------|
| **关键** | 项目运行关键 | startup_guide.md |
| **高** | 架构/决策参考 | API文档、Phase1总结、优化报告 |
| **中** | 进度/历史记录 | bugfix、progress |
| **低** | 已过时/归档 | 可定期清理 |

## 维护指南

1. **定期更新**: MEMORY.md需每周更新
2. **Bugfix合并**: 相同问题的多次修复合并为一条
3. **命名规范**: 使用英文命名，格式如 `BUGFIX_[type]_[date].md`
4. **归档规则**: 完成Phase后移入history目录
