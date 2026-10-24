# Rule34 Downloader for Android

Android 10+ 的作者相册与原文件下载应用。2.1.0 提供 **作者 / 全部作品 / 下载** 三个入口；设置位于右上角，支持 Material You 动态配色和宽屏导航。

## 使用

1. 打开即进入作者相册，无需先配置账号。输入作者标签直接添加；帖子链接或 Post ID 自动识别作者，多作者时再选择。重复添加会打开已有相册。
2. 在设置中保存 Rule34 的 User ID 和 API Key，即可获取作品列表。保存不发起预验证请求，凭据错误由实际请求反馈。
3. 作者相册展示已发现的全部作品，已下载图片读本地，其他图片在线预览。网格和全屏预览持续分页，支持图片缩放、GIF 和系统视频播放器；预览不依赖 API 凭据状态。
4. 下载页集中显示进度、暂停和逐文件失败。失败项显示作者、文件名、Post ID、缩略图及具体原因，可单个或批量重试，点击作者失败数直接进入对应列表。
5. 自动同步、网络偏好和旧下载目录关联放在设置；普通开关即时保存。作者菜单提供更新、暂停、继续、重新扫描和移除。

文件保存在 `Download/Rule34 Downloader/<作者>/`。移除作者保留公共下载文件。已有 Pool 图集继续提供本地查看。

## 下载与恢复

- 下载只检查 HTTP 状态、HTML 响应、非空文件、已知响应长度和写入错误；未知长度允许正常完成。无须 MD5，不计算传输哈希，不读回文件，不在每次同步时重验已完成文件。
- 文件先写入未公开的 MediaStore 条目，写入成功再发布并保存 URI。暂停或失败只清理本次未完成文件；继续时从头下载中断的文件。
- 文件名使用 Post ID、可选的元数据哈希和扩展名；没有哈希时使用 `Post ID.扩展名`。元数据哈希用于命名和旧文件匹配，不代表已验证文件内容。
- 单个文件失败不阻止其他文件，也不会因连续三个文件被拒绝而停止整组。网络中断和临时错误记录到具体文件，等待退避后重试；遵守服务器的 `Retry-After`，临时错误不消耗永久错误尝试次数。
- 单文件重试只重置所选任务，不重新扫描作者或重置其他失败项；有凭据时可刷新该文件的旧地址。已有待下载队列继续处理，用户暂停意图保留。
- API 不可用或凭据失效时仍处理已有媒体地址。扫描错误与文件错误分别显示；只有完整扫描才提交水位，不把已发现文件下载完成误报为作者列表完整。
- 旧目录通过系统选择器授权，按已有 URI、Post ID 或文件名中的哈希匹配，检查可读、非空及已知长度。任意改名且无法识别的文件保持原样，不建立全量内容哈希索引。目录遍历可取消、避免循环，无固定深度或文件数量拒绝。
- 队列、错误、暂停意图和扫描检查点持久化，应用重启后恢复。后台采用每作者唯一任务、运行租约和目录锁，防止旧任务覆盖新进度。

凭据使用 Android Keystore AES-GCM 保存在设备上，API 与媒体使用独立客户端，媒体请求不携带 API Key。

## 升级与架构

保持 application ID、`rule34_downloader.db` 和数据库版本 7；支持旧版本 1–6 迁移，保留 URI、订阅、暂停状态、扫描水位和旧 Pool 顺序，不因缺少 MD5 重下完成文件。

沿用 `app / ui / domain / download / data / platform` 六模块；UI 通过领域接口分页查询作品与失败文件，下载引擎可独立运行 JVM 测试。详见 [架构说明](docs/architecture.md)。

## 构建与验证

需要 JDK 17、Android SDK 37；Gradle Wrapper、AGP 及 Kotlin 版本由仓库指定。

```bash
./gradlew :domain:test :download:test testDebugUnitTest lintDebug assembleDebug
./gradlew assembleDebugAndroidTest connectedDebugAndroidTest
```

`Android checks` 替换旧 CI：提交和 PR 自动运行 JVM / Android 单元测试、lint、Debug / Release 构建和 Android 35 模拟器回归。构建使用 JDK 17，AGP 会安装 Android SDK Platform 37.0。CI 包括：

- JVM 下载引擎与 API 协议测试：分页检查点、认证错误、HTML 拒绝、请求取消、重试与发布边界。
- Robolectric + SQLite 的真实 Room 迁移测试：旧数据库 1–6 版、旧 URI、暂停和任务模式、Pool 顺序、事务回滚、独占运行租约和过期运行写入隔离。
- Android 35 端到端：可选 API 配置、三个主要页面、UI 暂停/继续、真实 HTTP、MediaStore 发布与长度检查、扫描故障隔离、无凭据继续已有文件，重新扫描保留原 URI；作者分页预览、逐文件错误与单文件重试；原生目录选择器还覆盖应用根目录、父目录、更高祖先、目录名变体、自定义根目录、作者媒体子目录、无法识别的改名文件保留并重新下载和空目录。
- 批量端到端（PR 自动运行，也可手动启用 `bulk_restore`）：3000 个独立原文件、元数据扫描暂停、真实 `force-stop`、保留完成 URI、真实卸载重装、系统 SAF 根目录/子目录选择、空文件与缺失文件恢复。

受控 Android 测试通过 Debug instrumentation 注入本机 HTTP API 与独立 PNG，模拟原站的 `hash`、文本类型 `tag_info` 和空 JSON / XML 确认，不证明任意标签可用。正式安装没有测试站点入口，Release 只使用正式 API。

真实原站测试默认不运行。手动触发 `Android checks` 并打开 `live_api`，凭据从 Repository Secrets `RULE34_USER_ID` / `RULE34_API_KEY` 读取。协议测试覆盖认证、两页搜索、空增量、已删除帖子、作者解析和原文件 MD5。恢复集成测试使用真实 API 元数据、真实 CDN 文件、Room 和临时文件系统，并注入扫描失败及损坏副本，检查队列继续、无 API 恢复、旧文件复用和从头扫描。测试只创建临时本地资料库和文件，不修改原站账号。本地可通过相同环境变量运行：

```bash
RULE34_LIVE_TESTS=true ./gradlew :data:testDebugUnitTest --tests '*Rule34Live*Test' --rerun-tasks
```

User ID 和 API Key 应通过环境提供，不要写入命令、源码或报告。测试完成后删除临时媒体，不保存原始 API 响应或带认证参数的 URL。

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

`Signed APK` 工作流验证并构建固定签名 Release。手动运行默认仅生成 artifact，打开 `publish` 才发布；推送与应用版本一致的 `v2.1.0` 形式标签也会发布。发布前运行单元测试、Release lint 并验证 APK 签名；已有同名 Release 不会被删除或覆盖。普通 CI 编译压缩后的未签名 Release，并提供可安装的 Debug APK。

本地固定签名构建读取四项环境变量：`ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。四项齐全后 Debug 与 Release 都使用该密钥，可运行 `./gradlew assembleDebug assembleRelease`；未配置时 Debug 使用 SDK 调试密钥。签名密钥与已安装版本不一致时不能覆盖安装；卸载会清除应用数据库、凭据和目录授权，公共下载文件保留。
