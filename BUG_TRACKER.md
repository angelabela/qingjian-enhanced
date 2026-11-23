# 2026-11-23：0.3.19-dev 日志驱动修复

## BJ-053：输入法服务初始化阶段 Context 空指针
- 日志证据：AndroidRuntime 多次记录 `Unable to create service QingjianInputMethodService`，根因为 `Context.getMainLooper()` 对空 Context 调用。
- 根因：0.3.17 新增的 Handler 在 Kotlin 成员初始化时访问 Service 的 mainLooper；此时 Service Context 尚未附加。
- 处理：紧急回退 0.3.18 已移除该过早成员初始化；本轮保留此安全基线，禁止在 Service 字段初始化阶段访问 Context。
- 验证：0.3.19 构建后需在设备确认设置与键盘可用；设置 Activity 闪退仍需独立堆栈。

## BJ-054：Android 16 扫码悬浮窗创建时崩溃
- 日志证据：`UnsupportedOperationException: Tried to obtain display from a Context not associated with one`，发生在 ScanOverlayService 创建 overlay WindowContext 阶段。
- 根因：Service Context 未关联 Display，直接调用 createWindowContext。
- 修复：通过 DisplayManager 获取默认 Display，先 createDisplayContext，再 createWindowContext。
- 验证：待 Android 16 实机测试扫码启动、相机预览和停止流程。

## BJ-055：视频背景刷新时 Surface 被反复销毁
- 源码风险：每次刷新动态背景都无条件移除并重新创建 TextureView，可能销毁 Surface、重启播放器并造成背景闪断。
- 修复：URI 不变时保留 TextureView/Surface，透明度和模糊效果原位更新；仅 URI 变化时替换视图。
- 验证：待设备测试设置刷新、键盘重开及视频铺满整个面板。

## BJ-056：设置 Activity 闪退原因待独立确认
- 当前证据：已上传 ime 日志明确包含 IME Service 初始化 NPE，但未取得足以定位设置 Activity 的完整 FATAL EXCEPTION/Activity 堆栈。
- 处理：不把 Service NPE 直接当成设置 Activity 闪退根因；下一轮需补充应用启动异常和 AndroidRuntime 堆栈。
- 验证：待 0.3.19 实机确认设置主页及各设置子页。

---

# 2026-11-23：0.3.17-dev 日志驱动修复

## BJ-049：Android 16 扫码悬浮窗创建时崩溃
- 日志堆栈指向 ScanOverlayService.onCreate 第 186 行：Service Context 未关联 Display，createWindowContext 抛 UnsupportedOperationException。
- 修复：先从 DisplayManager 获取默认 Display，创建 Display Context，再创建 TYPE_APPLICATION_OVERLAY WindowContext。
- 验证：待 Android 16 设备实测。

## BJ-050：视频背景在面板显示过程中消失/不完整
- 日志显示视频层最初成功播放（1080×836），随后记录 video=0×0；该值可能表示视图对象为空，不能仅凭该尺寸认定 TextureView 被测量为零。
- 源码风险：每次 refreshDynamicBackground 都先移除 TextureView，再无条件创建新对象，可能释放 Surface 并造成闪断。
- 修复：URI 不变时保留 TextureView/Surface，只原位更新透明度、模糊和亮度遮罩；URI 改变才替换视图；视频层按面板精确宽度测量。
- 验证：待设备确认视频完整覆盖整个输入法面板且设置刷新时不中断。

## BJ-051：剪贴板与验证码提示重复消费
- 日志显示同一 timestamp 的 primaryClip 在短时间内多次 selected；生命周期、剪贴板监听和验证码路径存在独立重试入口，preferredCode 曾绕过普通哈希去重。
- 修复：合并为单一可取消重试队列；普通剪贴板与短信验证码统一按 SHA-256 + 剪贴板 timestamp 去重。再次复制相同文字且 timestamp 更新时仍可提示，不清空系统剪贴板。
- 验证：待设备测试键盘重开、重复回调、同文再次复制及短信验证码。

