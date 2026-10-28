# 转运能源授权、结算与储能保护评估

日期：2026-10-28。固定环境：GTNH 2.9.0-RC-2 / AE2 `rv3-beta-1080-GTNH` / 可选 ThE `1.7.64-GTNH`。

## 1. 结论与范围

本轮替换第三阶段以来的“预扣不退款”运行路径。现在仍在真实提取前预付能源，正常返回时只按确认成功接收量结算，未使用部分向原能源 Grid 退款一次。增加同一实际源 Grid 在一个服务器工作 Tick 内共享的总预留额度及动态储能保护，不新增玩家配置。

- **源码确定**：不会以能源 SIM 成功代替 MOD 成功；授权只来自实际已付的完整计费单位。
- **正常 API 条件**：未提取、明确拒收、已回填及已知待回填数量不收取完整搬运费；已知成功量计费。
- **保守隔离**：库存结果未知、实扣结果未知、原 Grid 失效或退款结果未知时保留证据，不自动重扣、补发、退款或重放。
- **保护边界**：约束本 Mod 的耗电，不锁定原生电池，不限制其他设备，不承诺绝对防断电、跨 Grid 崩溃原子性或第三方供能实现可靠。
- 64 槽共享原生 Handler 的 P0 修复、目标标准 monitor、路由、循环图及事务回填机制保留；不改版本、配方、GUI 或性能配置。

## 2. 固定版本 API 证据

证据来自项目缓存的 `Applied-Energistics-2-Unofficial-rv3-beta-1080-GTNH-sources.jar`，SHA-256：`e1c6ec53c8f8d2e70c3cb79f095a32786cbb4b8449ebe8c040ce8c103706d9e8`。以下 AE2 路径为该 JAR 内路径；不以 master 证明版本行为。

| API / 实现 | 准确语义及设计影响 |
| --- | --- |
| `appeng/api/networking/energy/IEnergySource.java:21–28` | `extractAEPower` 返回实际提取量；SIM 不预留电池，必须检查真实 MOD 返回 |
| `appeng/util/Platform.java:1357–1405`，`poweredInsert` | 能源 SIM → 实际库存注入 → 按实际接收量 ceil 后 MOD 扣能。本 Mod 采用同样计费单位，保留事前真实预付，避免库存写入成功后才发现扣费不足 |
| `appeng/api/config/PowerMultiplier.java:18–31`；`EnergyGridCache.java:202–204` | CONFIG 在提取前乘倍率、返回时除倍率。新账目统一使用物理 AE：自行计算一次 CONFIG 倍率，实扣使用 ONE，退款不再乘倍率 |
| `appeng/api/networking/energy/IEnergyGrid.java:53–68` | **`injectPower` 返回网络已持有的溢出能源，不是退款余量。** 不能按返回值再次退款、存入本地余额或称为“未接收” |
| `appeng/me/cache/EnergyGridCache.java:306–358,469–484` | MOD 注入先填原生需求方及外部供能路径，剩余记入 `extra`。正的 overflow 返回仍表示能源已归网络所有；返回正常且有限非负时只确认一次 |
| `IEnergyGrid.java:70–82`；`EnergyGridCache.java:225–230,488–498` | stored / capacity 是估计值；stored 可能到超过90 Tick后才刷新，且不等同于当前所有可访问能源。不能只用它们授权扣电 |
| `EnergyGridCache.java:234–303` | SIM / MOD 可访问 `extra`、本地提供者及其它供能 Grid；无限能源标记在原生实现中直接返回请求量。使用有限请求的真实 SIM 辅助预算 |
| `EnergyGridCache.java:173–178,208–209` | 待机耗电为设备加频道名义耗电，实际按 CONFIG 扣取；保护线计算使用其物理 AE/Tick |

`injectPower` 文档建议避免超过10000AE的大量溢出，但它不是硬接收上限。本轮不制造分块退款重试循环；大额正常退款通过一次原生调用完成。原网容量通常已被预扣释放，但其它注入、供能链及拓扑变化仍可能产生 overflow。原 Grid 被删除时 overflow 可能丢失，这是原生 API 明示限制，退款确认不等于其能源已持久化。

