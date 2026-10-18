# inbox/ — 工作坊产物重建（reconstructed）

> **声明**：本目录是**场景 A 重建**——从未开过真实工作坊。这里的 13 个产物
> 是依据 `truth/` 树中已有的领域知识**反推**出来的（"如果开过工作坊，每个
> 阶段会产出什么"），不是真实便签的格式化。内容只取自树中已有事实，
> 不编造；凡反推中需要解读的地方，以树为准，树没有的不写。
>
> 术语按晶圆厂 floor 行话：Lot / FOUP / recipe / track-in / track-out /
> 量测 / OCAP / 返工 / 报废 / 暂存 / 工艺路线（POR）。

## 13 个产物（一阶段一产物，目录序号即规范顺序）

| 目录 | 产物 | 内容 |
|---|---|---|
| `01-event-flow/` | 事件流 | 领域事件序列。每条事件带 `sequence`——它在业务叙事中的位置，**问题空间**的描述，不是机器计数器 |
| `02-commands/` | 命令与触发 | 命令、角色、外部系统（量测设备/工艺设备）、命令→事件因果链 |
| `03-aggregates/` | 聚合清单 | 聚合边界划分：Fab 流水线运行、晶圆 Lot、工单 |
| `04-subdomains/` | 子域划分 | **本 demo 跳过**：三个聚合同属"Fab 制造执行"一个限界上下文，无真实子域可分（见下） |
| `05-context-map/` | 上下文映射 | **限界上下文级别**的关系：制造执行上下文 ↔ 设备上下文（通知必须确认的协作契约）、工单上游 |
| `06-aggregate-detail/` | 聚合细化 | 聚合自身：状态槽位、字段、值对象定义（设备区、晶圆分类、OCAP 预案……） |
| `07-aggregate-relations/` | 聚合间关系 | **聚合级别**的引用：哪个聚合引用哪个、基数、业务含义（问题空间语言，不写 foreignKey） |
| `08-message-detail/` | 消息字段细化 | 事件/命令的字段定义 |
| `09-lineage/` | 字段血缘 | CD 量测→分类→处置、晶圆子 Lot 血缘、事务编号、工艺路线位置 |
| `10-policies/` | 策略定义 | **本 demo 无独立便签**：5 张策略卡片已是其结构化 policy atom（`policies/*.policy.yaml`）的浓缩复述，内容被 atom 完全覆盖；且工具链无法 roundtrip `kind: note + type: policy` 的便签（decompose 会把它强制转成 policy atom）。卡片原文逐字保留在对应 atom 的 `payload.workshopCard` 里 |
| `11-stories/` | 用户故事 | 从 M3.5 / fab-demo-tour 场景反推的用户故事 |
| `12-use-cases/` | 用例 | 前置条件、步骤、扩展 |
| `13-read-models/` | 读模型 | 定义含字段 + 字段来源（血缘） |

### #5 / #6 / #7 分工（不要混）

- **#5 上下文映射**：站在**限界上下文**的高度——"我们这个上下文跟谁协作"。
  本 demo：Fab 制造执行上下文 ↔ 设备上下文（下发工艺指令 / 上报量测与故障，
  通知必须确认）；工单是上游触发器。
- **#6 聚合细化**：站在**聚合内部**——状态槽位有哪些、字段什么类型、值对象长什么样。
- **#7 聚合间关系**：站在**聚合之间**——A 聚合引用 B 聚合，基数是什么
  （1:N / N:1 / 1:1），一句话业务含义。例如"一次运行驱动多个 Lot 推进，
  通知必须确认（1:N）"、"母 Lot 切分出多个子 Lot（1:N）"、"一个工单对一次运行（1:1）"。

### 到 truthsrc v0.3 阶段码的映射