## BJ-052：扫码前缀输入框首次点击未唤起软键盘
- 日志显示 EditText 已有 View 焦点，但悬浮窗口没有 Window 焦点，showSoftInput 返回 false；再次点击后窗口才进入可聚焦状态。
- 修复：增加 requestFocusFromTouch，延长窗口焦点和 IME 请求等待上限。
- 验证：待 Android 16 设备首次点击输入及关闭编辑态后的宿主输入法行为。

---

# 2026-11-23：0.3.16-dev 修复记录

## BJ-046：打开悬浮扫码导致输入法/程序崩溃

- 发现版本：0.3.15-dev（GitHub Actions #545）
- 用户复现：打开扫码功能后程序崩溃。
- 日志证据：本次上传日志 ZIP 的 11 月 23 日日志未包含扫码启动事件或 AndroidRuntime 的 FATAL EXCEPTION 堆栈，因此无法从该文件确认单一崩溃异常。
- 源码风险：545 版对 startForeground 的异常做了捕获，但 createWindowContext、识别器初始化、悬浮窗创建以及 CameraX future.get()/bindToLifecycle() 仍存在未捕获异常路径。
- 本轮处理：对服务初始化阶段和 CameraX 主线程绑定回调增加阶段标记、完整堆栈日志和安全退出；避免启动失败异常传播导致输入法进程崩溃。
- 验证：待 Actions 构建及用户设备打开扫码实测。
- 当前状态：**修复待用户验证**；真实崩溃堆栈仍需下一版日志确认。

## BJ-047：视频背景内容被裁切，未完整显示

- 发现版本：0.3.15-dev（GitHub Actions #545）
- 日志证据：Stack/main/TextureView 均为 1080×836，视频源为 1920×1080；视图本身已覆盖测量区域。
- 根因：旧变换使用 max(sx, sy) 等比例缩放并居中裁切，16:9 视频映射到更接近 1.29:1 的面板时会丢失左右画面。
- 本轮处理：改为 Matrix.ScaleToFit.FILL，分别按面板宽高缩放，完整显示视频源画面并铺满面板，不裁切、不留黑边；代价是视频宽高比可能随面板比例变化。
- 验证：待 Actions 构建及设备画面验收。
- 当前状态：**修复待用户验证**。

## BJ-048：同一剪贴板内容在重新打开键盘后重复提示

- 发现版本：0.3.15-dev（GitHub Actions #545）
- 用户期望：相同内容只提示一次，仍保留在系统剪贴板；重新打开键盘不重复显示。
- 根因：旧去重条件要求 CandidateBarView 的提示层仍处于显示状态；5 秒提示自动消失或键盘重建后条件失效。
- 本轮处理：改为仅按内容 SHA-256 与持久化 lastHash 去重，不再依赖提示层可见状态；新内容变化时正常提示。系统剪贴板不执行清空、删除或覆盖操作；短信验证码广播仍按独立事件处理。
- 验证：待 Actions 构建及设备实测（包含关闭/重开键盘和系统剪贴板保留检查）。
- 当前状态：**修复待用户验证**。

# 2026-11-23 最新日志复核与 0.3.15 修复

- 剪贴板：日志显示 primaryClip 可读取且同一内容曾成功调用显示；但通知 5 秒自动消失后，持久化的 lastHash 仍阻止相同内容再次显示。0.3.15 改为仅在同一提示仍显示时去重，提示消失后再次打开键盘可重新显示。
- 扫码焦点：前缀点击时悬浮窗口曾未获得窗口焦点；0.3.15 使用 Android 11+ WindowContext 创建悬浮界面，并在焦点切换后主动请求根节点与编辑框焦点，再请求 IME。
- 系统异常：日志栈显示 ColorOS 浮动工具栏从服务 Context 取 Display 失败，触发 `UnsupportedOperationException`；该异常路径与悬浮 EditText 的 Context 关联方式吻合，改用 WindowContext 处理。
- 视频背景：播放器成功 prepared，TextureView 为 1080×836、视频源 1920×1080，未见播放器错误；0.3.15 增加 Stack/main/texture 实际测量尺寸日志。当前证据不足以断言完整面板高度，待新日志确认是否还有底部/顶部未覆盖。
- 扫码标题栏：悬浮服务标题栏已是不透明主题色、预览区 topMargin 与标题栏实际高度同步；Activity 扫码页标题栏也改为不透明色。
- 短信验证码：本次日志没有 `SMS_CODE_DIAG`，未发生可确认的短信接收事件，短信链路仍待真实短信验收。
- 日志轮转：`jiff::Zoned::now().date()` 按设备本地日期命名，保留 30 天；无需再改。
- 本轮源码版本 `0.3.15-dev` / versionCode 315；待 CI 和用户设备验收。

