# ME Transfer Drive 发布前性能、安全性与 Bug 综合审计

审计日期：2026-10-28。审计基线提交：`e4959a7401b596e709ec654ebc339c8c193bab43`。

## 1. 执行摘要与发布判断

**结论：可以冻结常规功能、进入受控 GTNH 验收；尚不建议将当前构建作为稳定版发布。** 本轮没有找到在健康原生 API 正常返回条件下、可证明阻止发布的 P0 复制/丢失/免费转运路径；这不是“所有环境均安全”的证明。发现两项有具体触发条件的 P1，应先作局部修复或明确接受其影响，再确定公开 Release Candidate：

1. **READ 可见性切换遗漏精确 ItemWatcher 通知**：终端库存可已刷新，等级发信器仍持有旧数值。属于确定的通知链缺口，不是旧 Handler 分叉问题。
2. **共享能源额度下的长期饥饿**：低开销完整 FIFO 轮次总由同一设备先取额度，后设备获得 CPU 片但长期搬运0。新增确定性测试复现，不破坏数量或共享总额度。

第三项 P1 是**固定 AE2 的条件集成风险**：第三方 Handler/同步 Watcher 抛异常后，上游缺少 finally 的深度计数/栈可能污染网络并保留引用。本 Mod 的保守隔离不能修复整个上游网络；不建议在附加 Mod 中反射清理或大范围改写它。

没有发现需要再次重构原生 Handler 共用、泛型事务、标准目标 monitor、稳定 UUID 路由、独立服务端边界或一次性退款架构的证据。2ms 是准入软预算，不能中断慢 Handler、图见证、回填或退款，不能作为峰值 MSPT 承诺。真正需要游戏测量的热点是：循环图见证、反复能源大额探测、原生元件逐类型 NBT 写入、同步 Watcher、复杂外部容器和多人 GUI。

**范围控制：** 本轮未修改生产 Java、依赖、配方、GUI、工具链或运行参数；仅新增报告、审计见证测试并更新开发状态。没有进行完整整合包游戏测试，没有提供实测 TPS/MSPT、内存字节或性能提升百分比。

## 2. 证据、版本与验证层级

- Mod：实际逐路径阅读 `src/main/java/com/kyou/metransferdrive/`，包括存储、事务、路由/图、调度、能源、NBT、GUI/协议、工具及方块掉落。
- AE2：本地 `Applied-Energistics-2-Unofficial-rv3-beta-1080-GTNH-sources.jar`，SHA-256 `e1c6ec53c8f8d2e70c3cb79f095a32786cbb4b8449ebe8c040ce8c103706d9e8`。下文 `appeng/...` 为此 JAR 内的真实源码路径，不使用 master 代替。
- Thaumic Energistics：本地 `ThaumicEnergistics-1.7.64-GTNH-dev.jar`，SHA-256 `3952ae0afbc8878468bb987a56cf95064feb95411263775e71cf4431066c84ae`。通过 `javap` 核对 `AEEssentiaStackType.getId/getAmountPerUnit`、元件库存继承及 `saveChanges/loadCellStacks` 字节码；不能将缺少源码行号的字节码偏移伪装成源码行号。
- Minecraft/Forge：项目 RFG 固定版本生成源码，如 `build/rfg/minecraft-src/java/net/minecraft/tileentity/TileEntity.java`；GUI、掉落和主线程边界同时核对实际调用位置。
- 旧审计、`SAFETY_REVIEW.md`、路由/吞吐/能源报告作为设计背景重新核对；旧问题仅在当前代码仍有证据时列入。

下文 Mod 简写路径均相对 `src/main/java/com/kyou/metransferdrive/`。**源码确定**指控制流/次数/限制；**条件风险**有明确前提但未在游戏复现；**自动见证**使用生产算法及契约替身；**待实测**表示缺少实际世界、供能及第三方 Handler 的运行证据。

## 3. 当前架构与关键调用链

### 3.1 存储注册、缓存与网络通知

```text
元件 setInventorySlotContents / decrStackSize
  → AppEngInternalInventory 真实槽回调
  → Tile.onChangeInventory(slot>=0)：结构失效、重建64槽原生Handler
  → 完成缓存标志 → setIdlePowerUsage同步事件
  → MENetworkCellArrayUpdate → GridStorageCache.cellUpdate
  → 需要库存时通用ICellProvider枚举 → 网络持有同一Handler
  → 可读且活跃时 Platform.postChanges(removed,added)
  → setter末尾 markDirty → onChangeInventory(-1)：仅标保存，不再次失效

普通源网读写
  → NetworkMonitor → NetworkInventoryHandler → 本机外层访问权限Handler
  → 同一个原生cell Handler/CellInventory
  → 原生元件NBT更新 + Tile.saveChanges标区块dirty
  → 原生网络数量通知；不查询绑定目标/路由，不重建64槽

内部转运
  → getNativeCellHandler返回上述外层Handler的同一internal
  → 原生提取/回填 → sourceChanged发布可读源网delta
```

依据：`tile/TileMETransferDrive.java:onChangeInventory:179–226,getNativeCellHandler:376–379,saveChanges:489–494,updateHandlers:497–555`；AE2 `tile/inventory/AppEngInternalInventory.java:80–138`、`me/cache/GridStorageCache.java:237–302`、`me/cache/NetworkMonitor.java:296–345`。

