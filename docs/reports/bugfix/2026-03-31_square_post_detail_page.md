---
timestamp: 2026-03-31 22:00:00
source_agent: Human (Bug Report)
category: bugfix
priority: high
status: fixed
---

# Bug 修复：Square 帖子详情页和评论数量显示

## 问题描述

1. **帖子不能查看详情页** - Square 页面点击帖子或评论按钮无法跳转到详情页
2. **评论数量显示问题** - 需要确认评论数量是否正确显示

## 根因分析

### 问题 1: 帖子详情页缺失

- `PostDetail.vue` 页面不存在
- 路由配置中没有 `/post/:id` 路由
- `Square.vue` 的 `handleComment` 函数只打印日志，没有跳转逻辑

### 问题 2: 评论数量

数据库验证：
```sql
-- 帖子表
SELECT id, comment_count FROM posts WHERE deleted = 0;
-- 结果：id=1 有 13 条评论，id=2,3 有 0 条

-- 评论表
SELECT post_id, COUNT(*) FROM comments WHERE deleted = 0 GROUP BY post_id;
-- 结果：post_id=1 有 13 条评论
```

**结论**：数据一致，评论数量是正确的。显示为 0 是因为那两个帖子确实没有评论。

## 修复方案

### 1. 创建 PostDetail.vue

新建帖子详情页面，包含：
- 帖子内容显示
- 点赞/取消点赞
- 评论列表
- 发表评论功能

### 2. 添加路由

```javascript
{
  path: '/post/:id',
  name: 'PostDetail',
  component: () => import('@/views/PostDetail.vue'),
  meta: { requiresAuth: true }
}
```

### 3. 修改 Square.vue

```javascript
// Handle comment (navigate to post detail)
const handleComment = (postId) => {
  router.push(`/post/${postId}`)
}

// Navigate to post detail
const viewPost = (postId) => {
  router.push(`/post/${postId}`)
}
```

### 4. 修改 PostCard.vue

- 添加 `@click` 事件跳转到详情页
- 添加 `@click.stop` 阻止点赞/评论按钮的事件冒泡
- 添加 cursor-pointer 和 hover 效果

## 修改文件

| 文件 | 修改内容 |
|------|----------|
| `PostDetail.vue` | 新建帖子详情页面 |
| `router/index.js` | 添加 /post/:id 路由 |
| `Square.vue` | 添加跳转逻辑 |
| `PostCard.vue` | 添加点击跳转功能 |

## 功能说明

### 帖子详情页功能

1. **显示帖子内容**
   - 作者信息（头像、名称、类型）
   - 发布时间
   - 帖子内容

2. **交互功能**
   - 点赞/取消点赞
   - 查看评论数量
   - 发表评论

3. **评论列表**
   - 按时间正序显示
   - 显示评论者信息
   - 区分 Human/Agent 评论

### 导航逻辑

- 点击帖子卡片任意位置 → 跳转到详情页
- 点击评论按钮 → 跳转到详情页
- 点赞按钮独立工作，不触发跳转

## 验证步骤

1. 重启前端开发服务器
2. 访问 Square 页面
3. 点击帖子卡片 → 应跳转到详情页
4. 点击评论按钮 → 应跳转到详情页
5. 在详情页查看评论列表
6. 发表评论，验证评论数量更新