# 2026-11-23 最新日志复核（上传包）

- 日志包共 7 个日文件；包含 `ime.2026-11-23.log`（483 行），导出清单标记 `timezone=Asia/Shanghai`，最新记录时间为 UTC 2026-11-22T18:14:31.992Z（北京时间 11 月 23 日 02:14:31）。
- `logging.rs` 已使用 `jiff::Zoned::now().date()` 按设备本地日期滚动，`KEEP_DAYS=30`；因此本次日志文件命名正确，UTC 行时间与本地文件名跨日是预期现象，不需要重复修改轮转逻辑。
- 剪贴板：系统 primaryClip 可读，单条文本长度 12，来源明确为 `system_primary_clip`；候选栏多次处于 attached=true、shown=false，之后有两次记录 `displayed`。当前缺陷点是候选栏显示时机与提示重试脱节，而不是剪贴板读取失败。
- 扫码前缀：日志先出现 `windowFocusable=false`、`windowFocused=false`、`IME request accepted=false`，之后第二次点击才变成 `windowFocusable=true`。根因是窗口焦点切换异步，旧代码在窗口真正获得焦点前立即请求软键盘。
- 视频背景：日志确认 TextureView 实际大小为 1080×836，视频源为 1920×1080，播放器成功 prepared；没有播放器错误。当前 Stack 视频层只按主内容高度测量，而 root 在底部另有导航栏 inset，视频未覆盖该预留区域。
- 本次源码处理：候选栏显示后有限重试、扫码前缀等待窗口焦点并重试、视频背景延伸覆盖底部 inset；扫码标题栏使用不透明主题表面色并同步精确高度。
- 短信验证码：本次日志没有 `SMS_CODE_DIAG` 记录，不能据此判定短信接收/提取链路正常或异常；需要收到真实短信验证码时再验证。
- 当前状态：源码修复已提交待 CI 构建；设备端仍需安装验收，不能把源码改动或 CI 成功当作已在手机上解决。

---

# 青简输入法问题追踪台账

## BJ-041：视频背景未铺满输入法面板

- 首次记录：2026-11-23（用户反馈 #528 安装测试后视频只覆盖面板左侧）
- 影响模块：`service/InputPanel.kt`、`service/DynamicVideoBackgroundView.kt`
- 期望：视频背景覆盖候选栏、键盘和底部操作区域的完整面板宽度，保持比例、不拉伸。
- 本轮处理：Stack 测量视频背景层时强制使用与主内容一致的 EXACTLY 宽度，避免 TextureView 在宽度约束下采用纹理固有尺寸。
- 当前状态：**已修复待验证**。修复代码进入 #538（APP 0.3.12-dev，源码计算 versionCode=312），固定签名构建与 v2 签名验证通过；尚无用户设备实测确认。

## BJ-042：扫码标题栏透明且预览区存在留白

- 首次记录：2026-11-23（用户反馈 #528 安装测试后标题栏仍有透明感，且与相机预览之间有空白）
- 影响模块：`scan/ScanOverlayService.kt`
- 期望：标题栏为不透明主题底色；标题栏底边与相机预览区域直接衔接，不留缝。
- 本轮处理：标题栏渐变底色改为 alpha=255 的不透明主题融合色；新增标题栏布局变化监听，按实际高度同步预览区 topMargin 和窗口总高度，确保两者边界精确相接。
- 当前状态：**本轮修复待构建与设备验证**。

## BJ-043：扫码前缀输入框需多次点击才能输入