**旧缓存 P0 未复现为当前问题。** 负槽保存不再使已发布的 Handler A 失效；缓存完成标记先于闲置耗电同步事件；构造期递归查询/换元件被拒绝。正常内部操作和源网共用原生库存，不新增另一份库存缓存。实际世界中的 ItemWatcher 重入仍需验收，且第8.1节的权限切换通知问题独立存在。

### 3.2 周期、路由、事务与能源

```text
每世界Tile Tick：TransferClock.tick + 有界状态检查
  → 周期到期且存在绑定：加入跨维度SERVER END FIFO（不积压重复周期）
  → 每片最多解析4接口，当前实际Grid去重；8组缓存候选
  → 每片最多打开1槽/探测4条/推进8步
  → 当前元件一种内容：long快照目标、实时原生数量复核
  → 最多2次准入尝试，包含拓扑及按类型循环图检查
  → 目标标准monitor模拟 → 共享源Grid能源预算/真实预付
  → 原生源提取 → 目标monitor实际注入 → 已知余量回原元件
  → 按确认接收量结算，一次退款或隔离
  → 安全结束才扣减路由余量/推进下一不同Grid或下一种类
```

依据：`transfer/AETransferController.java:84–131,134–277,280–332,335–418,420–488`；`RoutedTransfer.java:28–58`；`TransferScheduler.java:17–75,127–165`。

周期为1～86400秒，默认60秒（1200 Tick），只控制作业启动。活动作业跨Tick续作；时钟仍递减，到期不会创建第二份并行作业。源网真正离线、配置变化或拓扑归属变化时按原机制停止，等待后续周期；能源保护暂缓且源网仍活跃时保留当前游标、下次服务继续。

## 4. 原生 ME Drive / I/O Port / 本机比较

| 项目 | 固定版本 TileDrive | 固定版本 TileIOPort | 当前 ME Transfer Drive |
| --- | --- | --- | --- |
| 元件与库存 | 10槽，逐元件原生Handler、外层DriveWatcher及按类型列表 | 6输入+6输出，当前ItemStack身份对应一个缓存库存 | 64槽，逐元件原生Handler、外层访问权限Handler；8组仅路由 |
| Grid/频道 | 一个节点，REQUIRE_CHANNEL | 一个节点，REQUIRE_CHANNEL | 一个节点，REQUIRE_CHANNEL；不创建8个Grid |
| Tick | **每15 Tick** recalculateDisplay，遍历10槽状态/类型；活跃变化发阵列事件、显示变化markForUpdate | TickRates.IOPort默认1/1；依hasWork及事件wake/sleep，工作回调URGENT、无工作SLEEP；原生设置可覆盖TickRates | 每世界Tick常量时钟/状态；有活动作业才进入全局FIFO；静态外观没有10灯轮询 |
| Handler重建 | 结构/优先级等使isCached失效时更新，不因每15Tick显示回调自动全量重建 | currentCell引用变化时重建缓存 | 真实槽结构/NBT加载时更新；普通内容变化/slot=-1不重建 |
| 搬运来源 | 无主动搬运 | 单元件或整个所在网络monitor，取决于填充/清空方向 | 仅本机单元件单种内容，不查询整个源网库存 |
| 搬运量 | 无 | 基础256名义单位及速度升级，按类型单位换算；原生循环持续到预算/可搬内容停止 | long库存目标，单次offer≤INT_MAX，容量/实付/保护预算可进一步缩小 |
| 接收/能源 | 普通网络路径 | poweredInsert：能源SIM、注入、按实收后扣费 | 标准getMEMonitor(type)，提取前真实预付、实收计费、退款一次 |
| 回填/异常 | 交给单库存API | 失败回源但不核查回填返回，无本机式未知结果记录 | 检查量/类型；已知余量RECOVERABLE，未知UNCERTAIN，不自动补偿 |
| 额外工作 | 显示及原生网络缓存/统计 | 工作枚举/升级/红石/元件移槽 | 目标解析、类型图、拓扑复核、共享调度/能源账目、GUI业务状态 |

原生证据：`TileDrive.java:86–120,199–207,267–294,322–385,414–438`；`TileIOPort.java:87–109,197–203,352–480,488–569`；`core/settings/TickRates.java:29,55–60`；`util/Platform.java:1357–1405`。

### 相同64个元件的比较

| 成本 | 本机1台/64元件 | 原生7台/64元件（70槽） | 原生8台/64元件（80槽） |
| --- | --- | --- | --- |
| 节点/频道需求 | 1/1 | 7/7 | 8/8 |
| 名义基础闲置AE/Tick | 2 | 14 | 16 |
| 元件闲置耗电 | 相同64个元件各自原生idleDrain之和 | 相同 | 相同 |
| 实际元件Handler | 64份native及外层wrapper | 64份native及wrapper | 64份native及wrapper |
| 内容/类型索引规模 | 取决于相同元件内容，不能按一台10槽计算 | 基本同规模 | 基本同规模 |
| 单个元件结构改变时本地重建 | 本机检查64槽×注册类型 | 改动的那台检查10槽×注册类型 | 同左 |
| Grid阵列失效后全网重建 | 仍枚举全网活跃provider的同类型Handler，不因节点少而消除64份库存 | 同原生机制，provider更多 | 同原生机制，provider更多 |
| 外观/时钟 | 每Tick常量计时+作业；静态外观 | 7份每15Tick显示更新 | 8份每15Tick显示更新 |
| NBT | 64元件+最多64绑定/8组+单批证据，集中同一Tile | 64元件+7台设备元数据 | 64元件+8台设备元数据 |

