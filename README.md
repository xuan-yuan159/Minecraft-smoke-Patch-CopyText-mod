# Smoke Chat Copy Patch v1.0.1

这是适用于工作区 Smoke 1.8.9 PvP 客户端的独立聊天复制补丁。客户端运行于 Java 8；补丁后的运行时类同样是 Java 8 字节码。

## 功能

- 聊天窗口打开时，右键单击一条消息即可复制其完整可见文本。
- 按住左 Alt 并左键单击一条消息也可复制。
- 彩色、富文本与自动换行消息会还原为完整逻辑消息，而不是鼠标所在的单词或行片段。
- 剪贴板文本不包含 Minecraft `§` 颜色和样式码。
- 未按左 Alt 的普通左键仍保留链接、命令、输入框和原有界面点击行为。
- 修复 Smoke AutoGG 遗漏 Hypixel 结算规则的问题：命中客户端内置的结算提示后发送已有 AutoGG 自定义文本，未配置时发送 `GG`，同一局的重复结算提示会在 2.5 秒内去重。

## 支持的客户端

补丁只支持 SHA-256 为以下值的原始 `Smoke.jar`：

```text
C600FD3C23FB95437B76CA7AC83EA60A26832BECB3796ED9F47DC519EF541CCD
```

输入 Jar 不匹配时，补丁工具会拒绝输出文件。该检查也避免将补丁应用到其他 Smoke 构建。

## 安装已生成补丁

1. 完全关闭 Minecraft 启动器和游戏进程。
2. 备份 `.minecraft/versions/Smoke/Smoke.jar`。
3. 将本包的 `patch/Smoke.jar` 复制到该版本目录并替换同名文件。
4. 将 `config/chatcopy.json` 复制到 Minecraft 游戏目录的 `smoke/chatcopy.json`。
5. 使用 Java 8 启动 Smoke；修改配置后重启游戏生效。

本包不包含未修改的原始客户端 Jar，仅包含由上述精确原始版本生成的补丁版 Jar。

## 从源码生成

补丁生成工具需要 JDK 17 或更高版本。它不会改动输入 Jar，默认在输入 Jar 同级写出 `Smoke.jar.chatcopy.patched`。

```powershell
Set-Location <补丁包目录>
.\patch-chat-copy.ps1 -ClientJar <原始 Smoke.jar 的完整路径>
```

也可显式指定安全的输出位置：

```powershell
.\patch-chat-copy.ps1 -ClientJar <原始 Smoke.jar 的完整路径> -OutputJar <补丁输出路径>
```

脚本会在包内创建可删除的 `build/` 编译目录。它不读取输入 Jar 作为 Java 编译类路径，因此可兼容 Smoke 内未标注 UTF-8 的 GBK 文件名条目。

## 配置

`smoke/chatcopy.json` 的默认内容：

```json
{
  "enabled": true,
  "rightClick": true,
  "modifierKey": 56
}
```

- `enabled`：是否启用聊天复制。
- `rightClick`：是否允许右键直接复制。
- `modifierKey`：左键复制所需的 LWJGL 键码，默认 `56` 为左 Alt。

## 内容说明

- `src/`：Java 8 运行时钩子与 ASM/原始 ZIP 补丁工具源码。
- `patch-chat-copy.ps1`：从原始 Smoke Jar 生成独立补丁副本的脚本。
- `patch/`：已生成的 `Smoke.jar` 补丁产物。
- `config/`：默认聊天复制配置。

该仓库只提供代码、配置、脚本和补丁产物，不创建 GitHub Release。