- 首次记录：2026-11-23（用户反馈 #528 安装测试）
- 影响模块：`scan/ScanOverlayService.kt`、`service/QingjianInputMethodService.kt`
- 期望：首次点击前缀框即可获得焦点并弹出输入法；输入过程中扫码窗口不应意外关闭或导致键盘消失。
- 日志分析：现有代码在调用 `showSoftInput()` 前就把请求标记为已发出，未检查系统是否接受请求；窗口焦点切换又是异步的。上传日志未包含每次前缀点击、窗口焦点和 IME 请求结果，因此尚不能仅凭旧日志确认唯一根因。
- 本轮处理：本轮增加前缀点击、窗口可焦点状态、IME 请求是否被系统接受及 IME active 状态诊断；先用新日志确定异常发生在窗口焦点、输入框焦点还是软键盘阶段。
- 当前状态：**待新日志分析**。


> 状态必须依据用户实测和当前源码更新。CI 通过不代表设备问题已解决。新增问题时分配稳定编号，不因工作流编号变化而改号。

## BJ-001：扫码悬浮窗口标题栏主题色

- 首次记录：2026-11-22（用户反馈 484 号安装测试后仍存在）
- 影响模块：`apps/android/app/src/main/kotlin/app/qingjian/inputmethod/scan/ScanOverlayService.kt`
- 期望：扫码窗口标题栏颜色、描边与当前主题色协调，并在切换主题后正确更新。
- 历史处理：486 号修正标题栏描边绘制位置，并增强主题色混入程度。
- 当前状态：**待用户验证**。此前用户报告 484 仍有问题；486 修复尚无新的设备实测结论。
- 再现时检查：主题 Palette 的来源与刷新时机、标题栏背景 drawable 是否被后续重设、服务重建/主题切换时是否更新、最终显示的 View 是否就是被修改的 View。
- 禁止：未检查 486 当前实现前，不要重复只调颜色 alpha 或边框颜色。

## BJ-002：验证码区域未显示系统剪贴板最新内容

- 首次记录：2026-11-22（用户反馈 484 号安装测试后仍存在）
- 影响模块：输入法服务剪贴板选择逻辑、`clipboard/ClipboardWatcher.kt`、验证码面板。
- 期望：系统剪贴板最新复制内容优先显示；同时保留输入法历史记录和短信验证码逻辑，不被旧历史覆盖。
- 历史处理：486 号调整显示优先级（短信验证码、系统 primaryClip、输入法历史），并改用文本内容指纹去重，兼容部分 ROM 时间戳不变化的情况。
- 当前状态：**待用户验证**。486 已交付，尚无用户对该项的新测试结论。
- 再现时检查：系统剪贴板监听注册/权限与生命周期、primaryClip 读取时机、剪贴板面板数据源、UI 刷新通知、去重缓存是否误吞新内容。
- 禁止：不能只改列表排序来掩盖系统剪贴板监听没有收到新内容的问题。

## BJ-003：翻译面板打开后动态光效/视频背景丢失

- 首次记录：2026-11-22（用户反馈 484 号安装测试后仍存在）
- 影响模块：`translate/TranslateBar.kt`、面板背景容器与动态背景刷新逻辑。
- 期望：打开翻译面板后继续显示当前设置的动态光效或视频背景，不退回默认静态配色。
- 历史处理：486 号打开翻译前刷新动态背景，并移除翻译条自身覆盖底层背景的绘制。
- 当前状态：**待用户验证**。486 已交付，尚无用户对该项的新测试结论。
- 再现时检查：背景宿主层级、动态背景 Activity/View 生命周期、翻译面板是否重建 root、是否有不透明背景遮挡、刷新调用是否在正确生命周期执行。
- 禁止：不要仅把 TranslateBar 背景设透明就认为动态背景已完整恢复，必须检查视频/光效实际渲染链路。

## 本轮功能验收：492 号测试包（APP 0.3.5-dev / versionCode 30500）

以下为用户在 486 测试后提出的新功能需求。本轮代码已进入签名构建并通过 CI 签名验证；**四项均待用户安装实测，不因 CI 成功标记为设备端已解决**。