上述AE是名义值，实际持续扣费按CONFIG倍率，频道相关耗电由原生Grid另计。节点、元件容量成本和转运成本必须分开；不能从节点减少推出精确CPU/内存节省比例。另有**容量统计遗漏差异**（第8.4节），不能把少做这项原生功能统计当作等价性能优势。

## 5. 各工作状态的维护频率、成本与缓存

设设备数D、绑定数B≤64、每元件类型K≤63、目标同类型库存Handler数H、能源提供者/可达供能路径规模P、图见证数W。以下是源码上界/理论工作，不是实测耗时。

| 状态 | 实际触发及单次工作 | CPU/内存/网络与保存影响 |
| --- | --- | --- |
| 无绑定，仅普通驱动器 | 每加载世界Tick只时钟和锁/图空状态检查；到期registry.isEmpty直接返回 | 没有周期64槽扫描、绑定解析、图构建或能源SIM。Handler按事件/按需维护；普通AE读写仍有原生开销 |
| 有绑定、8组全OFF | **每周期仍建RoutingCycle并解析所有B个绑定**，每服务片4个；之后逐槽发现组无候选 | 无元件内容快照、无图/转运，但不是“仅计时器”。每片最多开1槽，64槽加结束判定需65服务片；B=64还需16解析片，理想至少81片，不含排队/离线提前退出 |
| 路由启用、目标在线、元件空/无内容 | 每周期解析B；逐槽缓存Handler查询，对可转运元件各取一次至多63条快照 | 最多64元件快照/作业；没有有效候选则不构图、不扣搬运能源。分Tick，不每Tick扫64槽 |
| 少目标持续转运 | 每片最多2次已准入尝试；每笔含图/拓扑/实时量/目标/能源/完整数量事务 | 只扫描本机当前元件；目标成本依原生H、监听者及第三方实现，不按目标绑定数推断恒定时间 |
| 64槽×63小种类 | 最多4032有效种类快照条目；每种类每目标可能一次接收尝试，另有数量桥接/能源分批 | 即使无容量限制也有4032笔，不能合并不同种类。仅按2笔上限得到至少2016事务额度片，完整Controller还含开槽/准备/图/时间预算，实际更多 |
| 上亿同种 | 请求尽量搬空；至少ceil(实际目标量/INT_MAX)笔，额度不足再分 | 不按4096重复；能源与大NBT/回填/监听成本仍可能限制吞吐，不能承诺1Tick搬空 |
| 多设备同时到期 | Tile计时/入队O(D)，END全局队列轮转，设备一次片/服务器Tick | 2ms内只限制新增工作；同Grid毛能源额度共享但有饥饿缺口。各设备图通常独立，数量增加不保证仅固定线性常数 |
| 部分/拒收/退款 | 见第6节调用计数；已知部分先回填和结算，才下个Grid | 更多native序列化、源网delta、退款provider遍历；未知即停止，无无限Tick内重试 |
| RECOVERABLE | 默认每1200Tick（或用户周期）排入一次回源重试，仍经全局次数预算 | 不重新注入目标、不重扣能；源被占满时可长期保留已知held。重载后的记录统一隔离，不自动回填 |
| UNCERTAIN/能源未知 | 每Tick锁状态检查，不继续转运；等待人工核对 | 保留一批证据，不积累无界日志/库存。长期隔离是明确安全策略，不应当作可自动清零的性能垃圾 |
| GUI打开 | 每Container每Tick循环100槽，跳过56隐藏槽，比较8元件+36玩家槽；每10Tick业务状态快照 | 仅变更槽发送，但NBT比较/复制与状态副本可昂贵；不包含在转运2ms预算 |
| 配置/插拔/卸载/保存 | 事件驱动，非固定频率 | 配置使旧作业失效；插拔重建64槽；卸载取消工作；正常NBT保存遍历64元件及业务数据 |

依据：控制器 `tick/openNextSlot/nextType:84–332`；时钟 `TransferClock.java:6–31`；`RoutingCycle.java:95–169`；`ContainerTransferDrive.java:220–247,300–386`。新增 `ReleaseRoutingAuditTest.closedRoutesStillResolveEveryRegisteredInterfaceWhenACycleIsStarted` 确认64个OFF绑定仍进行64次解析/16准备片；65槽推进片为Controller静态推导，不是完整世界计时。

## 6. Tick、拓扑、库存通知和能源热点

### 6.1 软预算究竟覆盖什么

`TransferScheduler.Slice:29–75` 在完整操作间检查设备500000ns/全服2000000ns，最多2次尝试、1快照/4探测/8推进；`runTick:127–165` 在SERVER END服务一次设备身份，跨维度共享。时间准入包含后续本笔拓扑和事务，避免“验证稍慢就永远不能开始”的饥饿。

但准入后不能抢占：一个图工作单位的设备元数据/绑定解析、整轮W个见证、一个元件快照、native库存/供能/Watcher、回填及退款都可超预算。Tile常量Tick、插拔时结构更新、普通AE存取、GUI START队列和Container同步在该预算之外。故不能说“本Mod总耗时≤2ms/Tick”。

当前没有必要为追求硬时间上限把提取、注入、回填拆成异步操作；这会扩大数据安全边界。先测单次调用和峰值，再决定是否有明确收益的局部措施。

### 6.2 拓扑/图：重复工作与必要保护

