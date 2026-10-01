# Rule34 Downloader for Android

一个原生 Android / Material 3 应用：按 **artist tag** 或 **Pool 图集** 建立独立目录，下载 Rule34.xxx 原始文件，支持后续同步、相册预览和暂停/继续。

> 本项目无广告、付费墙或共享 API Key。图片内容来自用户主动添加的 artist tag，并在用户打开画师相册时加载；请自行遵守所在地法律、Rule34.xxx 的服务条款与站点限制。

## 功能

- 添加作者或图集支持以下输入：
  - 直接输入单个 artist tag，例如 `savvyraexo`
  - 粘贴帖子 URL / Post ID，自动识别页面中的 artist tag
  - 粘贴单作者搜索 URL，例如 `https://rule34.xxx/index.php?page=post&s=list&tags=savvyraexo`
  - 粘贴 Pool 图集链接 `https://rule34.xxx/index.php?page=pool&s=show&id=<ID>`，或输入 `pool:<ID>`
- **API 凭据可选**：
  - 已配置 User ID + API Key：优先使用 DAPI，速度更快、批量同步更稳定
  - 未配置：自动使用公开网页匿名解析，仍可发现帖子并取得 “Original image” 原文件链接
- 首次同步下载该作者全部作品；后续保存最高 post ID，只补充新增帖子。
- 每位作者独立保存到 `Download/Rule34 Downloader/<artist>/`。
- **Pool 图集**：跨页发现全部成员，保存到 `Download/Rule34 Downloader/pool_<ID>/`，相册和滑动预览按原站图集顺序展示。后续重新检查成员和顺序，只给未记录的帖子补取元数据，不会因帖子 ID 较旧而漏掉后来加入的图片。
- 同步支持暂停/继续；暂停状态持久保存，自动同步跳过暂停的画师。已完成的文件保留，正在传输的文件在继续后重新下载。
- 重装后可在设置中“关联旧下载目录”，重新添加同一画师并同步时校验、复用已有文件，恢复本地预览和完成状态。
- **画师相册**：点击画师卡片中的“查看作品”，按帖子 ID 从新到旧显示缩略图和下载状态，支持分页加载。
- **全屏预览**：点图片后可左右滑动、双指缩放和双击放大/复位，支持常见图片与 GIF；视频可打开原帖或使用系统播放器查看已下载文件。
- 图片优先读取已下载的 MediaStore 文件，可离线浏览；本地文件不存在时回退到在线图片。在线缩略图和大图复用 Cloudflare 会话。
- Android 10+ 使用 MediaStore，不申请读/写外部存储权限。
- WorkManager 支持手动同步和可选的 15 分钟 / 1 小时 / 6 小时 / 24 小时后台检查。
- Material Design 3 / Material You：Android 12+ 动态配色、状态切换动画、文件级进度、Snackbar 与完成通知。
- API User ID / API Key 使用 Android Keystore AES-GCM 加密后仅保存在本机。
- 匿名网页使用 WebView 的 Chromium 网络会话读取，验证页和下载共享与当前引擎匹配的浏览器 User-Agent、Client Hints 与 Cookie。原文件的普通 HTTP 传输被挑战拦截时，可切换到同源 WebView 流式传输。不增加 Android 权限。

## 使用

1. 打开 App，点“添加作者/图集”。
2. 输入 artist tag，或者粘贴帖子、作者搜索、Pool 图集链接；图集也可输入 `pool:<ID>`。
3. 确认作者或图集标题后开始同步。
4. 不配置 API 也能使用；如果希望大批量同步更快、更稳定，可在设置中填写自己的 Rule34 **User ID** 与 **API Key**。
5. 以后点“同步”，或在设置中开启自动增量同步。
6. 点画师卡片的“查看作品”进入相册。相册展示同步已发现的帖子，未下载的图片也可在线预览；首次添加画师时先等待同步发现作品。
7. 同步中点击“暂停”，待显示“已暂停”后点“继续”；相册工具栏也有暂停/继续按钮。

### 重装后复用旧图片

卸载会清除应用数据库和目录授权；公共 Downloads 中的文件会保留，但重装后的应用不能直接读取原安装留下的所有文件。

1. 在设置中点“关联旧下载目录”，选择 `Download/Rule34 Downloader` 子文件夹，也可以选择单个画师文件夹。Android 11+ 不允许授权整个 Downloads 根目录，应进入子文件夹再确认。
2. 重新添加原来的画师 tag 或同一个 Pool 图集，并同步。应用读取帖子元数据后，按对应目录、帖子 ID、文件类型和原文件 MD5 校验关联旧文件，恢复“已下载”和本地预览，避免重复下载。图集目录使用稳定的 `pool_<ID>` 名称，标题变化不会改变保存路径。
3. 空文件、校验不匹配的文件不会视作完成。缺失文件仍正常下载；目录授权失效时请重新选择目录。

