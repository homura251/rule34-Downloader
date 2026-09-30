# Rule34 Downloader for Android

一个原生 Android / Material 3 应用：按 **artist tag** 建立独立目录，下载 Rule34.xxx 上该作者的原始文件，并在以后只补充新增作品。

> 本项目无广告、付费墙或共享 API Key。图片内容来自用户主动添加的 artist tag，并在用户打开画师相册时加载；请自行遵守所在地法律、Rule34.xxx 的服务条款与站点限制。

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
- 同步支持暂停/继续；暂停状态持久保存，自动同步跳过暂停的画师。已完成的文件保留，正在传输的文件在继续后重新下载。
- 重装后可在设置中“关联旧下载目录”，重新添加同一画师并同步时校验、复用已有文件，恢复本地预览和完成状态。
- **画师相册**：点击画师卡片中的“查看作品”，按帖子 ID 从新到旧显示缩略图和下载状态，支持分页加载。
- **全屏预览**：点图片后可左右滑动、双指缩放和双击放大/复位，支持常见图片与 GIF；视频可打开原帖或使用系统播放器查看已下载文件。
- 图片优先读取已下载的 MediaStore 文件，可离线浏览；本地文件不存在时回退到在线图片。在线缩略图和大图复用 Cloudflare 会话。
- Android 10+ 使用 MediaStore，不申请读/写外部存储权限。
- WorkManager 支持手动同步和可选的 15 分钟 / 1 小时 / 6 小时 / 24 小时后台检查。
- Material Design 3 / Material You：Android 12+ 动态配色、状态切换动画、文件级进度、Snackbar 与完成通知。
- API User ID / API Key 使用 Android Keystore AES-GCM 加密后仅保存在本机。
- 匿名网页与原文件下载共享 WebView Cookie 和 User-Agent；Cloudflare 验证会先尝试通过内置 WebView 完成，需要手动操作时可从设置进入“网页验证”。不增加 Android 权限。

## 使用

1. 打开 App，直接点“添加作者”。
2. 输入 artist tag，或者粘贴帖子/搜索链接。
3. 确认 artist tag 后开始同步。
4. 不配置 API 也能使用；如果希望大批量同步更快、更稳定，可在设置中填写自己的 Rule34 **User ID** 与 **API Key**。
5. 以后点“同步”，或在设置中开启自动增量同步。
6. 点画师卡片的“查看作品”进入相册。相册展示同步已发现的帖子，未下载的图片也可在线预览；首次添加画师时先等待同步发现作品。
7. 同步中点击“暂停”，待显示“已暂停”后点“继续”；相册工具栏也有暂停/继续按钮。

### 重装后复用旧图片

卸载会清除应用数据库和目录授权；公共 Downloads 中的文件会保留，但重装后的应用不能直接读取原安装留下的所有文件。

1. 在设置中点“关联旧下载目录”，选择 `Download/Rule34 Downloader` 子文件夹，也可以选择单个画师文件夹。Android 11+ 不允许授权整个 Downloads 根目录，应进入子文件夹再确认。
2. 重新添加原来的画师 tag，并同步。应用读取帖子元数据后，按画师目录、帖子 ID、文件类型以及可用的 MD5 校验关联旧文件，恢复“已下载”和本地预览，避免重复下载。
3. 空文件、校验不匹配的文件不会视作完成。缺失文件仍正常下载；目录授权失效时请重新选择目录。

这个流程恢复与重新添加的画师匹配的本地文件，不恢复被卸载清除的账号凭据或同步历史。关联操作只请求所选目录的读取授权，不增加照片或全盘存储权限。暂停为文件级继续，不使用 HTTP Range 续传当前半个文件。

### 多图片与漫画帖子

当前按“一个帖子 ID 对应一个原文件 URL”处理 API 的 `file_url` 或网页的 `Original image`。多个帖子组成的连载或合集，只要各帖子匹配所添加的画师 tag，就会按各自 ID 分别同步；GIF 多帧和视频仍作为一个原文件保存。

目前没有实现单个帖子包含多个独立原文件附件的解析，也没有 Pool 整体导入或压缩包自动解压；相册跨帖子切换不等于单帖多附件适配。若遇到这类具体页面，需要根据其链接和接口另行适配。

## 匿名模式与 API 模式

Rule34 的 DAPI 当前要求 `user_id` 与 `api_key`。App 在未配置凭据时不会调用需要认证的 DAPI，而是解析公开网页：

1. 作者搜索页读取每页 Post ID（当前网页每页 42 项）。
2. 逐条读取帖子详情页。
3. 从详情页 Options / `.link-list` 的 “Original image” 获取原文件 URL。
4. 从 `#tag-sidebar .tag-type-artist` 识别帖子中的 artist tag。
5. 对匿名请求主动限速；普通 429 / 服务端错误使用退避重试。检测到 Cloudflare challenge 时，通过主线程 WebView 尝试完成验证，最多等待 30 秒，再携带共享 Cookie 重试原请求一次。
6. 需要点击或无法自动完成的验证：打开设置 → “网页验证”，完成后重新同步。验证会话仅保存在本机，并供后续网页请求和原文件下载复用。验证页不加载帖子图片。

首次同步作品很多的作者会明显慢于 API 模式。验证仍受站点策略和系统 WebView 版本影响；验证超时会明确提示，不会无限重试或把验证网页保存为原文件。