- `RoutingCycle.isTopologyValid:131–143` 每次检查源身份及所有已解析B个节点归属；不枚举目标库存。
- 健康完整接收的一笔，Controller前后、RoutedTransfer前后及SIM/MOD目标supplier合计约6次该检查，各最多B个已解析节点。这是可定位的重复热点；其中跨外部调用前后检查具有重入/拓扑安全意义，不能只因在同Tick就删掉。
- `AETransferController.prepareGraph:883–960` 每类型证明独立；构建每片4工作单位，最多128Grid/128设备/512绑定/256边/1024工作单位。每个设备取得候选时最多检查64缓存Handler元数据，并按B×8匹配组；不扫描这些元件的内容。
- `RouteGraph.isFresh:184–196` 逐见证检查。见证包括根和已加入的有效路径，重复边也先登记见证；因此W不仅是不同边数，受根+绑定数量及设备上限约束。完成时反向可达收尾O(Grid+边)，有界但不是每单位只有一个原始Java操作。
- 完成证明最多保留20个已加载世界Tick，然后重建；慢排队不会让刚完成证明必然在使用前过期。持续变动仍可能反复重建/停止周期，不承诺每次拓扑抖动都能转运。
- **负信息边界**：正向见证不能发现“之前空的Grid上新加入设备”或之前离线而未加入的路径。新增审计测试证明旧图仍可isFresh，重建后才识别环。这是有界重建窗口/保守配置图边界，不是全局即时循环检测。
- 配置候选图按存储类型，不按具体物品过滤或实际物流构图；会保守拒绝含候选环、但当前具体类型/过滤实际不能走通的路径。隐藏共享底层库存不在图中。

建议保留同Grid拒绝、TTL、见证及当前同步防回流；不为消除重复遍历而直接删掉证明。全OFF的提前元数据短路较局部，优先级低于P1正确性问题。

### 6.3 每笔 API 调用与 NBT/通知成本

| 结果 | 目标monitor | 来源native MOD | 原生来源NBT重写 | 能源 | 源网数量delta（可读/活跃时） |
| --- | --- | --- | --- | --- | --- |
| 满额成功 | 1 SIM+1 MOD | 1提取 | 1次，遍历本元件K | 1大额SIM+1预扣MOD | 1 |
| 实际部分成功并回填 | 1 SIM+1 MOD | 1提取+至多1回填 | 至多2次 | SIM+MOD+至多1退款调用 | 至多2 |
| SIM拒收 | 1 SIM | 0 | 0 | 无能源SIM/MOD | 0 |
| 额度不足暂缓 | 1目标SIM | 0 | 0 | 通常1能源SIM；本Grid额度已耗尽时跳过能源探测 | 0 |
| 已知回填重试 | 无 | 1回填尝试 | 仅实际写入时 | 无新搬运费 | 实际回填非0才发 |

不计原生Handler内部的sticky/优先级额外SIM、目标自身NBT、监听者重入和API违约。目标网络 `NetworkInventoryHandler.injectItems:112–250` 依优先级/黏性及两遍接收，理论最坏随H及单个外部处理器成本增长；本Mod没有再手动遍历分配目标设备。

原生 `CellInventory.saveChanges:330–381` 每次MOD会遍历并写入K条；ThE自己的`saveChanges`字节码0–251也逐条新建NBT、清理到maxTypes，再回调ISaveProvider。大量小种类因此存在每元件约K²级累计序列化成本；搬空大单种减少事务次数，但不能消除63种各一次保存。

`Tile.saveChanges:489–494` 和数量事务/能源检查点的dirty标记**不等于每次完整序列化64槽或写磁盘**。不过 `TileEntity.markDirty:167–177` 还会读方块元数据并通知比较器更新；一笔事务的多个阶段/能源检查点会多次调用。它与原生cell内容保存、Watcher delta是不同成本，应分开测量。不能把重复dirty标记直接计为重复完整NBT保存。

源网手工delta是绕过源monitor直接操作元件后所必需；没有找到健康路径上“原生源monitor已通知一次、本Mod又通知一次”的重复链。**有精确Watcher兴趣时，通知本身会引发原生整网同种数量查询**：`NetworkMonitor:320–336`→`NetworkInventoryHandler.getAvailableItem:455–488`逐H个Handler汇总；存在外部网络库存时改走带过滤的原生枚举。故“本Mod不扫描整个源网取货”不等于“整个通知调用链永不遍历源网”。同网D台各自产生变化、H也随D增长时，理论维护工作可产生D×H放大；全局预算限制启动数量，不能取消单次通知成本。

结构改变触发的cellUpdate可失效全网同类型网络Handler，下一查询重新枚举全网providers；本地64槽重建和全网索引重建也不能混算。

### 6.4 能源大探测、大退款与保护边界

固定 `EnergyGridCache:202–209,234–358,413–425,469–498` 及 `IEnergyGrid:53–82` 已重新核对。

- stored/capacity是估计值；stored超过90Tick未刷新才可能重算，且不等同于extra+外部可达能源。实时SIM仍有必要。
- `TransferPowerBudget:34–40` 探测额至少按INT_MAX物品的费用构造，与小候选数量/流体单位无关；可能遍历整个本地和外部供能路径P。每笔重查可达供能，额度耗尽后跳过探测。可确认其最坏遍历，不可据此推断真实MSPT。
- 退款一次调用 `injectPower`，不是逐AE循环；原生可能遍历需求方/外部Grid并复制seen集合。金额大不直接等于调用次数大，但大退款更可能经过更多提供者或落入overflow。
- API建议不要一次发送超过10000AE，**不是硬接收上限**；正overflow已归网络，不得重退。当前处理符合契约。把退款机械拆成大量10000AE片会增加调用/中断边界，未证明更安全，暂不建议。
- 退款成功通常把预付释放的空间填回，但不能保证其它供能/拓扑没有变化。overflow所在Grid被删除可能损失能源；ack不表示落盘。
- 能源暂缓仍先做目标SIM/图/拓扑，当前候选每获得工作片可重复这些工作；没有Tick内死循环，但低电长等、复杂目标可能产生持续无效工作。若实测明显，再考虑内部有界退避，保留恢复机会与安全复核。

