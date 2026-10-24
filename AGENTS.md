# Agents Anywhere 手表版 — 项目约束

本仓库是 [dsh-watch](https://github.com/DING-HAO-RAN/dsh-watch) 的改造分支：手表端从「局域网中继客户端」改为 **Agents Anywhere（AA）云 / 自托管服务器的客户端**（方案 A）。中继、UDP 发现、配对整条链路已删除；`desktop/`、`relay/`、`tests/` 是原方案的遗留代码，仅供参考，不参与手表构建，**不要再往里面投入修改**。

## 构成

- 唯一的有效部件是 [app/](<app/>)：原生 Java 手表应用，包名 `cn.dsh.watch`。
- **HTTP 层集中在 [Api.java](<app/src/main/java/cn/dsh/watch/Api.java>) 一个文件**，界面代码不直接碰 URL。想换自托管服务器，改登录页里的地址即可；改协议时只动 Api.java。
- [Store.java](<app/src/main/java/cn/dsh/watch/Store.java>) 存的是**账号**（base + email + password + token + userId + displayName + 备注），不是设备列表。`Store.parseServer` 是地址解析的唯一入口，**不要再引入第二套地址存取逻辑**。
- 换账号（`switchAccount`）时必须清空：会话、`steps`、`openGroups`、`openRows`、`lastListSignature`、待答通知与模型目录，否则上一个账号的状态会套到新界面上。
- 「记住密码」默认开启：AA 令牌 7 天 TTL 且**无刷新接口**，静默重登依赖本机保存的密码。关闭后令牌过期只能手动登录。

## AA 协议（实测结论，改动前先复核）

- 登录：`POST /api/v2/auth/password-salt {email}` 取盐 → `passwordVerifier` = PBKDF2-HMAC-SHA256（密码、盐 UTF-8、**120000 轮**、32 字节）→ base64url 无填充 → `POST /api/v2/auth/login {email, passwordVerifier}` 换 `{userId, accessToken, ...}`。7 天 TTL。401 时静默重登一次，再失败清令牌回登录页。
- 每个请求都带 `Accept: application/json` 与 `ngrok-skip-browser-warning: true`（自建服务器架在 ngrok 后面时没有它会被拦成网页）；JSON body 加 `Content-Type: application/json; charset=utf-8`；空 POST body 用 `{}`。
- FastAPI 错误是 `{"detail":...}`；RPC 风格响应在 HTTP 200 里带 `{ok, result, error}`，`ok:false` 不是网络失败。两者都要翻译成中文给用户。
- REST 列表响应有包裹键：`sessions` / `projects` / `connectors` / `runtimes` / `state` / `notices` / `catalog`（模型目录外层多一层 `catalog`，Api 已解包）。`POST /sessions/read` 的 body 是**纯 JSON id 数组**。
- 新建会话走 `POST /sessions/create-and-start`，服务器要拉起运行时，**读取超时放宽到 75 秒**。
- **两个独立的序列域**：增量轮询游标是时间线信封的 `nextSeq`（事件序号）；翻历史用条目上的 `orderSeq`（最早一条减 1 往回翻）。二者互不通用，混用会把历史翻页弄乱。
- 提问两类回法：`input_request` 表单回 `{answers:{问题id:{optionIds,customText?}}}`，`actionId` 用表单自带的；审批/确认类没有表单，**选项 id 就是动作 id**。只有 `type=="interaction"` 且 `responseRequired` 且状态 `open|responding` 的通知可答。
- 模型目录 `/runtime/catalogs/model` 给 `{models:[{selectionId, reasoningItems:[...]}]}`；当前生效值以会话状态的 `selections` 为准，目录 `default` 只做初次兜底。切换要先 `POST /takeover` 再 PATCH selections；这条链路**刻意不做 401 静默重登**，失败直接报给用户。
- **只读与接管**：会话默认 `takeover=false`，服务器对发消息 / 切模型一律回 RPC 冲突（原文 "session is read-only until takeover is enabled"）。手表端在 `sendMessage` 前检查该字段：只读 → 先弹「开启接管？」确认页 → `POST /sessions/{id}/takeover` → 成功后自动继续发送；`applySelections` 内部已自带 takeover。接管是**持久状态**（`DELETE` 关闭），开启后官方手机端/网页端不会实时回滚。列表接口与快照的 `session` 对象都带 `takeover` 字段，快照的优先（更新）。
- **null 陷阱**：服务端文案字段可能显式为 `null`，`org.json` 的 `optString` 遇 null 返回 null，所有解析必须经 `Api.text(...)` 兜底，否则界面会崩。

## 构建

- 构建走 [scripts/build.ps1](<scripts/build.ps1>)，直接调用 SDK 工具链（aapt2 → javac → d8 → zipalign → apksigner），无 Gradle。环境用 `JAVA_HOME` / `ANDROID_HOME` 指定，**不要把本机路径写进仓库**。
- `BuildRoot`（或 `WATCH_BUILD_ROOT`）**必须在项目目录之外**，脚本会拒绝在工作树里构建——避免把临时产物和签名私钥混进版本库。
- 脚本是 UTF-8 **带 BOM**：Windows PowerShell 5.1 读无 BOM 文件会按 GBK 解码，中文注释直接变成语法错误。改动脚本时保留 BOM。
- 三个必须保留的写法（去掉就会踩坑）：
  1. 外部命令输出重定向到文件后判退出码——PowerShell 5.1 在 `ErrorActionPreference='Stop'` 下会把 javac 的过时警告当致命错误中断构建，从而**掩盖真正的编译错误**；
  2. 源码与 class 清单写文件后用 `@file` 传参——数组经 shell 展开会被拆坏，导致 d8 静默漏掉大部分类，手表上表现为 `NoClassDefFoundError`；
  3. 用 JDK 的 `jar` 工具把 dex 追加进包——受限语言模式下不可用 .NET 的 ZipFile。
- `build.ps1` 会逐个核对 `MainActivity` / `WatchUi` / `WatchUi$Page` / `Store` / `Api` / `Notify` / `Markdown` 七个 class 是否存在，这是拦截「类漏编」的最小门禁。新增顶层类时同步维护这个清单。
- **调用方式**：在本机代理环境里 `powershell -File build.ps1` 下 `$PSScriptRoot` 会是空串（Split-Path 直接炸）；要用 `-Command "& '<脚本绝对路径>' -BuildRoot '<目录>'"` 调用。

## 界面

- 圆屏适配 v2 由 [WatchUi](<app/src/main/java/cn/dsh/watch/WatchUi.java>) 的 `page()` 一处负责：**滚动区满屏，静态留白极小**（圆屏左右 7%、上下 4.5%），边缘行的收束交给 `CurvedScrollView` 的缩放/淡出（仿 Wear OS ScalingLazyColumn：中心 45% 不动，靠边行约 84% 大小、55% 透明度）。不要再把内容压进「圆的内接矩形」——那样上下是黑边、两侧全浪费（v1 的教训）。新页面入场时 `render()` 会把首行下移到 8.5% 屏高，标题不落在淡出区。
- **绝不要按控件自身的屏幕位置去动态调边距**。它形成布局反馈环——算边距 → 控件变窄 → 文字折行 → 位置变化 → 再算出不同边距，最终把同排按钮压成宽窄不一的窄条且无法拉伸。基础安全区已经足够。
- 按钮用 `TextView` + 自绘背景实现（`WatchUi.button`），**不用 `android.widget.Button`**：后者带 9-patch 背景与固有最小宽度，在窄圆屏上会被顶住而撑不满整行。按钮必须单行显示，宽度交给父容器（`LayoutParams(-1, -2)`）。
- 同排两个按钮用 `weighted()`（`weight=1` 平分）；**之后不要再改它们的 layoutParams 或加 margin**，那会破坏 weight 分配。
- 可点区域不低于 48dp；**操作入口统一收进「更多」页**（底部边缘上滑或底栏「更多」按钮），页面上只留最常用的两个按钮，把高度让给内容。
- 标题必须单行（`WatchUi.headingView` 已设 `setSingleLine`）。圆屏可视高度只有三百多像素，标题一旦折成两三行，按钮和内容区就被挤出屏幕。截断用 `clipLine`（只加省略号），**不要用会插换行的写法**。
- **绝不能让「渲染 → 发请求 → 回调再渲染」形成环**。列表页曾经如此：`showSessionList()` 里发请求，回调又调 `showSessionList()`，屏幕每轮都重建、持续上下抽动。现在的分工是：`showSessionList()` 只在进入页面时调用一次，回调一律走 `renderSessionListPage()`（只重绘、不发请求）。
- 界面用「重建整屏」的方式刷新，因此**滚动位置由 `render()` 统一保留**，换页时才调 `resetScroll()` 回顶部。会话页（4 秒）与会话列表（10 秒）都会定时刷新，不做这件事用户就翻不动列表和长对话。
- 刷新前先比对**内容指纹**，没变化就直接返回。定时刷新还额外受两道闸约束：用户刚触摸过（2.5 秒静默期）、或正在编辑时跳过。
- **需要用户停留的页面**（发消息、打字回答、选项答题、模型选择）必须先置 `editing = true` 暂停自动刷新，否则刚打开的界面会被立刻换掉。
- 服务端 JSON 的文案字段可能显式为 `null`；`Api.text(...)` 兜底是硬性规则（见协议一节）。
- **长对话分步加载**（实测 100 条快照慢且易超时）：首屏快照 `snapshot?limit=30`（[Api.java](<app/src/main/java/cn/dsh/watch/Api.java>)），更早的靠「载入更早的内容」按需翻（`timelineHistory`，orderSeq 域）；**渲染侧再裁一刀**（`visibleSteps`，首屏只渲最后 15 条，「显示更早的内容」本地展开 +15，`loadOlder` 成功后窗口扩到全量防止新拉的历史被藏住）。会话内容还有 **LRU 缓存（6 个会话）**：再进秒开（显示上次内容），快照回来静默校正；载入失败时已有内容就保持画面 + 轻提示，不上整屏错误页。
- **进会话要落在最新消息（底部）**：`fillContent` 末尾 `scrollToBottomAfterRender` → `render()` 的 `pendingScrollToBottom` 分支。**不能用嵌套 `post` 等布局**——三连 post 在同一批消息里就跑完了，早于首帧 traversal，页面还没布局、滚了个寂寞；必须 `postDelayed` 跨帧（100ms 滚一次 + 300ms 兜底底栏让位 padding 的二次变化）。

## 手势与操作

- 手势统一在 `MainActivity.dispatchTouchEvent` 里判定，**不能**挂在页面根容器上——子层 `ScrollView` 会先消费触摸，左滑会被当成纵向滚动。
- 判定要用**整个手势期间的最大位移**，不能只看抬手那一帧：注入的滑动常被拆成很多小步，逐帧看会漏判。门槛为横向 ≥44dp 且大于纵向的 1.2 倍。
- **右滑返回上一级，全程跟手**（页面实时平移，阻尼 0.7，拖过半屏才完成转场，否则弹回）。拖拽期间页面底下铺「上一层」快照（`backShot`，前进换页时顺手拍下的旧页截图）——拖出当前页看到的是上一层的样子，不是一块纯色；没有快照时退回画布色。**右滑到顶**（项目列表根页面）是双滑退出：第一滑只提示「再滑一次退出」并弹回，2.5 秒内第二滑才真正退出（`exitApp`）。
- **左滑菜单已退役**。原菜单的全部功能收进「更多」页（`showMorePage`，`navTag="more"`，整页行列表 + Material 图标），入口：**屏幕底部边缘（下 12%）起手的上滑**（抬手判定，≥44dp 且横向位移不超过纵向 1.2 倍；列表中间上滑翻页不受影响）或底栏「更多」按钮。打开期间 `editing=true` 防定时刷新重建。
- **换页动画**：每个页面建好后设 `page.navTag`（如 `"session:"+id`、`"compose"`），`render()` 靠它区分「换页」与「自动刷新重绘」——只有标签变化才播入场动画（220ms 位移 + 淡入，DecelerateInterpolator(1.8)），方向由 `inferDirection` 按页面类别推断（进入浮层页=前进、回到会话/列表=返回），显式 `navForward()` / `navBack()` 可覆盖。自动刷新渲染的标签不变，**永远不播动画**，这是动画不干扰滚动保留的前提。
- 正文与工具结果都经 [Markdown](<app/src/main/java/cn/dsh/watch/Markdown.java>) 渲染：标题放大加粗、列表带圆点、行内代码等宽加底色、引用变灰、表格压成一行、代码块整体等宽。工具读出的 .md 文本因此能直接可读。渲染入口在 `messageBubble`（助手消息），用户消息按原样显示；渲染异常时兜底为纯文本。消息气泡支持**长按复制**（`bubbleRich(..., copyText)`，复制 Markdown 原文）。
- **视觉风格对齐 AA 深色主题**（中性 Zinc 色系，`WatchUi` 色块顶部有完整色表）：画布 `#09090B`、主操作按钮是**纯白胶囊**（`#FAFAFA` 底 + 黑字 + 暗波纹），次按钮深灰面板 + 细描边；助手消息**无气泡底色**铺在画布上，用户消息右侧深灰胶囊；链接/行内强调用金色 `#EBB353`。颜色只标状态（运行绿、等待琥珀、错误红、链接金），不标说话人。
- **字号缩放**：`WatchUi.text()` 是所有文字的唯一出口，设置页的四档缩放（0.85/1.0/1.15/1.3）经 `setFontScale` 在这里一次生效；48dp 最小点击高度不随缩放变，大字号靠按钮自然增高。
- **圆屏判定**：`page()` 用 `isScreenRound() || 近正方形(|w−h| ≤ max(4px, 3dp))`——不少全安卓圆表（454×454 的 1.39 寸屏）报告的是方形配置，只看 API 会按 8dp 方屏边距处理，四角被表圈切掉。
- **界面设置页**（`showSettings`，列表/会话菜单的「界面设置」进入）：字号、会话刷新频率（2/4/8 秒，`sessionRefreshMs()` 取代旧常量）、进会话页默认「只看对话」、**提醒通知开关**（`Notify` 服务）；改动即时落盘生效，档位用「点一下换一档」的循环行。开关变化要同步启停服务（`startService` / `stopService`）。
- **会话页底部操作栏是固定底栏**（`ui.bottomBar(page)`）：钉在屏幕底部不随内容滚动，内容列自动让出其高度；栏内按钮照常 `weighted()`。不要把操作栏 `ui.add` 进内容流——长对话里会滚出屏幕，找不到「更多」。
- 工具调用按**正文分界**分组，不是按「相邻的工具步骤」：时间线里工具与正文平铺、中间几乎总隔着一条消息步骤，按相邻关系分组会让每次调用各自成组，折叠就失去意义。折叠分两级——点卡片列出连续调用的清单，点清单里某一条才看参数与结果；收组时连带清掉内部明细，下次打开是干净的清单。
- **展开状态必须记在 `openGroups` / `openRows` 里**（工具组键 `groupKey`，行键 `groupKey#i`，思考块键 `think:<seq>`）。界面是重建整屏刷新，展开状态若只存在于视图上，会话页每 4 秒一次的刷新就会把用户刚点开的详情缩回去——这正是「点了展开却看不到内容」。还原直接设可见性、**不走动画**，否则整屏重建时每张卡片都闪一遍。
- 收组监听器只挂标题与摘要两处，**不要挂整张卡片**：清单区域必须留给单条明细，否则「点这一条」被卡片抢走，变成把整组收起来。清单行整行可点（`rowBox`，`minHeight 48dp`）。
- **提醒通知**（[Notify.java](<app/src/main/java/cn/dsh/watch/Notify.java>)，顶层 `Service`）：应用启动时若已配对且开关开启则 `startService`，`START_STICKY` + Handler 每 30 秒轮询；筛 `waiting/unread` 且非前台会话（`MainActivity.foregroundSessionId` 去重），拉 notices → `Api.parseNotices` → 未提醒过的发高优先级通知（渠道 `asks`），点开带 `session` extra 直达会话（`MainActivity.openPendingSession`）；问题处理完自动撤通知。Doze 深睡时跳过该轮。改轮询/去重逻辑不要破坏「只在新问题出现时震一次」（`seen` 键 = `会话:通知id`）。
- 模型选择两级（模型 → 推理强度）：AA 目录没有供应商层级，Api 把整个目录包成一个名为「模型」的 `ProviderInfo` 以复用既有结构；显示时不要把它再拆成三级。当前值取「会话状态 selections → 目录 default」，不存在时不臆造模型名。
- 长路径会占满圆屏，展示前一律经 `shortenPaths` / `shortPath` 压成最后一段。

## 测试

- 目前没有自动化测试：原 `tests/` 下的脚本都是对着**中继**跑的，不适用于 AA。协议层的核对靠 [Api.java](<app/src/main/java/cn/dsh/watch/Api.java>) 里的注释与 AA 手机客户端 / 网页端的实际行为对照。
- 涉及界面行为的改动，用 `adb` 在真机上从实际入口走一遍（启动 → 登录 → 点项目 → 点会话 → 发消息 / 答题 / 切模型）。
- 手表上验证输入框内容要看截图：`uiautomator dump` 对第三方 `EditText` 常读到空值。
- 验证「界面是否还在闪」不要靠肉眼看截图：间隔几秒连拍数张，比对文件哈希；哈希一致即画面稳定。
- 没接 adb 时，构建通过 + 代码审查是最低门槛；**每次改完必须跑 `scripts/build.ps1` 确认零编译错误**。

## 发布

- 仓库：[DING-HAO-RAN/dsh-watch](https://github.com/DING-HAO-RAN/dsh-watch)（上游，MIT）。本分支的应用名是 **DSH Watch**（AndroidManifest 两处 label），产物名固定为 `DSH Watch.apk`（[README](<README.md>) 与之一致）。
- 仓库用 **MIT 协议**（[LICENSE](<LICENSE/>)，版权署名用仓库标识 `DING-HAO-RAN`，不写个人姓名与邮箱）。改协议要同步三处：`LICENSE`、README 的许可段、`relay/package.json` 的 `license` 字段。
- 成片与封面放在 `docs/`（**不进版本库**，`.gitignore` 已忽略）；投稿文案在 `docs/B站投稿文案.md`（进版本库）。简介正文距 B 站 2000 字上限留余量，再加内容先删同量的旧内容。
- **画面不许用 CSS 动画**：逐帧截图时 CSS 动画不受控，同一 `t` 会截到不同画面。所有随时间变化的量都必须由 `t` 直接算出来。
- 旁白每行 ≤30 字、语速 `speed=-6`；字幕直接用旁白原句（静音也要能读懂），画面上的大字只写旁白里没有的事实，两者刻意不重复。

## 敏感信息

- **绝不把用户凭据（邮箱、密码、令牌、userId）写进代码、git、日志或回复**。账号数据只存在手表本地 SharedPreferences（应用私有目录）。
- 仓库里的 `desktop/`、`relay/`、`tests/` 属于旧方案，其中可能引用本机路径或历史令牌，不要把它们的内容当作现状。