实现参考 [Tachiyomi CloudflareInterceptor](https://github.com/izfaruqi/tachiyomi/blob/master/app/src/main/java/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt) 的 WebView / Cookie / 重试流程；同时使用 `cf-mitigated: challenge` 和旧验证页特征识别挑战，避免把普通限流当作验证。

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

推送到 `main` 且提交信息包含 `[release-debug]` 时，CI 测试和构建通过后自动发布/刷新对应版本的 GitHub prerelease。版本号读取 `app/build.gradle.kts` 的 `versionName`：当前为 `debug-v1.3.0`，附件为 `rule34-Downloader-v1.3.0-debug.apk`。其他版本的 Release 保留。

普通 main 提交和 PR 会构建并上传调试 APK artifact，并使用临时测试密钥验证 Release 构建与签名；临时密钥签出的 Release APK 不会上传或发布。发布调试版本时，应在最终合入 main 的提交标题或内容中保留 `[release-debug]`。

## 固定密钥签名的 Release APK

`Signed Android Release` 工作流使用你保存在 GitHub Actions Secrets 中的固定密钥生成 Release APK。它会运行项目现有的单元测试、启用代码和资源压缩，并通过 `apksigner verify` 检查签名后上传 APK。调试构建仍使用默认调试签名。

### 1. 准备并备份密钥

已有这个应用的发布密钥时，继续使用原密钥。没有密钥时，可以在自己电脑的终端运行以下命令（需要 JDK，Android Studio 也自带 JDK）：

```bash
keytool -genkeypair -v -storetype JKS -keystore rule34-release.jks -alias rule34 -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Rule34 Downloader"
```

按提示设置密钥库密码和密钥密码；密钥密码可以直接按回车，使用相同密码。这里的别名是 `rule34`。妥善备份 `rule34-release.jks`、两个密码和别名，后续更新继续使用同一密钥；仅有 APK 或公钥证书不能恢复私钥。

把密钥库编码为 Base64。在 Windows PowerShell 中运行：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\rule34-release.jks))) | Set-Clipboard
```

Linux / macOS 可以生成一个文件，再复制其中的内容：

```bash
base64 < rule34-release.jks > rule34-release.b64
```

Base64 是私钥文件的另一种表示方式，应与密钥文件一样保管；项目已忽略常见密钥库、Base64 文件和 `keystore.properties`。

### 2. 添加四个 Repository Secrets

进入仓库的 [Settings → Secrets and variables → Actions](https://github.com/homura251/rule34-Downloader/settings/secrets/actions)，点击 **New repository secret**，逐个添加：

| Secret 名称 | 值 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 完整密钥库文件的 Base64 内容，不能填文件路径 |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库密码 |
| `ANDROID_KEY_ALIAS` | 密钥别名；上述命令生成的是 `rule34` |
| `ANDROID_KEY_PASSWORD` | 密钥密码；若生成时直接按回车，则与密钥库密码相同 |

填写 **Secrets**，而不是 Variables。工作流只在 `main` 上运行，不向 PR 提供发布密钥；密钥库恢复到 runner 临时目录，结束时清理。缺少任何一项时会提示缺少的 Secret 名称并停止构建。

### 3. 生成或发布签名 APK

第一次设置后，进入 **Actions → Signed Android Release → Run workflow**，选择 `main`：

- `publish_release` 勾选：上传 Actions artifact，并发布 `v<versionName>` GitHub Release，例如 `v1.2.0` / `rule34-Downloader-v1.2.0-release.apk`。
- `publish_release` 取消勾选：只上传签名 APK artifact，可先检查安装效果。

以后推送到 `main` 且最终提交信息包含 `[release]`，也会自动构建并发布签名 Release。`[release-debug]` 继续发布调试 prerelease；普通提交不发布 Release。

签名 Release 不覆盖已有版本，发布新版本前应同时增加 `app/build.gradle.kts` 中的 `versionCode` 和 `versionName`。只想重新打包当前版本时，取消 `publish_release` 即可。

**从调试版切换**：如果设备上已安装的 APK 使用了其他密钥，Android 会拒绝直接覆盖安装。先做好数据备份，再卸载旧版并安装新签名版；卸载会移除应用内的画师、进度和设置等数据，已保存到公共 Downloads 的文件会保留。固定签名版后续使用相同密钥和更高 `versionCode` 时可以正常覆盖升级。

本地签名构建也读取环境变量：`ANDROID_KEYSTORE_PATH`（密钥库路径，可为绝对路径或相对项目根目录）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；四项齐全后运行 `./gradlew testDebugUnitTest assembleRelease`。未设置时本地 Release 构建不签名，调试构建仍可正常运行。

配置参考 [GitHub Actions Secrets](https://docs.github.com/zh/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets) 和 [Android 应用签名](https://developer.android.com/studio/publish/app-signing?hl=zh-CN)。

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

- 添加作者阶段只处理元数据；图片在用户主动打开画师相册和预览时加载。
- 不自动猜测多个 artist tag 中哪一个才是用户想要的作者。
- 用户直接输入 artist tag 时将该 tag 视为用户明确选择，不额外要求 API 验证。
- 移除作者只停止跟踪，不删除已经保存到 Downloads 的文件。
- 自动同步依赖 WorkManager，系统可能因省电策略延后执行；15 分钟是周期任务允许的最小间隔。
- 匿名模式保留请求限速；交互验证由用户在 WebView 中完成，后台任务不会主动弹出页面。

## License

MIT，见 [LICENSE](LICENSE)。