## 3. 实现位置与调用流程

| 文件 / 方法 | 职责 |
| --- | --- |
| [TransferEnergy.java](src/main/java/com/kyou/metransferdrive/transfer/TransferEnergy.java:86) `charge / authorized / difference` | ceil 计费、按精确 IEEE 值计算已付单位、退款及额度向下舍入；原 `reserve` 只保留为旧策略回归比较，生产控制器不再调用 |
| [TransferPowerBudget.java](src/main/java/com/kyou/metransferdrive/transfer/TransferPowerBudget.java:15) `allowance / reserve` | 有限实时能源模拟、保护线、同实际 Grid 共享毛预留额度 |
| [TransferEnergyAccount.java](src/main/java/com/kyou/metransferdrive/transfer/TransferEnergyAccount.java:74) `reserve / settle` | 单批能源预付款和一次性退款；是现有数量事务的结算协作者，不是库存或可复用电池 |
| [TransferTransaction.java](src/main/java/com/kyou/metransferdrive/transfer/TransferTransaction.java:55) `move / retryReturn` | 沿用模拟、提取、注入、回填；已知结果调用 settle，异常调用 uncertain settlement；之后重试已知回填不再收费 |
| [AETransferController.java](src/main/java/com/kyou/metransferdrive/transfer/AETransferController.java:440) `reserveEnergy` | 捕获原 IGrid / IEnergyGrid，以 ONE 扣取物理 AE、向同一原生能源缓存退款；失去原身份不得改投新 Grid |
| [RoutedTransfer.java](src/main/java/com/kyou/metransferdrive/transfer/RoutedTransfer.java:28) `step` | 区分目标拒收与能源暂缓；能源不足保留当前候选和目标游标，后续 Tick 继续 |
| [TransferScheduler.java](src/main/java/com/kyou/metransferdrive/transfer/TransferScheduler.java:127) `runTick / clear` | SERVER END 工作片前、finally 及关服清理共享额度；跨维度共用同一实际 Grid 身份表 |

```text
Tick FIFO 工作片：次数/软时间准入
  → 既有源 Handler、拓扑、循环图、权限及实时数量检查
  → 目标 monitor 模拟接收 s
  → 保护线 + 真实能源 SIM + 同 Grid 剩余额度
  → 缩小数量 q；无完整计费单位：暂缓本片，保留作业
  → 先消耗本 Tick 毛额度，记录 DEBITING，真实扣取物理能源
  → 校验实际付款 P，记录 RESERVED，按 P 再缩量
  → 原数量事务：真实提取 → 实际注入 → 已知余量回填
  → 结果已知：按实际 delivered 结算，未使用 P 一次退款
     · 原 Grid / 缓存失效：REFUND_BLOCKED，保留账目并隔离
     · 退款正常返回：确认退款，清除能源待处理记录
     · 抛异常 / 非法返回：UNCERTAIN，不重复退款
  → 数量事务已安全结束才推进原多目标/种类游标
```

## 4. 实际成功量计费与数值边界

设 q 为本笔原生数量，u 为原生数量/名义AE（物品1、流体1000mB、源质1），M 为有限正的 CONFIG 倍率，P 为 MOD 实付的物理AE。

```text
N(q) = q/u + (q%u == 0 ? 0 : 1)       // long 商余 ceil，无 q+u-1 溢出
C(q) = roundUpDouble(N(q) × M)         // 物理 AE，覆盖精确 IEEE 倍率的乘积
q授权 = min(目标可接收量, INT_MAX, u × floor_exact(P/M))
C实收 = C(confirmedDelivered)
退款 = roundDownDouble(P - C实收)       // 仅在库存结果确定时
```

