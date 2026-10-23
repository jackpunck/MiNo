# Android 正式签名与更新元数据

正式 APK 必须使用长期保存的发布密钥。当前流水线只负责签名、校验和发布文件；更新元数据生成不代表手机端安装更新流程已验收。Android 客户端提供手动检查、流式下载、校验和系统安装入口。

## 用户唯一需要完成的准备

自行准备并离线备份正式 keystore、alias 和两个密码，在 GitHub 仓库 Settings → Secrets and variables → Actions 中配置以下 **repository secrets**：

| Secret | 内容 |
| --- | --- |
| `MINO_KEYSTORE_BASE64` | 正式 keystore 文件完整字节的 Base64 编码，不是文件路径 |
| `MINO_KEYSTORE_PASSWORD` | keystore 密码 |
| `MINO_KEY_ALIAS` | 正式签名 key alias |
| `MINO_KEY_PASSWORD` | 对应私钥密码 |

本次没有生成、读取、提交真实密钥，也没有配置远程 secrets。Base64 只是编码，不是加密，同样必须保密。不要把正式密钥放进仓库或使用 debug keystore 代替。`.gitignore` 已忽略常用 keystore、Base64 签名文件及 `android/signing/`、`android/signing.env`，但它不能保护已跟踪文件或任意命名文件。后续覆盖安装需要相同签名；请独立备份密钥，GitHub secrets 不能替代备份。此前安装 benchmark/debug 包的设备不能直接用正式包覆盖安装，请先备份用户数据再处理安装迁移。

## 本地配置

Gradle 从进程环境读取四个值，不读取仓库里的密码文件：

| 环境变量 | 含义 |
| --- | --- |
| `MINO_KEYSTORE_PATH` | 正式 keystore 路径，建议绝对路径 |
| `MINO_KEYSTORE_PASSWORD` | keystore 密码 |
| `MINO_KEY_ALIAS` | 正式签名 alias |
| `MINO_KEY_PASSWORD` | 私钥密码 |

在安全的本地会话注入这些变量后，由用户执行 `android/gradlew :app:assembleRelease`（在 Windows 用 `android/gradlew.bat`）。不要把密码写到命令行参数、脚本、日志或 Gradle 属性里，也不要启用输出环境变量的调试日志。release 任务图缺任何一项或 keystore 文件不存在都会明确失败，不会降级到 debug 签名。debug、benchmark、probe 仍可在没有正式密钥时构建；benchmark 保持原有 debug 签名。

## Android 版本

默认读取 `src-tauri/tauri.conf.json`，目前 `versionName = 0.5.0`，`versionCode = 500`。可以独立设置：

- `ANDROID_VERSION_NAME`：规范的三段数字版本，例如 `0.5.1`。
- `ANDROID_VERSION_CODE`：`1..2100000000` 的十进制整数。

不设置版本码时按最终版本名计算 `major * 10000 + minor * 100 + patch`。版本组件无前导零；major 不超过 210000，minor、patch 不超过 99；最终版本码仍受上限约束。空字符串属于非法本地覆盖。发布前请确保版本码比用户已安装版本更高，流水线不联网查询历史版本码。

GitHub CI 可通过同名 **repository variables** 覆盖 Android 正式包版本；未配置（GitHub 返回空字符串）时使用默认值。两项分别生效，不改桌面版本或 tag。tag 使用 `vX.Y.Z`，Android 版本独立时资产依然挂在触发构建的该 tag Release 下。

## CI 与发布

PR/main 的 Android 验证仍运行现有单测、lint、benchmark 和 instrumentation APK 构建，不读取发布 secrets。Node 元数据测试加入现有 `check` job。

推送 `v*` tag 才会运行 `android-release`：

