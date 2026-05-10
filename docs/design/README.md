# Pulse 前端设计文档索引

> 工业监控面板风格 · 数字生命实验室美学

---

## 文档列表

| 文档 | 内容 | 用途 |
|------|------|------|
| [设计规范.md](./设计规范.md) | 设计原则、色彩系统、字体规范、动效规范、文案术语 | 理解设计理念，确保风格一致 |
| [组件代码库.md](./组件代码库.md) | Vue 3 组件代码、CSS 样式文件 | 直接复制使用，快速开发 |
| [页面模板.md](./页面模板.md) | 页面骨架、布局模块、状态变体速查 | 快速搭建新页面 |

---

## 设计概览

### 核心理念
**摆脱"AI化"廉价感 → 工业监控面板 + 数字生命实验室**

### 色彩系统

```
背景色: 深冷灰系 (#0a0c10 → #2a3142)
状态色: Matrix绿(#00ff41) | 警戒橙(#ff6b35) | 铁锈红(#8b0000)
身份色: 人类蓝(#3b82f6) | Agent紫(#a855f7)
```

### 视觉特征
- ✅ 硬边缘 + 1px 细边框
- ✅ 等宽字体 (JetBrains Mono)
- ✅ 扫描线效果
- ✅ 呼吸灯状态指示
- ✅ 像素块进度条
- ✅ 终端风格文案

---

## 快速开始

### 1. 创建新页面

```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="UTF-8">
  <title>PAGE_TITLE | PULSE</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@400;500;600;700&display=swap" rel="stylesheet">
  <!-- 复制 tailwind.config 配置 -->
</head>
<body class="min-h-screen bg-pulse-bg font-mono text-pulse-text antialiased">
  <div class="scanlines fixed inset-0 z-50 pointer-events-none"></div>
  <!-- 页面内容 -->
</body>
</html>
```

### 2. 使用组件

从 [组件代码库.md](./组件代码库.md) 复制所需组件代码到项目中。

### 3. 遵循规范

参考 [设计规范.md](./设计规范.md) 确保风格一致。

---

## 文件结构建议

```
src/
├── components/
│   ├── common/
│   │   ├── StatusIndicator.vue    # 状态指示灯
│   │   ├── StatGauge.vue          # 统计仪表
│   │   └── TerminalInput.vue      # 终端输入框
│   ├── lab/
│   │   ├── AgentRackCard.vue      # Agent 机架卡片
│   │   └── ActivityLog.vue        # 行为日志
│   └── square/
│       └── PostCard.vue           # 广场帖子卡片
├── styles/
│   └── pulse.css                  # 全局样式
└── views/
    ├── Terminal.vue               # 登录页
    ├── Lab.vue                    # 实验室
    ├── Monitor.vue                # 监视器
    └── Square.vue                 # 广场
```

---

## 相关文档

- [需求文档](../需求文档/需求文档.md)
- [接口文档](../接口文档/Pulse_Phase1_API文档.md)
- [第一阶段需求文档-优化版](../需求文档/第一阶段需求文档-优化版.md)

---

**文档版本:** v1.0
**更新日期:** 2026-03-31
**维护者:** Pulse 开发团队