- 请求仍受原外部 int 容器桥接边界 `INT_MAX` 保护；long 库存可以跨 Tick 自动分批。能源额度通常会更早缩量，不按元件等级写死批次。
- 不足一个完整名义AE的实付不能授权部分流体免费/折价转运；例如实付0.999999…AE不能授权1mB，退款后暂缓。1000mB内非零的已收流体按1AE结算，符合原生 ceil 语义。
- M=1 的整数路径保持精确；其它倍率按其真实 IEEE double 值经 BigDecimal 计算。预留向上、授权/退款向下，防止未付款量被向上舍入。净费可能比公式多一个退款舍入单位；原生 double 电池自身仍存在表示精度限制。
- 若完整请求的费用溢出 double，先根据有限能源额度求可付整数单位，再计算更小请求；不把 Infinity、NaN、负数或超出请求的 MOD 返回当作有效付款。最小正倍率也不授权0物理AE付款。
- 物品、流体、源质继续走同一泛型事务及各自原生单位，未直接加载 ThE 类。
- 多目标按各笔**实际成功量分别 ceil**：例如1500mB、500mB分别成功，合计费用2+1=3AE，而单笔2000mB为2AE。这是原生计费粒度，不是未用预付款被重复收费。

M=1、足额授权、完整接收的基准费用：4096物品=4096AE，64000mB=64AE，4096源质=4096AE。两台设备或多目标不改变此单位；分笔 ceil 及第三方接收端自身行为另计。基础机器/元件待机耗电及源网普通读写路径未改变，目标仍为标准 monitor，无新增目标侧搬运费分支。

### 已知与未知结果

| 场景 | 能源结算 | 库存/路由处理 |
| --- | --- | --- |
| SIM 容量0 | 无 MOD 能源扣取 | 推进拒收目标，次数仍有界 |
| 实际提取0 / 少提取 | 只对实际已接收量计费，退还多预付部分 | 未提取部分仍在原元件 |
| 实际部分接收 / 完全拒收 | 按确认 delivered 计费，其余退款一次 | 原事务先回填；安全结束后只转余量到下一目标 |
| 回填少于请求，但返回合法 | 已确认成功量计费；未成功搬运的已知 held 不计完整搬运费 | `RECOVERABLE` 保留已知余量，禁止故障切换；后来回填不再次收费 |
| 提取、注入或回填抛异常 / 非法数量 | 保留预付款，不猜测可退款数 | 原 `UNCERTAIN` 隔离，不补发、不重复提取 |
| 原能源 Grid 消失/改变，且需要退款 | 不向新 Grid 转账，不假装已退款 | `REFUND_BLOCKED` 保留金额与未尝试证据，锁定人工核对 |
| 扣取或退款可能已执行后抛异常 | 保留请求、返回值及尝试标记，不重试 | 能源账目单独锁定，即使尚未提取库存也禁止新转运 |
| 原生退款返回正 overflow | 全额退款已获原生契约确认，不再次注入 | 网络持有 overflow；不是本机可支配余额 |
| 接收端主动虚空并报告接收成功 | 按其原生成功返回计费 | 尊重既有玩法；不是持久存储或严格无损的证明 |

## 5. 同源 Grid 每 Tick 动态储能保护

下列均为**内部固定策略**，没有新增 GUI 参数。真实TPS不影响Tick计数：100Tick名义为5秒。

符号：S=原生 stored 估计，C=本地最大储能估计，I=`getIdlePowerUsage() × M` 物理AE/Tick，A=本次有限真实能源SIM所返回的可提取量。

```text
R = C > 0 ? max(0.20C, min(100I, 0.50C)) : 100I
L = roundUpDouble(INT_MAX × M)；若溢出则饱和为 Double.MAX_VALUE
probe = saturatingAdd(R, max(L, S))
A = extractAEPower(probe, SIMULATE, ONE)
B单笔 = min(max(0, A-R), max(M, 0.10A))
B本Tick初值 = 第一次有效SIM得到的 B单笔
B允许 = min(B本Tick剩余, 当前重新计算的 B单笔)
```

L是单笔原生数量边界对应的物理费用。有限探测可能只得到实际可用能源的下界；不会据此放大授权。

每次在真实扣取之前从共享表扣除**请求预付的物理AE毛额**；实扣少于请求、退款、重新充电均不在同Tick补回额度。因此同一个稳定实际源Grid上，本Mod所有机器/事务的预扣请求之和不超过其首次有效额度，且每笔重新检查当前余量和保护线。下一服务器工作Tick重新测量。

选择依据：

