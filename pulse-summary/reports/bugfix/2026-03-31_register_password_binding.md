---
timestamp: 2026-03-31 19:50:00
source_agent: Human (Bug Report)
category: bugfix
priority: high
status: fixed
---

# Bug 修复：注册功能密码验证失败

## 问题描述
用户注册时，输入正确的密码（包含字母和数字的8位密码），系统提示"密码不能为空"。

## 错误日志
```
2026-03-31T19:45:21.495+08:00  WARN 36312 --- [pulse-backend] [nio-8080-exec-6] 
c.p.exception.GlobalExceptionHandler     : ValidationException: {password=密码不能为空}
```

## 根因分析
**文件：** `pulse-frontend/src/views/Terminal.vue`

**问题：** 密码输入框在注册模式下错误地绑定到了 `loginForm.password`，而注册函数发送的是 `registerForm.password`（空值）。

```vue
<!-- 错误代码 -->
<div class="text-pulse-muted text-xs mb-2">ACCESS_KEY:</div>
<TerminalInput
  v-model="loginForm.password"    <!-- 绑定错误！ -->
  type="password"
/>
```

```javascript
// 注册时发送的是 registerForm.password（空值）
const success = await authStore.register(
  registerForm.value.username,
  registerForm.value.email,
  registerForm.value.password   // 这是空的！
)
```

## 修复方案
根据当前模式（登录/注册）分别绑定不同的表单数据：

```vue
<!-- 修复后的代码 -->
<div v-if="isRegisterMode">
  <div class="text-pulse-muted text-xs mb-2">ACCESS_KEY:</div>
  <TerminalInput
    v-model="registerForm.password"
    type="password"
    placeholder="SecurePassword123"
  />
</div>

<div v-if="!isRegisterMode">
  <div class="text-pulse-muted text-xs mb-2">ACCESS_KEY:</div>
  <TerminalInput
    v-model="loginForm.password"
    type="password"
    placeholder="SecurePassword123"
  />
</div>
```

## 修改文件
- `pulse-frontend/src/views/Terminal.vue`

## 测试验证
1. 输入用户名、邮箱、密码（包含字母和数字的8位密码）
2. 点击注册按钮
3. 应该成功注册并跳转到 Lab 页面

## 教训
- 在表单切换（登录/注册）时，需要确保所有输入字段都绑定到正确的数据对象
- 建议将登录和注册表单拆分为独立组件，避免数据绑定混淆