1. 等待 `check` 和 Android 验证通过；解析最终 Android 版本。
2. 检查四项 secrets，只报告缺失名称；缺失就失败，不发布 Android APK。
3. 使用限制文件权限，把 keystore 解码至 `$RUNNER_TEMP/mino-release.jks`，只在构建步骤注入密码；禁用 configuration cache 并使用 `--no-daemon`。该文件不在工作区或上传路径内。
4. 执行 `:app:assembleRelease`；退出 trap 和 `always()` 清理步骤删除临时 keystore（runner 被强制销毁时由临时 runner 生命周期清理）。
5. 用 `apksigner verify` 验证签名，用 `aapt dump badging` 检查包名、实际版本和 minSdk，再重命名与计算哈希。
6. 保存独立 artifact `MiNo-Android-release`。`android-publish` 等 Windows 打包/Release job 完成，再向同一个 Release 追加 Android 文件，避免并发创建 Release；不会覆盖 Windows assets 或 Release notes。

正式发布文件：

- `MiNo_android_<versionName>.apk`
- `android-update.json`

工作流默认 `contents: read`；仅 Windows 发布 job 和 Android 上传 Release job 使用 `contents: write`。签名构建 job 没有写权限。手动 dispatch 保持现有 Windows 出包行为，不启动 Android 正式发布。Android 签名失败不影响已经成功发布的 Windows 文件，但绝不会上传 benchmark/debug 作为 Android 正式包。

## 更新元数据和本地测试

生成器仅使用 Node 内置模块，读取最终 APK 的实际字节计算 size（字节数）与小写 SHA-256，不发网络请求。例如在仓库根目录执行：

```sh
node --test tools/generate-android-update.test.mjs
node tools/generate-android-update.mjs --apk /path/to/signed.apk --output /path/to/android-update.json --tag v0.5.0 --notes "Android 发布说明"
```

格式示例（size、sha256 必须由真实 APK 生成，以下仅说明字段）：

```json
{
  "schema": 1,
  "versionName": "0.5.0",
  "versionCode": 500,
  "minSdk": 26,
  "apkUrl": "https://github.com/jackpunck/MiNo/releases/download/v0.5.0/MiNo_android_0.5.0.apk",
  "size": 123456,
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "notes": "MiNo Android 0.5.0"
}
```

生成器拒绝非法数值、版本、空 APK、不匹配的 URL 和非法 SHA-256；URL 严格限定为 `https://github.com/jackpunck/MiNo/releases/download/<tag>/MiNo_android_<versionName>.apk`，禁止凭据、query、fragment 或其他仓库。`notes` 是字符串，省略时为空；CI 使用简短版本说明。生成器不解析 APK，也不判断其签名真伪，CI 在生成前单独验证。SHA-256 用于校验下载内容，不代替 Android 的签名验证。

Node 测试通过不代表 Gradle 构建、正式签名、Release 上传或手机覆盖安装已通过；本地验收与正式渠道发布需分别验证；正式密钥未配置前不发布正式 APK。

配置依据：[Android 签名配置](https://developer.android.com/build/building-cmdline)、[GitHub Actions 权限](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)。
## 第一次生成正式密钥

在仓库外选择一个长期保存目录，例如 `C:\Users\jackp\MiNoSigning`。使用 JDK 的 `keytool` 交互生成（以下命令不会把密码写入历史）：

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\MiNoSigning"
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore "$env:USERPROFILE\MiNoSigning\mino-release.jks" -alias mino-release -keyalg RSA -keysize 3072 -validity 10000 -storetype JKS
```

自行输入并保存密钥库密码、私钥密码和证书信息。把密钥文件与密码分别做离线备份，以后一直用同一份。不要把文件或密码发到对话中。上传 GitHub secrets 可在浏览器设置中完成，或使用已登录的 `gh secret set` 从标准输入读取；避免把密码写在命令行参数。keystore 的 Base64 可用本地文件内容编码后直接送入 secret 输入，不要打印到终端或保存进仓库。

APK 上传成功后再上传 `android-update.json`，避免先让手机发现尚未上传完成的包。正式更新链路仍须发布一次更高版本进行实机覆盖安装验收；本地调试签名验收不等于正式发布成功。