### 6.5 内存、分配与引用寿命

- 每个填充槽保留native+wrapper，按类型列表引用同一对象；当前作业只保留一个元件快照及一个种类游标。固定类型的`HashBasedItemList.add:31–45`、`FluidList.add:30–44`和ThE `EssentiaList.add`字节码均复制首次加入的stack，不是把整个源网建成第二份库存。
- 每次有效候选会复制identity/stack、候选列表，构图分配有界map/见证；能源非常用倍率使用BigDecimal，普通差额也使用精确转换。这是可定位分配，不等于已有GC故障；没有可支持具体内存字节的堆实测。
- 绑定最多64、图及见证有明确上界、恢复账目只有一批；GUI保留当前/上次配置和状态、服务端请求队列最多1024。不维护无限历史能源/库存列表。
- 停止/完成清cycle、graph、candidate、types；正常/异常结算detach能源端；SERVER队列/预算在卸载取消及关服清理。RECOVERABLE有意保留原元件/Handler以验证回源，并随Tile寿命存在；对象内部循环引用本身不是GC泄漏。
- 未见正常生命周期下由本Mod静态集合无界持有已卸载Grid的链。上游异常GLOBAL_DEPTH/ThreadLocal仍可保留旧monitor（第8.3节），GUI积压也可短期持有玩家引用。反复卸载/进出存档及异常后的堆引用需要真实验收。

## 7. 端到端正确性与不变量

### 7.1 数量与路由

对结果确定的单笔，设x为真实提取、d为目标确认接收、r为真实回填、h为已知held：

```text
0 ≤ d ≤ x ≤ 授权量 ≤ 本元件本种实时量及本轮余量
x = d + r + h
下一目标只处理（本轮旧余量 - d）；仅 h=0 且数量/能源均无pending时故障切换
```

`TransferTransaction.java:55–124` 检查返回范围，来源/目标适配器检查类型身份（`GridStorageDestination:58–72`），余量为long且只减不增（`RoutedTransfer:37–68`）。一次提取不合并其他元件的同种内容；源网新增库存不会扩大本轮快照目标，新类型等下周期。源网并发（主线程间隔）读写允许消耗可回填空间，合法回填失败会保留RECOVERABLE，不能假定事务锁令整个源库存静止。

INT_MAX是外部int桥接保护而非日常4096配额。AE2 `util/item/AEItemStack.getItemStack:400–409` 将数量截为int；本机不向此桥接递交更大量。来源快照最多63条、类型相符、非负、总和`Math.addExact`（`SourceCellAccess:14–47`）；实际long库存经多笔续作。目标或任意子类违约仍可能在抛异常前产生副作用；类型检查不能证明第三方诚实。

物品、流体及注册的essentia走同一事务和`getMEMonitor(type)`；ThE原生单位1、不转换物品/流体，未安装时生产类不直接链接ThE/Thaumcraft。目标可包含原生元件和外部存储总线，不按容器类别封禁。接收端主动虚空报告成功时d表示API接收量，不代表真实持久存量。

### 7.2 能源

对确定结果，M为CONFIG倍率，u为原生数量/名义AE，P为真实预付款：

```text
授权量 ≤ u × floor_exact(P/M)
费用 = roundUpDouble(ceil(d/u) × M)
退款 = roundDownDouble(P - 费用)，仅一次、仅原IGrid且同IEnergyGrid缓存
本Tick同实际Grid毛预留请求和 ≤ 该Grid首次有效预算
```

`TransferEnergy.java:85–132` 防止向上授权/long乘积溢出；实际费用不得超过实付。物品/源质u=1，流体u=1000mB；分笔流体分别ceil，两个500mB是2AE，不是重复扣费。refund向下舍入可能产生一个表示单位的额外净费用；原生double电池不提供无限精度。

有限网：`R=max(0.2C,min(100I,0.5C))`（C=0则100I），`B=min(max(0,A-R),max(M,0.1A))`，A来自实时能源SIM。每笔重查，首次B固定本Tick共享毛总额；退款不复用额度。小网一个名义AE机会仍受R约束；无限标记下仍有限请求和真实付款。此机制不限制其他AE设备；不同Grid共享供能者、合并拆分新身份及SIM后第三方变化不在严格总储备保证内。

`TransferEnergyAccount:74–169` 正常完整/部分/零接收只结算确认量；已知held未成功搬运不承担完整费，后续回填不再收费。不确定库存不退款；未知扣费/退款或原Grid失效保留账目、锁定。未发现把overflow当退款余量或先搬完后无条件补扣的路径。

### 7.3 生命周期、持久化与中断位置

