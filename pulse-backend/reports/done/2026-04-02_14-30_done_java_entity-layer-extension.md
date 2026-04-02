---
timestamp: 2026-04-02 14:30:00
source_agent: Java Backend Agent
tech_stack: Java
category: done
status: done
priority: high
---
# 完成任务：实体层修改 - Dislike/PostView/Like/Post

## 变更概述
实现了实体层的扩展，新增两个实体类并扩展两个现有实体类。

## 具体变更

### 1. 新建 Dislike.java
路径: `pulse-backend/src/main/java/com/pulse/entity/Dislike.java`
- 表名: `dislikes`
- 字段: id, userId, authorType, authorId, postId, createdAt
- 主键自增，createdAt 自动填充
- **db_schema_change**: 需创建 dislikes 表

### 2. 新建 PostView.java
路径: `pulse-backend/src/main/java/com/pulse/entity/PostView.java`
- 表名: `post_views`
- 字段: id, userId, authorType, authorId, postId, firstViewedAt, lastViewedAt
- 主键自增，firstViewedAt 自动填充
- **db_schema_change**: 需创建 post_views 表

### 3. 扩展 Post.java
路径: `pulse-backend/src/main/java/com/pulse/entity/Post.java`
- 新增字段: `dislikeCount` (踩数量) - 位于 likeCount 之后
- 新增字段: `viewCount` (浏览量) - 位于 commentCount 之后
- **db_schema_change**: posts 表需添加 dislike_count 和 view_count 列

### 4. 扩展 Like.java
路径: `pulse-backend/src/main/java/com/pulse/entity/Like.java`
- 新增字段: `authorType` (表态者类型 HUMAN/AGENT) - 位于 userId 之后
- 新增字段: `authorId` (表态者ID) - 位于 authorType 之后
- **db_schema_change**: likes 表需添加 author_type 和 author_id 列

## 技术细节
- 使用 @Data 和 @TableName 注解保持风格一致
- 主键使用 @TableId(type = IdType.AUTO)
- 时间字段使用 @TableField(fill = FieldFill.INSERT) 自动填充
- 支持人类用户和 Agent 的统一表态/浏览追踪

## 下一步
- Mapper层实现 (Task #5 pending)
- Service层实现 (Task #6 pending)
- Controller层实现 (Task #7 pending)