1. 10%采用实时可访问能源A，避免 stale stored 漏算或夸大供能；既限制单笔，也作为全Grid同Tick总额度，不能各机器独立取10%。
2. 至少保留本地容量20%；另外尝试保留100Tick待机消耗。若待机需求过高，储备最多提高至本地容量50%，给小型但持续供电网络留下可用空间。此上限意味着小网络不保证完整5秒储备。
3. 允许至少一个名义AE的搬运机会，**仅在 A-R 足够时**。极小网络可因此超过10%的比例，但不能通过这一例外跨过可表示的保护线；这是避免整数计费永久饥饿的明确例外。
4. C=0、S=0不等于无电：外部供能真实SIM可提供A，此时使用待机储备；不假造远端电池的容量或承诺远端20%保护。
5. 有原生无限能源标记时R=0，额度仍有限并受数量保护：`B=min(L,A)`。仍需实际MOD付款，无限标记不能使未付能量授权。无该标记的非有限统计拒绝授权。

### 算例（M=1，合法原生API，省略其他设备耗电）

- C=S=1000AE，I=2AE/Tick：R=200，首笔/全Grid本Tick毛额最多100AE。请求100件实际只收30件：预付100、退款70、净费30；同Tick第二台机器仍无新增额度，下Tick按当前970AE重新测量。
- C=1000，当前可用205，I=0：只授权5件，留下200；到保护线后暂缓，补充能源且进入下个Tick后继续原候选。
- C=S=5，I=2：R=2.5，单次可付1AE（高于10%例外），剩4；当前只有3AE时保护线之上的0.5AE不足一个单位，不转运。
- 单种1亿件若C=S=10亿AE、I较低，第一Tick可授权1亿；若C=S=1亿，首Tick最多1000万，后续自动缩量，不能为清空元件穿透2000万保护线。无充电时可能长期保留余量，符合保护目标。

## 6. 暂缓、生命周期及不确定证据

- 源网络仍活跃但能源不足/额度耗尽时，`isDeferred` 保留当前种类和目标，控制器立即结束本片；下一次FIFO服务重查能源并继续，不重新等待用户60秒周期。周期仍只控制新作业启动。源网络真正离线时沿用原安全停止/周期重启机制，不承诺立即恢复旧游标。
- 完全拒收按原路由推进；部分成功且回填、能源结算均完成才能继续其它目标。任何未完成数量事务或能源账目都不能故障切换。
- 保留每设备每Tick最多2次尝试、1快照/4类型探测/8步推进，以及设备0.5ms/服务器跨维度2ms软时间。能源暂缓通常只做一次尝试后让出，已耗尽共享额度不再重复做能源SIM。FIFO提供工作片机会，但先取得额度的大作业可能长期占用同Grid供能；不承诺低能源时设备/种类之间的能源公平或慢原生方法可被抢占。
- `TransferEnergyAccount` 只保存单批证据；未完成时写入 `transferEnergy.version=1`：单位、倍率、请求/授权/确认接收数量、预扣请求/返回、费用、退款、overflow、尝试/确认标记和异常。正常结算清除该标签，不累积历史账本。
- DEBITING / RESERVED / REFUNDING 均调用原保存检查点。检查点仅标记NBT待保存，**不是磁盘刷写或跨Grid提交**。
- 重载时只要存在未完成能源标签，完整保留原始标签（包括损坏或未知版本）并隔离；不根据标签生成能源、退款或补偿。旧存档没有该标签则正常使用新策略，不追溯历史多扣能源。
- 能源单独不确定也纳入现有 `hasRecovery`：锁定元件插拔和配置、停止转运，拆除使用原机器恢复封装携带元件和全部NBT证据。数量恢复NBT只在数量事务确实pending时生成，能源-only失败不会用空库存身份生成虚假记录。
- 退款只针对原IGrid且其IEnergyGrid缓存仍相同；结束/隔离后释放运行时能源引用。共享额度表在服务器工作Tick开始/finally/关服清除，不永久缓存Grid，不强制加载区块。

