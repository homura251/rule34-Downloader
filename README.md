# Rule34 Downloader for Android

一个原生 Android / Material 3 应用：从任意 `rule34.xxx` 帖子识别 **artist tag**，按作者创建独立目录，下载该作者的全部原始文件，并在以后只补充新增帖子。

> 本项目不内置图片预览、广告、付费墙或共享 API Key。下载内容来自用户主动添加的 artist tag；请自行遵守所在地法律、Rule34.xxx 的服务条款与 API 使用规则。

## 功能

- 从帖子 URL 或数字 ID 识别 artist tag；若帖子存在多个 artist tag，由用户明确选择。
- 首次同步分页获取该作者全部帖子，保存 `file_url` 指向的原始文件。
- 后续使用上次最高 post ID 做增量发现，已完成文件由本地数据库去重，失败项自动重试。
- 每位作者独立保存到 `Download/Rule34 Downloader/<artist>/`。
- Android 10+ 使用 MediaStore，不申请读/写外部存储权限。
- WorkManager 支持手动同步和可选的 15 分钟 / 1 小时 / 6 小时 / 24 小时后台检查。
- Material Design 3 / Material You：Android 12+ 动态配色、状态切换动画、文件级进度、Snackbar 与完成通知。
- Rule34 API User ID / API Key 使用 Android Keystore AES-GCM 加密后仅保存在本机。
- tag 接口兼容 JSON/XML；批量 tag 查询覆盖不全时会对缺失 tag 做限速精确查询。

## 使用

1. 在 Rule34.xxx 账户设置的 API Access 区域取得自己的 **User ID** 与 **API Key**。
2. 打开 App → 设置，保存凭据。
3. 点“添加作者”，粘贴帖子，例如：
   `https://rule34.xxx/index.php?page=post&s=view&id=18875738`
4. App 读取帖子元数据并列出其中的 artist tag；选择作者后即开始首次全量同步。
5. 以后点“立即同步”，或在设置中开启自动增量同步。

## 存储与权限

最低版本为 Android 10（API 29）。项目刻意不声明 `READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`、照片、定位、联系人、相机、麦克风等权限。

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 调用 Rule34 API、下载原文件 |
| `ACCESS_NETWORK_STATE` | WorkManager 网络约束 |
| `WAKE_LOCK` | WorkManager 在必要时完成后台任务 |
| `RECEIVE_BOOT_COMPLETED` | 由 WorkManager 恢复已计划的周期任务 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | 长时间批量下载时的 data-sync 前台服务 |
| `POST_NOTIFICATIONS` | Android 13+ 的同步进度/完成提示；只在用户开启后台同步时请求 |

文件由 MediaStore 写入公共 Downloads 集合，因此 Android 10+ 不需要传统存储权限。

## API 与增量策略

Rule34 DAPI：`https://api.rule34.xxx/index.php`。

- 帖子：`page=dapi&s=post&q=index&json=1`
- 单帖：使用 `id=<postId>`
- 作者帖子：使用 artist tag；单次最多 1000 条并通过 `pid` 分页
- 增量：保存 `last_seen_post_id`，下一次发现阶段查询 `artist_tag id:>last_seen_post_id`
- 作者识别：读取帖子 tags，再查询 tag 元数据，仅保留 `type=1`（artist）

站点当前要求 API 请求携带用户自己的 `user_id` 与 `api_key`。凭据不会写入源码、日志或 GitHub。

## 构建

要求：

- JDK 17+
- Android SDK 37
- Android Studio / Gradle 9.6.x

```bash
./gradlew testDebugUnitTest assembleDebug
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 结构

```text
app/src/main/java/com/homura251/rule34downloader/
├── data/      # SQLite、偏好设置、Keystore 凭据
├── network/   # Rule34 DAPI 与帖子 URL 解析
├── storage/   # MediaStore 原文件下载
├── work/      # WorkManager 增量同步与通知
└── ui/        # Compose Material 3 / Material You
```

## 设计约束

- 不在 App 内展示成人图片缩略图，添加作者阶段只处理元数据。
- 不自动猜测多个 artist tag 中哪一个才是用户想要的作者。
- 移除作者只停止跟踪，不删除已经保存到 Downloads 的文件。
- 自动同步依赖 WorkManager，系统可能因省电策略延后执行；15 分钟是周期任务允许的最小间隔。

## License

MIT，见 [LICENSE](LICENSE)。