| 中断/事件 | 当前保护 | 仍可能的后果 |
| --- | --- | --- |
| 换元件/改配置 | pending锁，提取/回填重验同ItemStack和同native Handler；配置重入拒绝 | 外部直接破坏世界或违约绕开接口不保证；正常结构变化中止旧槽作业 |
| 目标掉线/合并/拆分 | SIM及MOD重新取目标monitor，验证原周期/当前Grid/权限；变化则回填或停止 | 原生方法内部同步变化/异常仍可能未知，不能当作跨Grid锁 |
| 提取后源网通知抛异常 | 数量事务UNCERTAIN，不重新提取或退款 | 原生元件可能已扣量，但transaction.held尚未收到返回；held=0不能证明未提取，人工不能只凭held补偿 |
| 目标MOD后抛异常 | 不重放目标，不盲目回填已可能接收部分 | 可能长期隔离/无法证明可追回量；并可能污染上游深度状态 |
| 已知回填不足 | 原元件引用保留、RECOVERABLE按周期重试 | 可长期等待空间；保存重载后转人工隔离，不自动重复恢复 |
| 扣费/退款后异常 | 单批金额/尝试标记保留，禁止重复退款 | 预付可能滞留；需核对原网，不转给新网/不生成补偿 |
| 卸载/失效/关服 | 取消调度、清图/路由；正常结算后释放能源引用；服务器清共享表/GUI队列 | 已知恢复引用有意保留到对象释放；上游异常栈引用不受本Mod清理控制 |
| 正常拆除 | 一般散装元件；恢复状态一件封装包含元件及全部证据，非双重掉落；快照恢复不额外掉落 | Forge爆炸、保护Mod和取消放置需真实验证 |
| 正常退出/重载 | 只序列化当前业务状态；有恢复标签原样隔离、不自动执行 | 未完成记录不一定可自动恢复；须保留人工核对流程 |
| 硬崩溃/独立区块保存 | 无危险自动重放/补发 | 源扣量先落盘而目标未落盘可丢失；目标先落盘源仍旧可复制；能源亦可能独立丢失/回滚，甚至已关闭记录无法证明旧保存先后 |

依据：`AETransferController:335–418,588–615,753–831`；`TransferEnergyAccount:194–223`；`TileMETransferDrive:133–145,345–428,575–600`；`BlockMETransferDrive:62–74,94–115`。

**原子性判定：** 主线程顺序避免Mod自身异步竞态；正常API下有运行时数量守恒和确定余量恢复、重复操作隔离。没有跨源库存、目标库存和能源的整体原子提交；dirty不是落盘；未知回调不能承诺无丢失，也不能承诺任意第三方无销毁。相较原生IO Port，对回填返回、未知结果和实际预授权更保守；增加能源账本及退款边界，也增加检查点/调用和长期隔离可能，不能简单宣称全场景更安全。

### 7.4 GUI、工具与旧存档

- Container每次重验Tile对象/UUID、世界/区块、8格距离、世界建造及AE BUILD权限；网络线程仅入队，实际操作主线程执行（`ContainerTransferDrive:73–88,250–295`，`DriveGuiNetwork:64–75,192–232`；AE `Platform:1633–1650`）。
- 自定义点击使用窗口/随机会话/页版本/配置版本/递增序号，拒绝裸C0E；稳定64物理槽不随页重映射。隐藏槽/数字键整栈/Shift单元件splitStack保护存在，普通NBT保留；乱序请求重同步而非强行应用。
- 修改路由/目标用稳定UUID并调用原服务端管理方法；pending时禁止修改，删除清引用；版本2指定集合、版本1单UUID/更早单目标迁移仍严格，坏UUID/未知引用隔离，不按列表下标持久化（`TargetRegistryNbt:66–156`）。
- 工具仅Shift+右键，服务端验证原设备身份/加载/权限/锁及精确方块TileInterface。重复绑定不新增、不改路由；普通右键和客户端不自行判成功（`ItemNetworkBindingTool:44–82,133–209`、`BindingToolLogic:116–200`）。
- 外层64槽损坏NBT整体隔离，恢复封装不复用旧Grid或机器UUID。内部cell损坏另有原生读取边界（第8.5节），不能把外层隔离宣称为任意内部损坏都原样保全。
- 公共/客户端代理、可选Mod类链接仍隔离；静态类检查不等于真实专服启动。

## 8. P0 / P1 / P2 发现清单及精确建议

### 8.1 P1-01：源访问可见性改变遗漏精确 Watcher（确定缺口）

**位置：** `tile/TileMETransferDrive.setAccessMode:453–463`；AE `GridStorageCache.cellUpdate:237–272`、`NetworkMonitor.forceUpdate:352–366/postChange:320–339`、`parts/automation/PartLevelEmitter.configureWatchers:286–311/onStackChange:375–384`。

**触发证据：** 同一active provider不变时cellUpdate不生成provider增减delta，forceUpdate只通知monitor listener。精确指定种类、无模糊卡的LevelEmitter会移除monitor listener，仅注册ItemWatcher，所以不接收该刷新。READ_WRITE→WRITE终端隐藏100铁，但阈值50的精确发信器可保留旧100；WRITE期间内部转运也按设计不发可读delta；切回READ仍可能维持旧值，直到同类型额外通知/重新配置。

**影响：** 库存可见性、红石控制及依赖其行为的自动化错误；未证明实际复制库存。**建议发布前修复**：在READ可见性变化时对本机内容发布正确前后可见增减或合法Watcher更新，保留同一Handler，考虑同步重入及provider活跃变化的双记账。现有权限策略测试未真实覆盖此通知链；需原生精确Watcher与游戏回归。

### 8.2 P1-02：同Grid后设备可能长期能源饥饿（条件可达，自动复现）