| truthsrc v0.3 阶段 | 本目录 |
|---|---|
| `eventStorming` | `01-event-flow/` |
| `commandStorming` | `02-commands/`（含 policy 萌芽） |
| `aggregateDiscovery` | `03-aggregates/` |
| `subdomainExploration` | `04-subdomains/`（本 demo 无，跳过） |
| `contextMapping` | `05-context-map/` |
| `refining` | `06-aggregate-detail/`、`07-aggregate-relations/`、`08-message-detail/`、`09-lineage/` |
| （`commandStorming` 的 policy 面） | `10-policies/` |
| `storyMapping` | `11-stories/` |
| flow 模型（`use-cases.yaml`） | `12-use-cases/` |
| readModel 面 | `13-read-models/` |

### 关于 sequence 的说明

- `01-event-flow/` 中每条事件的 `sequence` **保留**：它是"事件在业务叙事中的位置"，
  问题空间的描述（用户裁定：去机械留语义）。
- 目录序号（01–13）即规范顺序；**不做跨文件连续重编号**——那是编译器的事，
  不进树。

## 便签格式（truthsrc v0.4，2026-10-18 迁移）

```yaml
# ═══ truthsrc atom — hand-editable; contract: SCHEMA.md ═══

kind: note
type: event            # event | command | role | externalSystem | aggregate | entity | relation | field | story | readModel
name: RunStarted
origin:                # 便签在工作坊板上的位置（冻结，不手工重编号）
  stage: eventStorming
  board: reconstructed
  seq: 1
payload:               # 工作坊自己的 JSON，verbatim，字节级保留
  {
    "type": "event",
    "name": "RunStarted",
    "name_zh": "流水线启动",
    "description": "<业务描述>",
    "info": {"timeline": "fab-pipeline", "sequence": 2}
  }
```

文件名由工具链的 decompose 规则决定：`<type>-<阶段码>-<slug(name)>-<seq>.yaml`
（如 `event-es-run-started-1.yaml`）；`origin.seq` 在 `(stage, board)` 内唯一，
0..n-1 连续由编译器 densify，手工只保证唯一不保证连续。

类型映射（pilot 方言 → truthsrc NOTE_TYPES，原文写进 `payload.info.pilotType`）：
`valueObject`/`aggregateState` → `entity`，`aggregateRelation`/`lineage` → `relation`，
`messageFields` → `field`，`useCase` → `story`。

## 校验

truthsrc 工具链（`zhongdj/metarch-truthsrc` 的 checkout 里执行）：

```bash
node bin/truthsrc.js validate --tree <truthDir>    # 0 hard 错误；notice 仅作标注
node bin/truthsrc.js roundtrip --tree <truthDir>   # compile∘decompose∘compile 字节恒等
```

事件便签要求：`payload.description` 非空（ES1）、`payload.info.timeline` 存在（ES2）；
`01-event-flow/` 每条事件的 `info.sequence` 即它在业务叙事中的位置（问题空间，
用户裁定：去机械留语义）。

## 角色—权限矩阵（02 × 13，不另起产物）

`02-commands/` 与 `13-read-models/` 使用**同一套角色词汇**（相同 id）；
对应关系显式写出，即一张完整权限矩阵：

| 角色 id | 名称 | 可签发命令 `can_issue` | 可查看读模型 `can_view` |
|---|---|---|---|
| `process-engineer` | 工艺工程师 | create-work-order、start-pipeline-run、merge-sublot | equipment-board、event-feed、pipeline-timeline、recovery-status、scenario-ledger |
| `demo-presenter` | 演示者 | inject-crash | equipment-board、event-feed、pipeline-timeline、recovery-status、scenario-ledger |
| `system` | 系统 | handle-ocap、record-measurement、classify-wafer、split-sublot、scrap-wafers、hold-wafers | （无——系统不看界面） |

- 角色便签（`02-commands/role-*.yaml`）同时列出 `can_issue` 与 `can_view` 两面；
  读模型便签带 `visible_to: [role ids]`。
- **诚实声明**：本演示代码里没有显式 RBAC，以上是重建时的最小假设——
  工艺工程师（建工单、启动）、演示者（点宕机按钮）与自动执行的系统。
  演示 UI 无登录，读模型谁打开谁看；矩阵按"签发"区分，不虚构"查看"限制。