- SCAN-DRAG：扫码浮窗标题栏任意区域均可按住拖动，标题栏内按钮/控件短按行为应保留。
- SCAN-WIFI-URL：识别 Wi-Fi 或网址后显示确认提示，5 秒无操作自动关闭；Wi-Fi 接入受 Android 系统授权/确认机制约束，网址应打开浏览器。
- CLIPBOARD-ACTIONS：剪贴板 Wi-Fi 条目显示加入 Wi-Fi 图标、网址条目显示访问图标；普通文本隐藏图标；移除条目下方删除图标，保留左右滑动删除。
- KEY-EFFECTS：常用功能面板独立「按键效果」入口；按键透明度、磨砂效果集中于该页；增加跑马灯开关及 A-Z 字母按键跑马灯。

验收时逐项记录通过/失败和复现步骤；未通过时先检查 492 对应提交与实际调用链，避免重复做表面修补。

## 本轮修复验收：516 号测试包（APP 0.3.8-dev / versionCode 30308）

工作流 #516（run ID `45033003050`，提交 `504187b0268c984a0a84d886e05776e6d02dfd56`）构建成功；固定签名验证通过（APK Signature Scheme v2，证书 CN=Qingjian，证书 SHA-256：`10be9cc45f931581203433b7c1095ca9689bb643463821ef9b108314aa958b72`）。以下仅表示代码已进入签名 APK，**全部待用户安装实测**，不得视为设备端已解决。

### BJ-004：扫码前缀框第二次点击闪退 / 标题栏与相机预览错位

- 本轮修复：将编辑态激活放到单击回调，嵌套 post 使用明确标签，减少重复焦点/IME 请求；窗口布局更新后重新按标题栏屏幕坐标对齐相机预览。
- 当前状态：**已修复待验证**。
- 用户验收：连续点击前缀输入框、打开/关闭软键盘；拖动标题栏至屏幕顶部并继续向上拖，确认预览区域不会与标题栏错位或继续越界。

### BJ-005：按键边缘发光 / 立体效果开关不即时生效

- 本轮修复：新增边缘发光和立体键帽绘制；设置变更通过现有 KeyboardView/Painter 即时刷新，不依赖关闭并重开输入法面板。
- 当前状态：**已修复待验证**。
- 用户验收：分别打开/关闭两个开关，观察当前面板按键立即变化；切换后再输入，检查绘制、触摸区域和按键布局没有异常。

### BJ-006：系统剪贴板新内容没有提示

- 本轮修复：提示作为候选栏临时覆盖层显示；空 preedit 下候选后台刷新不清除提示；系统剪贴板异步读取增加多次重试；候选栏尚未挂载/显示时不提前写入已提示指纹。
- 当前状态：**已修复待验证**。
- 用户验收：输入法面板打开时从其他应用复制新文本，确认候选栏出现提示；提示可操作，之后候选恢复；面板未打开时复制，再打开输入法确认最新内容能提示。检查不会重复提示旧内容。

### BJ-007：扫码悬浮窗口标题栏与相机预览不同步 / 浮窗不稳定

- 首次记录：2026-11-22（用户要求 516 测试后继续整体重构）
- 影响模块：`apps/android/app/src/main/kotlin/app/qingjian/inputmethod/scan/ScanOverlayService.kt`
- 根因方向：旧实现将标题栏放在 Dialog、相机预览放在独立 TYPE_APPLICATION_OVERLAY 窗口，需要同步两套坐标、焦点和窗口生命周期；拖动、软键盘与尺寸变化时容易发生偏移或触摸不一致。
- 本轮处理：0.3.9-dev / #527 将标题栏、预览和扫描控件合并为单一 WindowManager 根窗口；预览区随根窗口伸缩；前缀编辑时临时移除 NOT_FOCUSABLE 并请求系统输入法，结束编辑后恢复；保留原有图标、扫码识别、拍照识图、闪光灯、前缀过滤、扫描框调整、拖动/缩放及标题栏玻璃效果。
- 当前状态：**已修复待用户验证**。CI 编译和固定签名通过，不代表设备端已验证。
- 用户验收：连续打开/关闭前缀软键盘；拖动标题栏和调整大小；测试扫码、识图、闪光灯、过滤开关、扫描框形状和拍照按钮；确认窗口不漂移、不闪退、不抢宿主输入焦点。