**位置：** `TransferScheduler.runTick:127–152`；`TransferPowerBudget.allowance/reserve:16–18,48–62`；控制器暂缓分支`269–270`。

**触发：** 整轮所有设备工作低于2ms，队列轮回后首位不变；首设备大作业每Tick消耗全部共享毛额度。其后设备每Tick可被调用，却永远先看到0额度。持续充值不会修复取用顺序。

**见证：** `ReleaseEnergyAuditTest:14` 在100个虚拟Tick中两设备各获100次工作片，首设备搬10000、后设备0，数量守恒/总实扣10000。不是Minecraft时间测量，也不是以“测试通过”认可饥饿。

**建议：** 发布前做局部共享额度消费者首位轮换，或明确验收并接受其业务影响。至少令完整便宜轮次的下一Tick不总由同一身份先消费；保留共享毛额度、次数及事务整体性。无需重写事务或新增玩家参数。增加完成/取消/不同Grid混排测试，避免为公平引入重复服务。

### 8.3 P1-03：第三方异常可污染上游网络（条件集成风险，不是本Mod新Bug）

**位置：** AE `NetworkInventoryHandler.injectItems:114–250`、`NetworkMonitor.injectItems:211–216/postChange:296–345`：入深度栈/递增计数与退出之间没有finally。

**触发/影响：** 外部Handler或同步监听者中途抛异常，原生深度状态可能残留；后续库存操作拒收、通知被抑制，ThreadLocal/静态GLOBAL_DEPTH还可能保留monitor→Grid引用直到重建/进程结束。本ModUNCERTAIN避免盲目重放，不能保证整个AE网恢复。具体作用范围取决于抛出位置及上游实例是否更换，真实恢复需测。

**建议：** 将异常后源/目标网络普通存取、Watcher通知及卸载引用纳入验收；保留日志/NBT、停止本机，不反射重置AE私有状态。若真实常用后端能触发，先协调固定上游修复/隔离策略，不能靠“本机已隔离”宣称可稳定发布该组合。

### 8.4 P2-01：原生网络容量统计遗漏（确定兼容差异）

AE `GridStorageCache.updateBytesInfo:477–511`仅处理TileDrive和TileChest；本Tile继承AENetworkInvTile，不进入容量、类型/元件状态统计。通用`buildNetworkStorage:286–295`仍注册真实Handler，故存取正常，不是库存丢失。建议游戏确认网络容量界面并公开兼容边界；不为此强行继承TileDrive或改AE源码。不把略过统计的CPU当等价优化收益。

### 8.5 P2-02：内部坏元件NBT不保证原始保全（上游/损坏输入边界）

`tile/CellInventoryNbt:57–61`验证外层ItemStack；`Tile.updateHandlers:525–526`调用原生构造。AE `CellInventory:94–102,384–405`可能先跳过不可解码/非正数量，再saveChanges改写NBT；后续`SourceCellAccess`无法追回原生已删除身份。ThE也有自己的load/save覆盖。建议受损存档先备份，不用正常快照验证宣称全部内部损坏可恢复；这是健康库存以外的条件边界，不制造新P0结论。

### 8.6 P2-03：GUI共享队列可被单连接占满（确定排队缺口）

`DriveGuiNetwork:27,64–75,192–232`有全局1024队列、每START Tick128条，offer失败静默忽略；每玩家4配置/64点击限频在出队后`Container:250–278`才执行。有效编码的旧/无效会话也能先占排队位置。内存有界，但其他玩家可延迟/丢请求；未见越权或复制路径。建议真实压力验收，必要时局部增加每连接有界入队额度/公平性；不用扩大为整个协议重构。

### 8.7 P2 热点与接受条件

| 项目 | 位置/证据 | 建议与风险 |
| --- | --- | --- |
| OFF仍解析及65槽推进 | Controller115–124/280–295，RoutingCycle105–125；自动解析见证 | 可以局部按8组元数据提前短路，收益有限；不阻止游戏验收 |
| 低电重复验证/大额能源探测 | Controller215–246/269；Budget34–40；AE EnergyGridCache413–425 | 先测复杂能源/目标，若确实热点再有界退避/复用安全观测，不跳过付款或拓扑 |
| 图见证与重复拓扑 | 第6.2节的具体路径 | 保留安全检查，实测证明重复成本后才合并紧邻冗余，修改风险高于调常量 |
| 每种类NBT/Watcher成本 | AE CellInventory330–381、NetworkMonitor320–339；源质saveChanges字节码 | 原生数据维护成本，先测63类型/多设备；不引入独立缓存掩盖 |
| 大额退款/慢外部Handler | Account134–149、AE EnergyGridCache306–358 | API合法但可能慢/overflow生命周期风险，保持一次退款，测峰值，不机械拆退款 |
| 有界图盲区/保守拒绝 | RouteGraph104–126/184–196、Controller883–958，新增成员见证 | 明确TTL及配置图边界；第三方共享库存环无法完整证明，不能宣称所有循环可检测 |
| GUI配置副本/NBT同步 | Container220–247/338–386 | 多人打开满内容元件时测网络/GC；已有变更发送及revision缓存，不先重写GUI |

P2列表中既有确定的额外工作，也有条件复杂度风险；没有一项仅凭代码行数/复杂性就认定必须优化。

## 9. 测试覆盖、遗漏与本轮验收

