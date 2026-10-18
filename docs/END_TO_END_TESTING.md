# Android 端到端回归

主要用户操作由 Maestro 驱动；文件内容、队列与扫描检查点由 Android instrumentation 和真实 API 回归验证。测试报告必须同时保留 UI 结果和文件断言。

| 场景 | 验证方式 |
| --- | --- |
| 首次启动、跳过配置、四个页面、无凭据不扫描 | Maestro `01-setup-offline` |
| 错误 key、保存凭据、重启后保留、留空重新验证 | Maestro `01`、`05` |
| 帖子链接、多作者选择、直接标签、重复订阅、Pool 提示、输入法不改写链接或密钥 | Maestro `02-subscriptions-gallery`；作者输入使用 URI 键盘，密钥使用密码键盘，两者关闭自动纠正 |
| 图片、GIF、视频原文件、列表筛选、预览翻页、离线浏览 | Maestro `02`；结束后通过 adb 读回文件并核对 MD5 |
| 新作品增量、重复同步、从头扫描、移除重加复用 | Maestro `02`；`BusinessFlowsEndToEndTest` 检查原 URI、请求次数及水位 |
| 暂停全部、暂停任务筛选、重启保留暂停、继续任务 | Maestro `03-pause-recovery`；`ApiAppEndToEndTest` |
| 网络限制和自动同步设置保存 | Maestro `03`；`BusinessFlowsEndToEndTest` 检查已有重试任务更新约束且保留期限、运行中的传输安全切换网络限制 |
| 原生目录选择、取消选择、无 API 先恢复订阅、验证后自动复用 | Maestro `04-native-folder`；`FolderLinkRecoveryTest` |
| 空作者子目录、空格目录名、无需输入标签、缺少凭据时先恢复、验证后自动下载、重启和重复关联 | Maestro `empty-authors`；`FolderLayoutEndToEndTest.emptyAuthorDirectoriesRestoreWithoutTypingTagsAndSyncAfterApiVerification`，实际零文件目录及 MD5 读回 |
| 移除作者后重启保持移除、新增空目录自动导入、明确重新关联恢复已移除作者且不重下 | Maestro `empty-authors` 的移除/重启/重新关联；设备测试核对新增目录、原 URI、九次原图传输和暂停状态 |
| 已识别作者和待完成恢复持久化、用户取消待恢复作者、保留下载设置时不覆盖目录恢复状态 | `FolderRestorePlanTest`、`FolderLayoutEndToEndTest`；DataStore 更新在单次 `edit` 中读取并修改当前值 |
| 旧版已保存目录授权但漏掉作者，升级后启动自动补恢复；已完成订阅和显式暂停保留 | `FolderLayoutEndToEndTest` 的启动恢复断言；普通 Release APK 覆盖安装回归 |
| 祖先目录、作者目录、images/videos/年份子目录、hash 文件名、改名文件、空下载根目录 | `FolderLayoutEndToEndTest` 的 9 种实际 SAF 布局；有作者目录名时改名文件自动恢复 |
| 旧目录失效、取消关联、保留订阅并恢复下载 | `BusinessFlowsEndToEndTest` 和 Maestro `04` |
| 单个原文件 403、其他文件继续、无凭据下载已发现文件 | Maestro `05-api-media-errors`；`RecoveryUiEndToEndTest` |
| API 429、凭据失效、重新验证后恢复且完整文件不重下 | Maestro `05` |
| 下载中强杀进程后自动恢复 | Maestro `06-process-death`；bulk 的 crash/recover 阶段 |
| 实际关闭网络、排队期间重启、恢复网络后自动下载 | Maestro `network-prepare`、`network-queued`、`network-resumed` 与 adb 网络控制 |
| 截断、空文件、错误内容、HTML 响应、取消时的半成品 | `DownloadReliabilityTest`、bulk 恢复与 MD5 读回断言 |
| 3000 文件、跨进程保留完成 URI、真正卸载重装、旧文件与坏文件混合恢复 | `bulk-e2e.sh`；2993 份有效文件复用、7 份故意破坏/删除文件补下载 |
| 当前服务的分页、空 JSON/XML、作者解析、原文件下载、故障恢复 | `Rule34LiveApiTest`、`Rule34LiveRecoveryTest`，独立使用真实 API/CDN |

## 在模拟器上运行

```bash
bash .github/scripts/controlled-e2e.sh
bash .github/scripts/bulk-e2e.sh
# 安装 Maestro CLI 2.11.0 并加入 PATH 后：
bash .github/scripts/maestro-e2e.sh
```

Maestro 使用 `com.homura251.rule34downloader.e2e` 独立 APK。它与发行版共用业务代码、WorkManager、Room、DataStore、MediaStore、SAF 和 Compose 界面；专用 source set 将网络接到通过 `adb reverse` 暴露的本机 HTTP 服务。该工厂不编入普通 debug/release APK，普通版本始终使用真实 API。

服务提供真实可解码的 PNG/GIF/MP4，模拟 401、429、403、空响应和慢速传输，并记录原文件请求次数。测试不用真实 key，也不改写设备中的真实用户数据；运行脚本要求使用一次性模拟器。Maestro 覆盖 Android 10 / 16，instrumentation 和 3000 文件恢复覆盖 Android 15。

脚本逐场景启动新的 Maestro UiAutomation 连接，避免 Android 10 上长连接死亡后污染后续场景；业务断言失败直接让 CI 失败。场景 JUnit 汇总为 `results.xml`，单场景报告也会保留。

输出位于 `app/build/maestro-evidence/`、`app/build/e2e-evidence/` 和 `app/build/bulk-e2e-evidence/`，CI 在失败时也上传截图、日志和 JUnit 报告。Maestro 结束后还会从设备读回所有场景留下的原文件，检查 MD5 和重复文件数量。

真实 API 测试通过环境变量 `RULE34_LIVE_TESTS=true`、`RULE34_USER_ID`、`RULE34_API_KEY` 显式启用。凭据不写入仓库、测试 APK 或公开 CI 配置。真实 API/CDN 验证和受控 Android 自动化分别报告，避免把受控服务的通过结果当作生产服务上的完整手机测试。
