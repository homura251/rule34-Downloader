# Rule34 Downloader for Android

一个原生 Android / Material 3 应用：按 **artist tag** 建立独立目录，下载 Rule34.xxx 上该作者的原始文件，并在以后只补充新增作品。

> 本项目不内置成人图片预览、广告、付费墙或共享 API Key。下载内容来自用户主动添加的 artist tag；请自行遵守所在地法律、Rule34.xxx 的服务条款与站点限制。

## 功能

- 添加作者支持三种输入：
  - 直接输入单个 artist tag，例如 `savvyraexo`
  - 粘贴帖子 URL / Post ID，自动识别页面中的 artist tag
  - 粘贴单作者搜索 URL，例如 `https://rule34.xxx/index.php?page=post&s=list&tags=savvyraexo`
- **API 凭据可选**：
  - 已配置 User ID + API Key：优先使用 DAPI，速度更快、批量同步更稳定
  - 未配置：自动使用公开网页匿名解析，仍可发现帖子并取得 “Original image” 原文件链接
- 首次同步下载该作者全部作品；后续保存最高 post ID，只补充新增帖子。
- 每位作者独立保存到 `Download/Rule34 Downloader/<artist>/`。
- Android 10+ 使用 MediaStore，不申请读/写外部存储权限。
- WorkManager 支持手动同步和可选的 15 分钟 / 1 小时 / 6 小时 / 24 小时后台检查。
- Material Design 3 / Material You：Android 12+ 动态配色、状态切换动画、文件级进度、Snackbar 与完成通知。
- API User ID / API Key 使用 Android Keystore AES-GCM 加密后仅保存在本机。
- 匿名模式遇到 HTTP 429 / Cloudflare 验证时会给出明确错误，不会申请浏览器、Cookie 或额外 Android 权限绕过验证。

## 使用

1. 打开 App，直接点“添加作者”。
2. 输入 artist tag，或者粘贴帖子/搜索链接。
3. 确认 artist tag 后开始同步。
4. 不配置 API 也能使用；如果希望大批量同步更快、更稳定，可在设置中填写自己的 Rule34 **User ID** 与 **API Key**。
5. 以后点“同步”，或在设置中开启自动增量同步。

## 匿名模式与 API 模式

Rule34 的 DAPI 当前要求 `user_id` 与 `api_key`。App 在未配置凭据时不会调用需要认证的 DAPI，而是解析公开网页：

1. 作者搜索页读取每页 Post ID（当前网页每页 42 项）。
2. 逐条读取帖子详情页。
3. 从详情页 Options / `.link-list` 的 “Original image” 获取原文件 URL。
4. 从 `#tag-sidebar .tag-type-artist` 识别帖子中的 artist tag。
5. 对匿名请求主动限速；遇到 429 / Cloudflare challenge 时停止并提示稍后重试或配置 API。

因此匿名模式可用，但首次同步作品很多的作者会明显慢于 API 模式。

## 存储与权限

最低版本为 Android 10（API 29）。项目刻意不声明 `READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、照片、定位、联系人、相机、麦克风等权限。

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 访问 Rule34 API/公开网页并下载原文件 |
| `ACCESS_NETWORK_STATE` | WorkManager 网络约束 |
| `WAKE_LOCK` | WorkManager 在必要时完成后台任务 |
| `RECEIVE_BOOT_COMPLETED` | 由 WorkManager 恢复已计划的周期任务 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | 长时间批量下载时的 data-sync 前台服务 |
| `POST_NOTIFICATIONS` | Android 13+ 的同步进度/完成提示；只在用户开启后台同步时请求 |

文件由 MediaStore 写入公共 Downloads 集合，因此 Android 10+ 不需要传统存储权限。

## 构建

要求：

- JDK 17+
- Android SDK 37
- Android Studio / Gradle 9.6.x

```bash
./gradlew testDebugUnitTest assembleDebug
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

带有 `[release-debug]` 的 main 提交在 CI 构建通过后会发布/刷新 `debug-v1.1.0` GitHub prerelease，并附带 `rule34-Downloader-v1.1.0-debug.apk`。

## 结构

```text
app/src/main/java/com/homura251/rule34downloader/
├── data/      # SQLite、偏好设置、Keystore 凭据
├── network/   # Rule34 DAPI、匿名网页解析、输入解析
├── storage/   # MediaStore 原文件下载
├── work/      # WorkManager 增量同步与通知
└── ui/        # Compose Material 3 / Material You
```

## 设计约束

- 不在 App 内展示成人图片缩略图，添加作者阶段只处理元数据。
- 不自动猜测多个 artist tag 中哪一个才是用户想要的作者。
- 用户直接输入 artist tag 时将该 tag 视为用户明确选择，不额外要求 API 验证。
- 移除作者只停止跟踪，不删除已经保存到 Downloads 的文件。
- 自动同步依赖 WorkManager，系统可能因省电策略延后执行；15 分钟是周期任务允许的最小间隔。
- 匿名网页模式遵守站点响应；不会尝试绕过 Cloudflare/验证码。

## License

MIT，见 [LICENSE](LICENSE)。