既有测试覆盖数量事务正常/部分/未知/回填、三类型身份/单位、真实AE2 CellInventory/NBT组件的共享Handler回归、路由/优先级/UUID迁移/图预算、能源浮点/结算/退款及共享额度、虚拟时间/FIFO、GUI纯策略和编解码、静态专服/可选类边界。许多Grid/元件/外部容器仍是契约替身；不能据此确认实际外部存储或完整Forge交互。

本轮新增3项非侵入审计见证：

1. `src/test/java/com/kyou/metransferdrive/transfer/ReleaseEnergyAuditTest.java`：完整便宜FIFO轮次的共享能源饥饿。
2. `src/test/java/com/kyou/metransferdrive/transfer/ReleaseRoutingAuditTest.java`：64个OFF绑定仍解析；新Grid成员不在旧正向见证中、重建后识别环。

这些测试记录现有边界，未来修复相应策略时应改变预期，不能把它们作为保留缺陷的要求。未增加普通JVM纳秒基准来冒充Minecraft性能。

最终运行`gradlew.bat --gradle-user-home .gradle-user-home build spotlessCheck checkstyleMain checkstyleTest --console=plain --offline`，**BUILD SUCCESSFUL**：331项JUnit（保留328项+3项审计见证），0失败/0错误/0跳过；Checkstyle主37文件、测试31文件均0问题；Spotless Apply/Check成功。Spotless用于新增测试排版，最终核对已有生产源码/配置指纹不变。构建成功是编译/打包与自动验证结果，不替代上述P1判断或GTNH验收。

**未覆盖的关键集成链：** 精确ItemWatcher权限切换、真实多人高延迟Container、完整三类型外部总线、实际IEnergyGridProvider和原生overflow持久化、异常后上游深度恢复、保护Mod/爆炸/快照掉落、独立区块磁盘保存、实际图成员变化及峰值MSPT。

## 10. 最小 GTNH 验收清单与收尾条件

| 编号 | 必做场景 | 可审计通过标准 |
| --- | --- | --- |
| G1 | 相同64元件：7台原生Drive、本机全OFF、本机转运；另测空机/8元件 | 分开记录节点/频道/闲置AE、原生统计差异、平均/峰值耗时、NBT/网络量；不给百分比预设结论 |
| G2 | READ/WRITE/READ_WRITE，精确等级发信器/终端；开GUI连续插拔三类元件 | 同一共享Handler；可见性及真实注入/提取符合模式；修复P1-01后Watcher及时一致；WRITE仍能内部转运 |
| G3 | 三类各完整、部分、拒收；源网同时读写，目标有原生元件和物品/流体/源质总线容器 | 逐种源/目标/已知held数量守恒；不抽源网其它元件；异常不重复发货；主动虚空单独标注 |
| G4 | 4096/百万/千万/一亿/>int同种，63小种类、64混合槽 | 不截断/溢出；真实保护额度不足自动分批，无漏处理/死循环；记录事务次数及调用峰值 |
| G5 | 指定多UUID/自动优先级、同Grid多接口、满仓切下目标；拆分合并、撤权限/卸载 | 去重不重复发送，余量精确；新周期按当前归属；失效安全停止，无强制加载 |
| G6 | 两/三台同类型候选成环、不同类型路径、新加入/上线设备 | 已确认环受阻、合法非环可继续；验证20Tick重建窗口及复杂共享库存边界，不宣称全检测 |
| G7 | 同源Grid两台巨量作业、持续充值、不同维度原生连接 | 共享总毛额度有效；修复/接受P1-02后各设备可进展；保护线附近暂停/补能续作，不保证其他设备不致断电 |
| G8 | 原生/石英纤维/无线/外部/无限供能，非1倍率；大额部分拒收退款 | 原生单位ceil及净费正确，overflow不重复退款，退款归原Grid；分别记录待机与搬运费用及退款峰值 |
| G9 | 在SIM后/提取后/注入后/退款时触发受控失效或异常 | 不重放、不凭held/请求数自动补偿；证据明确；源/目标网后续普通存取/Watcher与引用释放正常或有明确恢复步骤 |
| G10 | 正常退出/区块卸载/拆除/爆炸/快照取消、恢复封装再放置；备份存档受控硬中断 | 一次掉落、元件NBT/证据保留；重载隔离不自动重试；记录硬崩溃独立保存的已知限制 |
| G11 | GUI同时转运：Shift/数字键/拖放/双击/分页/关闭、两人删目标/改优先级；旧包/撤BUILD/超距/拆机 | 不错槽、不丢元件/复制/越权，接受值回显，过期请求拒绝；拥塞可恢复，不无限占主线程 |
| G12 | 工具全部成功/重复/失败、中英文；两配方NEI；有/无ThE独立服务端 | 普通右键不绑定；只反馈操作玩家；不错误加载客户端/可选类；新工具未绑定 |

计时须注明机器、JVM、整合包、设备/类型/目标数量、样本窗口及Profiler方法；至少记录平均与峰值、成功量、事务数和库存/能源delta。无需为了形式制造某个MSPT阈值，服务器可接受限值由实测及服主需求决定。

**发布决策：** 先处理P1-01；P1-02建议在公开RC前局部修复，若接受限制必须明确告知并验证预期规模。P1-03需对实际使用的外部后端完成异常健康验收，发现真实触发则暂停该组合发布。完成G1～G12并保存证据、没有未解释数量变化/重复能源或掉落/无限等待后，可以结束常规开发并发布稳定版，同时公开硬崩溃原子性、虚空和隐藏共享库存的边界。当前不需要再开启一轮一般转运重构。