### BJ-008：视频背景被主题底色遮挡 / 视频区域无法适配

- 首次记录：2026-11-22
- 影响模块：`service/InputPanel.kt`、`service/DynamicVideoBackgroundView.kt`、`panel/DynamicBackgroundPanel.kt`
- 本轮处理：0.3.9-dev 曾尝试设置透明层和四种视频适配模式，但用户实测候选栏、键盘和底部行仍被主题色遮挡。0.3.10-dev 移除四模式，恢复等比例铺满居中裁切；将输入法窗口本身设为透明，并在视频启用时降低键帽不透明度，使视频可透过各层显示。
- 当前状态：**已修复待用户验证**。需检查关闭背景光效、打开视频背景时，候选栏、键盘和底部操作条都能透出视频；四种模式均需实机验证。

### BJ-009：按键效果颜色选择不够直接 / 按键外观设置入口重复

- 首次记录：2026-11-22
- 影响模块：`panel/KeyEffectsPanel.kt`、`panel/DynamicBackgroundPanel.kt`
- 本轮处理：边框颜色和背景颜色改为点击对应色块直接选中；0.3.9-dev 仍在「键盘背景动效」页残留一组透明度/磨砂控件，0.3.10-dev 删除重复 UI 与事件处理，保留「按键效果」页及底层绘制设置。
- 当前状态：**已修复待用户验证**。确认色块选中反馈正确，透明度/磨砂调节仍有效且重启后设置保留。



### BJ-010：扫码浮窗打开后整屏磨砂

- 首次记录：2026-11-23（用户反馈 #527 安装测试）
- 根因：单窗口重构后仍保留 `FLAG_BLUR_BEHIND` / `setBlurBehindRadius`；系统窗口后方模糊作用范围并非标题栏局部，导致整屏背景模糊。
- 本次修复：0.3.10-dev 移除系统级后方模糊，不再启用 blur-behind；扫码标题栏保留原有布局、按钮和图标。
- 当前状态：**待用户验证**。

## CI-001：Rust 日志导出调用不存在的 Dispatch.flush

- 首次记录：2026-11-22，GitHub Actions #533–#537 连续构建失败。
- 影响模块：`apps/android/core/src/jni/logging.rs`、`apps/android/app/src/main/kotlin/app/qingjian/inputmethod/core/AppLog.kt`、`core/Native.kt`。
- 失败步骤：`Build APK with the persistent release key`；Gradle 的 `:app:cargoNdk` 任务失败，后续签名验证与 artifact 上传均跳过。
- 关键错误：Rust E0599，`no method named flush found for reference &Dispatch`，位置 `apps/android/core/src/jni/logging.rs:131`；原调用为 `tracing::dispatcher::get_default(|dispatch| dispatch.flush())`。
- 根因：当前 tracing 的 `Dispatch` 没有该 `flush()` 方法，JNI 日志刷新桥接调用了不存在的 API。
- 本轮处理：提交 `0d30a9de4196ddb29e56e25f770ffc65a7f6fdfd` 移除 `Native.flushLogging()` JNI 桥接；`AppLog.flushForExport()` 改为写入导出边界标记，依据同步写入的 RollingFileAppender 不再调用虚构的队列 flush。
- 验证：#538 的 Rust/Android 构建、固定签名 v2 验证和 artifact 上传全部成功；运行页：https://github.com/angelabela/qingjian-enhanced/actions/runs/45049084378。
- 当前状态：**已修复待验证**（CI 编译问题已通过 #538 验证；日志导出内容仍待用户设备验收）。

## CI-002：android-debug 工作流未生成真正的 Debug APK

- 首次记录：2026-11-22 监控核查。
- 影响文件：`.github/workflows/android-debug.yml`。
- 当前行为：工作流名为 `android-debug`，但执行 `./gradlew assembleRelease`，准备固定 Release 密钥、验证签名并上传 `qingjian-android-signed`；没有执行 `assembleDebug`。
- 分支范围：push 分支列表不包含 `feature/workflow-444-model-coordination`。
- 最近运行：#571，分支 `feature/android-optional-trigram-model`，提交 `1fc9c71979415edd8626746bd5fd48d93d34f08c`，成功生成的仍是固定签名 Release APK。
- 当前状态：**未解决**。本次监控仅检查，没有修改工作流。

