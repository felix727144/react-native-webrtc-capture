---
AIGC:
    ContentProducer: Minimax Agent AI
    ContentPropagator: Minimax Agent AI
    Label: AIGC
    ProduceID: "00000000000000000000000000000000"
    PropagateID: "00000000000000000000000000000000"
    ReservedCode1: 304502200ad6bbe2ea5cc8f09d3f6bc6965fe043a19e3e6e165bfa847c819ab65361cd5c022100885e0ea971b43a9958755e86b4aeca7de270f685ba73aa6570e05f5a16321787
    ReservedCode2: 304402206828a6c0072911197e88c8ef812102c80c1d09b5b550c132b5dd0fe0466eef780220264fa26ce0424ae2964b499b5ba53d5b259102aee671da43c72b3bfa74c682ec
---

# 发布到私有库指南

## 问题解决

刚才的修改已经移除了 `husky install`，解决了发布失败的问题。

## 发布步骤

### 方法 1：使用 npm publish（推荐）

```bash
# 确保使用私有库
nrm use huban

# 先构建 TypeScript
npm run build

# 直接发布（跳过 prepare 脚本）
npm publish --ignore-scripts
```

### 方法 2：使用 npmignore 排除文件

如果你想完全控制发布内容，可以创建 `.npmignore` 文件：

```bash
# 创建 .npmignore
cat > .npmignore << 'EOF'
.git
.gitignore
node_modules
*.log
.DS_Store
*.lock
package-lock.json
.env
.env.*
.eslintrc*
.prettierrc*
tsconfig.json
.babelrc
metro.config.js
metro.config.macos.js
react-native.config.js
tools
examples
.github
*.md
!Documentation/*.md
!lib/**/*.d.ts
EOF

# 发布
npm publish --ignore-scripts
```

### 方法 3：创建发布脚本

在项目根目录创建 `publish.sh`：

```bash
#!/bin/bash

# 设置为私有库
nrm use huban

# 安装依赖（如果需要）
npm install

# 构建
npm run build

# 发布（跳过 prepare）
npm publish --ignore-scripts
```

Windows 用户创建 `publish.bat`：

```batch
@echo off
REM 设置为私有库
nrm use huban

REM 安装依赖（如果需要）
npm install

REM 构建
npm run build

REM 发布（跳过 prepare）
npm publish --ignore-scripts
```

## 私有库配置

确保你的 `.npmrc` 文件正确配置：

```ini
registry=https://registry.startdd.com
always-auth=true
```

或者在 `package.json` 中指定：

```json
{
  "publishConfig": {
    "registry": "https://registry.startdd.com"
  }
}
```

## 完整发布命令

```bash
# 完整发布流程
npm install
npm run build
npm publish --ignore-scripts
```

## 如果 bob build 失败

如果 `npm run build` 也失败，可以手动构建：

```bash
# 使用 npx 直接运行 bob
npx react-native-builder-bob build

# 或者跳过 JS 构建，直接发布（原生代码不需要构建）
npm publish --ignore-scripts
```

## 验证发布

```bash
# 检查是否在私有库中
npm view react-native-webrtc --registry=https://registry.startdd.com

# 安装测试
npm install react-native-webrtc --registry=https://registry.startdd.com
```