这个流程恢复与重新添加的画师匹配的本地文件，不恢复被卸载清除的账号凭据或同步历史。关联操作只请求所选目录的读取授权，不增加照片或全盘存储权限。暂停为文件级继续，不使用 HTTP Range 续传当前半个文件。

### 多图片与漫画帖子

[原站帖子帮助](https://rule34.xxx/index.php?page=help&topic=post)明确说明一个帖子代表一个文件；[Pool 帮助](https://rule34.xxx/index.php?page=help&topic=pool)说明图集由可排序的多个帖子组成，适合漫画、CG 套图等系列。

应用适配这个实际结构：添加 Pool 链接后，读取图集标题和各页的成员，按页面顺序下载、预览，不按帖子 ID 重排，也不受画师标签限制。分页跟随原站链接；对于没有分页链接的满页，兼容 Rule34 图集每页 45 项的偏移。重复分页会报错，避免死循环或错误宣告完成。

图集成员只有在全部分页读取成功后才替换；暂停或网络失败不会把未读完的结果当作完整图集。移出图集的帖子从图集预览和下载队列中隐藏，保留其下载记录及磁盘文件；重新加入时可复用。图集同步沿用 Cloudflare 会话、暂停/继续、旧文件校验和自动同步。图集成员通过网页发现；配置 API 后，未知帖子的原文件元数据优先由 API 读取。

画师同步仍逐帖处理匹配标签的作品；GIF 多帧和视频保存为一个原文件。没有把不存在的“单帖多附件”字段加入模型，也不自动解压 ZIP。

## 文件完整性与任务恢复

- 新下载必须非空、匹配声明长度（若可用）并通过原文件 MD5；关闭输出后重新读取保存的文件校验，全部通过才公开 MediaStore 文件并标记已下载。
- MD5 可以来自帖子元数据或原文件 URL 的精确 32 位哈希文件名。没有可靠哈希的文件会报告错误，不能凭非空内容认定完成或复用。
- 升级到 1.5.0 后，旧完成记录会重新待校验，保留文件和 URI；下一次同步通过校验后恢复完成。已经验证的记录每次同步检查可读性与长度，失效 URI 会重新关联或下载。
- 暂停中止网络请求和 WebView 流式传输，未完成的新文件被删除；继续会重新下载该文件。完成的旧文件保留。
- 同一画师/图集的任务串行执行，包括取消后的清理。下次同步清理该目录中本应用所有的未公开 MediaStore 文件，处理进程被杀留下的临时文件。
- 更换旧目录会刷新扫描索引并重启正在同步的任务。保留旧目录的只读授权，以免破坏仍引用旧 URI 的本地预览；已经暂停的任务保持暂停。

## 匿名模式与 API 模式

Rule34 的 DAPI 当前要求 `user_id` 与 `api_key`。App 在未配置凭据时不会调用需要认证的 DAPI，而是解析公开网页：

1. 作者搜索页读取每页 Post ID（当前网页每页 42 项）。
2. 逐条读取帖子详情页。
3. 从详情页 Options 的 “Original image” 获取原文件 URL，兼容没有 `.link-list` 的旧布局、嵌套文本和相对地址；也可读取帖子原图或视频 source。拒绝 sample、缩略图、视频封面、评论中的链接和站外地址。
4. 从 `#tag-sidebar .tag-type-artist` 识别帖子中的 artist tag。
5. 匿名元数据通过 WebView 读取渲染后的实际文档，并保留分页、详情读取的限速；后台页面最多等待 30 秒。1.5.1 修复页面刚出现导航/标签栏就提前读取的问题：等待主文档解析完成和对应的作品内容，详情页需读到原文件链接，支持原链接延迟渲染。页面已打开却缺少作品信息时返回带链接的页面错误，不再误报 Cloudflare 验证。原文件的 HTTP 挑战重试仍限制为一次，必要时切换到 WebView 流式传输，避免 Cookie 跨客户端后再次被拦截。
6. 需要点击的验证：打开设置 → “网页验证”，确认实际请求页面可读取后自动返回，再重新同步。保留已有 Cookie，不要求 Cookie 更新，也不以 Cookie 变化宣告成功。允许 Cloudflare 子页面和验证资源；读取元数据时跳过帖子媒体资源。上次请求地址保存在本机，重启后仍可验证对应地址。
7. 1.5.2 按 Mihon 的方式从当前 WebView 引擎推导浏览器 User-Agent，并在系统支持时同步 Client Hints 的品牌和版本。后台 WebView 配置实际视口；文档读取不依赖站点的 `JSON.stringify`。主文档中的被动 Cloudflare 检测脚本不会把已正常显示的作品误判成挑战页。
8. 页面读取成功后保留同一个 WebView，空闲 30 秒再销毁，使延迟执行的检测脚本有机会完成。手动验证完成后将实际 WebView 交给后台读取器，同时缓存该已验证文档最多 2 分钟、供匹配请求使用一次；其他帖子、画师和分页不会误用缓存。共享会话等待和读取均支持暂停。
9. 读取失败会区分验证页、文档读取失败、重定向、未完成加载和缺少作品信息。设置 → “网页验证” → “复制诊断”可取得上次后台读取和当前验证的版本、页面地址、标题、状态及视口，不包含 Cookie、API 密钥或页面正文。
10. 1.5.3 等待主页面提交后再读取 DOM，空文档仍返回有效快照，不再误报“页面数据不完整”。“重新加载”始终打开请求地址，避免反复刷新 `about:blank`。连接和 TLS 错误保留在界面与诊断中，不会被等待作品的轮询提示覆盖；诊断同时记录浏览器地址、已提交文档地址及加载进度。TLS 证书错误保持取消加载，不忽略证书验证。

首次同步作品很多的作者会明显慢于 API 模式。验证仍受站点策略和系统 WebView 版本影响；验证超时会明确提示，不会无限重试或把验证网页保存为原文件。

原文件的 HTTP 拦截流程参考 [Tachiyomi CloudflareInterceptor](https://github.com/izfaruqi/tachiyomi/blob/master/app/src/main/java/eu/kanade/tachiyomi/network/interceptor/CloudflareInterceptor.kt) 的 WebView / Cookie / 重试流程；同时使用 `cf-mitigated: challenge` 和旧验证页特征识别挑战，避免把普通限流当作验证。
浏览器身份处理参考 [Mihon WebViewUtil](https://github.com/mihonapp/mihon/blob/master/core/common/src/main/kotlin/eu/kanade/tachiyomi/util/system/WebViewUtil.kt)。

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

推送到 `main` 且提交信息包含 `[release-debug]` 时，构建与必需的 Android 回归测试全部通过后自动发布/刷新对应版本的 GitHub prerelease。版本号读取 `app/build.gradle.kts` 的 `versionName`：当前为 `debug-v1.5.3`，附件为 `rule34-Downloader-v1.5.3-debug.apk`。其他版本的 Release 保留。

普通 main 提交和 PR 会运行 JVM 单元测试及 Android 35 上的真实 WebView、流式传输、暂停、MediaStore 和数据库升级回归测试，构建并上传调试 APK artifact，并使用临时测试密钥验证 Release 构建与签名；临时密钥签出的 Release APK 不会上传或发布。发布调试版本时，应在最终合入 main 的提交标题或内容中保留 `[release-debug]`。

### Android CLI 与端到端测试

CI 安装 Google 官方 Android CLI，并在 Android 35 模拟器上操作真实应用界面：输入 Post ID → 识别作者 → 添加并下载 → 暂停/继续 → 校验已发布文件的 MD5、长度和 `IS_PENDING` → 打开相册与全屏预览 → 再次同步确认复用原 URI、没有再次请求原文件。这个可重复的测试使用受控 HTTP 站点，不代表真实原站验证成功。验证界面另测 `about:blank` 的重新加载和连接错误在轮询后仍然可见。

- `android-regression-results`：测试报告、应用流程截图、文件校验结果，以及 Android CLI 的屏幕截图、布局和 WebView 版本。
- `e2e-apks`：同一次构建、签名匹配的 App APK 与 instrumentation APK，可安装到设备重跑。
- `live-origin-results`：独立使用真实匿名网络请求反馈中的帖子 `18905312`，只有成功解析并下载、读回校验和发布后才算通过。Cloudflare、网络或页面解析失败会保留失败测试与诊断；该外部检查允许失败，不阻止受控回归测试和构建，但不能据此宣称匿名原站下载成功。

有 SDK 与设备的环境可运行：

```bash
./gradlew connectedDebugAndroidTest
./gradlew -PliveSiteTest=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.homura251.rule34downloader.LiveSiteEndToEndTest \
  -Pandroid.testInstrumentationRunnerArguments.livePostId=18905312 connectedDebugAndroidTest
```

真实原站检查只保存验证结果，不保留测试下载的文件。测试站点注入只在 Debug 构建内部供 instrumentation 使用，Release 禁止启用，应用设置不提供该入口。

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