## REL-001：APP 版本序列与 versionCode 档案记录不一致

- 首次记录：2026-11-22 监控核查。
- 用户规则：以 `0.3.4` 为基线，每次成功交付可用 APK 后 patch 递增；保留 `-dev` 后缀，APP 版本与 workflow run number 分开。
- 源码核对：#532 的 `apps/android/core/Cargo.toml` 为 `0.3.10-dev`，按 Gradle 公式计算 versionCode 为 `310`；#538 当前源码为 `0.3.12-dev`，按同一公式计算为 `312`。
- 异常：本次检查未找到成功交付的 `0.3.11-dev` APK；旧档案写入的 `30310/30312` 与当前 Gradle 公式计算值不一致。versionCode 目前按源码推算，未下载 APK 读取 Manifest。
- 处理边界：本次不修改应用源码、版本配置或 PROJECT_RULES；仅记录实际源码值和差异。
- 当前状态：**未解决**。按最新成功交付 `0.3.12-dev`，下一次新可用 APK 应为 `0.3.13-dev`（按当前 Gradle 公式计算 versionCode=313）；需先核对 `0.3.11-dev` 缺失记录、旧档案 `303xx` 值及 PROJECT_RULES 中的历史快照，确保 versionCode 单调且可覆盖升级。

## 新问题记录模板

复制以下结构新增条目：

### BJ-XXX：问题名称

- 首次记录：
- 发现版本 / 工作流：
- 复现步骤：
- 实际现象：
- 期望结果：
- 影响文件 / 模块：
- 历史修复与提交：
- 根因：
- 本次修复：
- 验证方式与结果：
- 当前状态：未分析 / 修复中 / 待用户验证 / 已解决 / 回归

## 归档规则

用户明确确认已解决后，保留条目并标记“已解决”，记录确认版本；不要删除历史。若后续复现，新增回归记录并关联原编号。


## BJ-044：日志文件日期与手机本地日期不一致

- 首次记录：2026-11-23（用户上传日志显示北京时间 11 月 23 日凌晨的事件仍写入 UTC 日期的 11 月 22 日文件）
- 根因：旧版使用 `tracing_appender::rolling::Rotation::DAILY`，文件日期按 UTC 日界线切换，与手机本地日期相差时区偏移。
- 本轮处理：改用 `jiff::Zoned::now().date()` 读取系统本地日期；每条日志事件开始时检查日期，跨本地零点后写入 `ime.YYYY-MM-DD.log`；同一事件持有文件锁，避免跨零点拆行；保留期从 7 天调整为 30 天。
- 当前状态：**本轮修复待构建与设备验证**。

## BJ-045：日志 ZIP 导出可能静默遗漏或截断日志

- 根因方向：旧版读取失败时 `getOrNull() ?: continue` 静默跳过；manifest 只记录文件大小、修改时间和行数，无法校验内容是否完整。
- 本轮处理：仅收集 `ime.*.log`；对读取期间仍在写入的文件进行重试；多次失败明确报错，不再静默跳过；manifest 增加时区、总字节数、最新记录时间、每个文件 SHA-256。
- 当前状态：**本轮修复待构建与设备验证**。

## 待日志分析：剪贴板验证码区域、视频背景和扫码前缀输入

- 剪贴板：增加监听触发、primaryClip 可读性、条目数量、MIME 与文本存在状态日志（不记录剪贴板正文）；收到新日志后核对监听、去重、数据源与 UI 刷新链路。
- 视频背景：增加 TextureView 表面尺寸、View 实际尺寸、视频源尺寸、变换比例和播放器准备/错误日志；收到新日志后对照面板布局测量链路。
- 扫码前缀：增加点击、窗口可焦点状态、输入框焦点、IME 请求是否被系统接受及 IME active 状态记录；收到日志后定位焦点或软键盘阶段。
