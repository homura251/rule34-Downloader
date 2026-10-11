# Rule34 Downloader for Android

Android 10+ 的原文件下载与本地资料库应用。2.0 使用 Rule34 DAPI，按作者标签订阅，提供 **订阅 / 作品 / 任务 / 设置** 四个入口。Android 12+ 支持 Material You 动态配色，宽屏使用侧边导航。

## 使用

1. 在 Rule34 账户设置取得自己的 User ID 和 API Key。首次打开应用，在配置页验证并保存；也可稍后配置，先浏览已有本地作品。
2. 添加单个作者标签、单作者搜索链接、帖子链接或 Post ID。帖子输入会通过 API 的标签类型识别作者；有多个作者时自行选择。
3. 同步先分页发现元数据、保存队列，再校验旧文件和下载原文件。文件保存在 `Download/Rule34 Downloader/<作者>/`。
4. 在订阅或任务页暂停、继续、重试。作品页支持分页、筛选、全屏预览、缩放、GIF 与系统视频播放器。
5. 重装后，在设置中通过系统目录选择器关联原来的 `Rule34 Downloader` 根目录或某位作者的子目录，重新订阅同一标签后同步。应用读回校验 MD5，复用完整文件，只补下载缺失或损坏文件。

User ID / API Key 使用 Android Keystore AES-GCM 加密保存在本机。凭据缺失或失效时，联网操作挂起，已下载文件、本地浏览和暂停操作仍可使用。API Key 只发送到 API 请求，媒体请求使用独立客户端。

**2.0 已移除匿名模式、网页解析、嵌入式 WebView 和浏览器验证入口。** API 返回验证网页时，任务报告阻断，不会保存 HTML 或切换到匿名请求。HTTP 429 和临时网络错误采用退避重试并遵守 `Retry-After`。

**已有 Pool 图集仅保留本地查看。** 当前版本不新增或在线同步 Pool；迁移保留旧图集名称、已知成员顺序和本地 URI。当前实现没有依赖网页的 Pool 成员获取途径。

## 文件与恢复

- 每个原文件写入 MediaStore 的未公开条目，校验响应长度和 MD5，再读回文件验证；验证成功才清除 `IS_PENDING` 并保存完成 URI。
- 暂停以文件为单位：已完成 URI 保留，中断的文件继续时从头下载。未实现 HTTP Range 断点续传。
- 暂停意图、待下载队列、分页检查点、重试次数持久化。应用启动恢复被中断的工作，用户暂停的任务保持暂停。
- 完整分页扫描成功才提交最高 Post ID。扫描过程中出错不会把未扫描页面标记为完成。
- 同时运行最多两组任务。同一实际目录使用互斥锁，清理中断条目只涉及本安装拥有的未公开文件。
- 关联旧目录使用 SAF 读授权；更换目录不会释放以前的读授权，也不会继续已暂停任务。
- 移除订阅只移除数据库中的关联，不删除公共 Downloads 中的文件。

## 架构：方案 B

| 模块 | 职责 | 项目依赖 |
| --- | --- | --- |
| `app` | Application、Activity、组装对象与配置 | `ui`、`data`、`platform`、`domain` |
| `ui` | Compose 页面、导航、按功能拆分的 ViewModel、Paging | `domain` |
| `domain` | 模型、端口接口、输入规则、校验规则 | 无 Android 或项目依赖 |
| `download` | 元数据扫描、恢复、校验与传输状态机 | `domain`；纯 Kotlin/JVM |
| `data` | DAPI、Room、凭据存储、DataStore | `domain` |
| `platform` | WorkManager、MediaStore、SAF、媒体 HTTP、通知 | `download`、`domain` |

UI 不直接访问 Room、WorkManager 或 HTTP。下载引擎通过接口使用元数据、队列和文件适配器，能够在 JVM 上独立测试。数据库把订阅、帖子、关联、任务、本地文件和检查点分开；取消或替换的旧运行无法覆盖新运行的状态。

详细约束、迁移策略和后续扩展点见 [架构说明](docs/architecture.md)。

## 升级

同一签名、相同 application ID 和更高 versionCode 可覆盖升级。数据库仍使用 `rule34_downloader.db`，支持旧版本 1–5 直接迁移到 Room 6；不使用破坏性迁移。旧 URI、完成 MD5、作者、同步水位和暂停意图保留，旧版未校验的完成记录会重新校验。旧 SharedPreferences 下载设置迁移到 DataStore，原凭据加密存储名称与 Keystore 别名沿用。

迁移或设置读取失败时显示可重试错误页，避免将读取失败显示成空资料库。

## 构建与验证

需要 JDK 17、Android SDK 37；Gradle Wrapper、AGP 及 Kotlin 版本由仓库指定。

```bash
./gradlew :domain:test :download:test testDebugUnitTest lintDebug assembleDebug
./gradlew assembleDebugAndroidTest connectedDebugAndroidTest
```

CI 包括：

- JVM 下载引擎与 API 协议测试：分页检查点、认证错误、HTML 拒绝、请求取消、重试与发布边界。
- Robolectric + SQLite 的真实 Room 迁移测试：旧数据库 1–5 版、旧 URI、暂停、Pool 顺序、事务回滚和过期运行写入隔离。
- Android 35 端到端：API 配置门禁、四个页面、UI 暂停/继续、真实 HTTP、MediaStore 读回校验和无凭据本地浏览。
- 批量端到端：3000 个独立原文件、元数据扫描暂停、真实 `force-stop`、保留完成 URI、真实卸载重装、系统 SAF 根目录/子目录选择与坏文件修复。

受控 Android 测试通过 Debug instrumentation 注入本机 HTTP API 与独立 PNG，不证明真实原站或任意标签可用。正式安装没有测试站点入口，Release 只使用正式 API。已删除原匿名原站检查。

批量测试在带 KVM 的模拟器上运行：

```bash
bash .github/scripts/bulk-e2e.sh
```

Actions artifacts 保存 APK、测试报告、截图和逐阶段实际文件校验记录。测试中的假凭据不访问正式 API。真实 API 使用情况取决于用户自己的账号、网络与服务端响应。

## 签名

固定密钥由 Actions Secrets 提供，不能提交到仓库：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Keystore 文件的 Base64 |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore 密码 |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Key 密码 |

同一个 GitHub Organization 可将这四项设为 Organization Secrets，授权多个仓库读取；所有仓库的签名步骤沿用相同密钥和 alias。个人账户使用每个仓库的 Repository Secrets。备份原始 Keystore 和密码，后续更新沿用同一密钥。

`Signed Android Release` 工作流验证并构建固定签名 Release。手动运行时可关闭 `publish_release`，仅生成 artifact；在 main 上提交信息含 `[release]` 时会发布。普通 CI 用临时密钥验证压缩后的 Release 构建，该临时 Release 不会上传或发布。

本地固定签名构建读取四项环境变量：`ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。四项齐全后运行 `./gradlew assembleRelease`。签名密钥与已安装版本不一致时不能覆盖安装；卸载会清除应用数据库、凭据和目录授权，公共下载文件保留。