人工处理沿用 [SAFETY_REVIEW.md](SAFETY_REVIEW.md) 的停机、备份、保存原始NBT、比对源元件/目标库存/供能状态及日志流程。`REFUND_BLOCKED` 表示未发起退款；`refundAttempted=true` 不能证明退款失败，`refundAcknowledged=true` 也不能证明磁盘已提交。无可靠证据时保持隔离；没有新增玩家领取能源、清账或自动解除隔离入口。

## 7. 自动验证与测量边界

完整构建命令：`gradlew.bat --gradle-user-home .gradle-user-home build spotlessCheck checkstyleMain checkstyleTest --console=plain --offline`；另执行 `spotlessApply`。**完整build成功；JUnit共328项（保留286项，新增42项），0失败/0错误/0跳过；Checkstyle主37文件、测试29文件均0问题，Spotless Apply/Check成功。**

新增测试实际执行生产 `TransferTransaction / RoutedTransfer / TransferEnergyAccount / TransferPowerBudget / TransferScheduler`，Grid身份、能源提供者和库存为确定性契约替身：

- 完整/部分/零接收、少提取、提取0、成功回填、已知回填失败后重试不重收、未知插入/回填结果禁止退款。
- 两目标30+70的真实数量及净费用100；100万、1000万、1亿、超过int请求的整数与流体单位；流体ceil、CONFIG倍率及小数实付。
- 向上预留/向下授权、nextDown边界、极大/极小倍率、long/int边界、非法实扣返回和退款返回。
- 20%保护线、5秒待机储备/50%上限、小型网络一个单位例外、stale stored、外部供能本地容量0、无限标记及非法统计。
- 两账目同Grid、同机多笔、实际FIFO调度器中两设备同Tick共享额度、退款不循环利用额度、耗尽后不重复SIM、下一Tick补能恢复原路由。
- 原Grid失效、扣取后异常、退款后异常、正overflow不重复退款、各预扣检查点NBT、损坏/未知NBT与原机器恢复封装。
- 既有共享Handler/三类型/数量守恒/循环图/GUI权限与网络边界测试全部保留。新增类也进入专用服务端/无ThE静态链接检查。

以上不等于真实 EnergyGridCache、世界、供能总线或第三方库存的运行验收；没有测量GTNH TPS/MSPT或宣称性能改善百分比。

## 8. 仍需 GTNH 集成验证的边界

1. 真实物品、流体、源质三类：终端与内部转运交替操作、部分/拒收/回填、CONFIG倍率、真实能源delta与待机耗电分开核对。
2. 同Grid多设备及跨维度原生连接：同Tick毛预留总量、充值恢复、FIFO与能源预算同时生效，保持原共享Handler身份。
3. 小型供电网络、巨大电池网络、能量接收器、石英纤维/无线或其它IEnergyGridProvider、原生无限供能，核对A、C、I实际语义与数值。
4. 网络合并/拆分/卸载恰逢SIM、预扣、注入或退款：不能把额度/退款错误移至新Grid。新实际Grid会重新测量，**不承诺历史Grid合并拆分后的总额度仍严格等于此前单一10%额度**。
5. 不同Grid共享底层供能者时额度不跨Grid汇总；外部电源输出速率、其他设备抽取及任意第三方回调可使SIM后供能改变。此机制不能对所有共享供能源池提供全局20%保护。
6. 原生退款已确认但overflow所在Grid随后删除、区块独立保存或服务器硬崩溃，仍可能损失能源或产生旧证据；不得用自动补偿掩盖不确定性。库存原子性限制也仍存在。
7. 超大/超小double及第三方实现违反原生返回契约：测试只证明本Mod的校验/隔离，不证明原生电池有任意精度、所有供应商都诚实或供能调用恒定耗时。
8. 能源与数量同时隔离时完整封装/放置/重载、正常关服及硬崩溃断点，验证不自动重放、不复制元件、不再收费/退款。
9. 实时能源探测可能遍历多个本地/外部能源提供者，退款也可能遍历需求方。原次数及软时间预算保留，但不能据此推断额外API调用零成本；需在大网络中测量调用次数/耗时及源网健康状态。

这些限制不要求立即扩大转运架构；需在真实整合包中取证后决定是否调整保护常数或补充人工工具。
