# Kizuna Meridian Debug Addon

这是仅供管理员使用的调试库，模组 ID 为 `meridian_debug`。它读取已安装身体宿主的权威快照，不复制身体账本。

## 功能

- `meridian_debug:limb_lens`：手持时每 10 tick 显示准星实体的部位 HP、状态、总 HP、体力和真元；右键打印完整快照。
- `meridian_debug:aggro_link`：选择两个可攻击生物，持续覆盖其攻击目标；普通右键互相仇恨，潜行右键建立单向仇恨。
- `/meridian_debug damage watch on|off [targets]`：记录自己参与的双向伤害，包含来源、命中部位、原始与结算剂量和部位剩余 HP。部位伤害溢出时追加原始余量、来源系数和各接收部位的 HP 损失；全身无部位可承受的余量单独标注。日志读取同一事务的固定回执，即使图腾随后恢复血量也保留原始分配事实。
- `/meridian_debug heal <targets> [all|health|limbs|stamina|zhenyuan]`：按目标恢复；省略模式时执行 `all`。
- `/meridian_debug heal <targets> limb <部位> [数值]`：按 Human 身体的实际部位 ID 恢复。
- `/meridian_debug revive [targets]`：省略目标时复活自己的死态身体；复用 Human 数据中的复活费用（初值一年）和 Framework 健康事务。最低 10% HP、肌肉满疲劳，寿元不足仍扣到零并提示，活体拒绝。它直接复活身体，不替代原版玩家死亡界面的重生按钮。
- `/meridian_debug aggro <A> <B> [mutual|oneway]`：设置仇恨方向；`/meridian_debug aggro clear [targets]` 清除覆盖。
- `/meridian_debug fatigue set <targets> <组织 ID|标签|all> <比例>` / `fatigue clear`：按 Framework 事务设置或清零组织疲劳。标签含冒号，需加引号，例如 `fatigue set @s "human:leg_muscle" 0.5`；补全直接给出带引号的文本。
- `/meridian_debug technique learn|forget <targets> <功法 ID>`：直接操作目录功法；ID 按原版标识符读取，带命名空间，例如 `meridian_basic_techniques:light_step_volume`。`learn` 接受书、卷或式 ID，`forget` 接受式 ID。`technique cooldown clear` 清空冷却；`technique status` 查看当前施放阶段（WARMUP/ACTIVE/RECOVERY）、最近一次失败码、已学和冷却。
- `/meridian_debug leap watch on|off [targets]`：每次服务端付费起跳记一行。成功的格式为 `跃步 LEAP 落点=<地面类型> x y z，做功=[组织=焦耳, …]`；被拒的格式为 `跃步被拒：<原因>`。等待落地间隔不记。
- `/meridian_debug body <target>`：显示境界、真元、寿元、肢体力量和最高疲劳；过长详情写入游戏运行目录下的 `meridian/body-logs/`，聊天栏给出文件的绝对路径。
- `/meridian_debug qi set <targets> <数量>`：把全身经脉内腔覆盖为游离态真元，总量由 Framework 按各经脉可用体积分配；宿主拒绝时原样报告失败码。`heal ... zhenyuan` 走同一调试入口补满内腔。

## 命令与补全来源

| 命令参数 | 补全来源 |
| --- | --- |
| `damage watch on/off` | literal；`targets` 使用原版实体选择器 |
| `heal targets` | 原版实体选择器 |
| `revive [targets]` | literal；可选目标使用原版实体选择器 |
| `heal ... limb 部位` | 目标身体快照的 `BodyStructure.parts` |
| `heal ...` 模式 | literal：`all`、`health`、`limbs`、`stamina`、`zhenyuan` |
| `fatigue ... 组织` | 目标身体快照中的组织 ID 与标签；含冒号的值补全为带引号文本 |
| `leap watch` | literal：`on`、`off`；`targets` 使用原版实体选择器 |
| `body`、`qi set`、`technique status` | 原版实体选择器 |
| `technique ... 功法` | 当前已加载功法目录的书、卷和式 ID |

## 宿主接口

Debug 不用 Mixin，也不借用其他附属的包名。它只调用各宿主公开的调试入口和协议入口，这些入口都标注「调试用途，不进正式玩法」，权限检查留在本库命令层。

| 用途 | 入口 |
| --- | --- |
| 身体快照、疲劳、治疗、复活 | `meridian_health_host` 健康宿主协议入口 |
| 玩家真元覆盖与补满 | Human `HumanDebugHost.debugQi(player, DebugQi)` |
| 类人真元覆盖与补满 | Threatening `HumanoidHealthHost.debugQi(entity, DebugQi)` |
| 功法进度读写 | 功法库 `TechniqueHostEntrypoint` |
| 最近一次施放失败 | 功法库 `TechniqueFabric.CAST_OBSERVED` |
| 跃步日志 | 功法库 `TechniqueLeaps.addLeapListener` |

## 依赖

运行时硬依赖 Meridian Mod Framework、Human、Vanilla-Damage 和 Vanilla Humanoid Threatening；这些附属缺失时不提供降级路径。Java 17、Minecraft 1.20.1、Fabric Loader 0.16.10。

构建时 Framework、功法库、Human、Vanilla-Damage 与 Threatening 在 `settings.gradle` 中按 Framework 统一装配清单以源码组合（`kizunaWorkspace.includeSource`），版本取自 Framework 的版本目录；GameTest 复用 Framework 的 `full-assembly.gradle` 装入整套产物，未组合的其余仓库读取各自已构建的安装 JAR。

## 验证

```text
./gradlew test build :fabric:runGametest
```

`fabric/build.gradle` 让 `remapJar` 先删 `fabric/build/loom-cache/temp/modprocessing`。Loom 只按坐标复用这里的嵌套 JAR，SNAPSHOT 坐标不变时会把旧的 server JAR 打进发布 JAR。

`DebugCommandGameTest` 经真实命令树覆盖以下行为：
- 学会后可施放，忘记后按 `NOT_KNOWN` 拒绝；
- 清冷却后可立即再施放；
- 状态输出包含阶段和失败码；
- 一次跃步恰好记一行；
- 身体摘要包含境界和真元；
- 按标签设置疲劳后数值与设定一致；
- 每条新命令的 Tab 补全；
- 新生成的真炁卫道士可以 `qi set` 并补满。
