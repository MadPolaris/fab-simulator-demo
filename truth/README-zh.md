# 真源树 — fab-simulator-demo

本目录是晶圆厂仿真演示的**领域真源**，从源代码逆向提取
（场景 A：只读提取，没有用它生成过代码）。

## 这个 demo 是什么

晶圆 **Lot**（装在 FOUP 中的一批晶圆）被一条**可续跑的步骤队列**
驱动着走过各**设备区**（光刻、刻蚀、量测……共 10 个区）：
每完成一步就记下进度，中断后从断点继续，已完成的步骤不重复。
晶圆被量测（CD-SEM）、分类（PASS / BORDERLINE / FAIL / SCRAP），
然后路由——返工、报废、暂存或放行。某步骤失败时，
**OCAP**（失控处置预案）规则求值并在步骤内救回，队列照样前进。
跨 Lot 的事务在恢复后沿用原编号，不另开重复事务。

两层：`core/` 是设备仿真器（idle → busy → idle 的 actor，可注入故障）；
`web/` 是 Play 页面加完整 CQRS 栈（domain / application / infrastructure）。

## 如何阅读（自上而下，不需要工具）

| 目录 | 内容 |
|---|---|
| `tree.yaml` | 根清单：名称、版本、来源、完整索引 |
| `types.yaml` | **类型词汇表**——本树使用的 7 种记录类型 |
| `aggregates/` | 三个一致性边界：`fab-pipeline-run`、`lot`、`work-order` |
| `pipelines/` | fab 步骤词汇（步骤顺序、槽位、失败码） |
| `value-objects/` | 共享不可变领域类型（设备区、OCAP 预案、配置……） |
| `policies/` | 响应式领域规则（OCAP、分类、事务编号沿用、处置） |
| `read-models/` | Web UI 提供的查询（时间线、设备看板、事件流……） |
| `scenarios/` | 演示脚本（M3.5 宕机/OCAP 演示、标准巡览） |
| `tools/` | `validate-tree.py`——机器门禁（见下） |

每个目录有自己的 `README.md`。每个原子文件以
`kind` / `id` / `name` / `description` 开头——读这四行就知道它是什么。

## 约定

- **稳定 ID，绝不复制文本。** 交叉引用用 `id`（如 `aggregate: lot`、
  `event: step-completed`）。名字变了，id 不变。
- **一文件一类型化记录。** `kind` 来自 `types.yaml`；校验器强制每种 kind 的必填字段。
- **领域重的概念中英双语**（fab / OCAP 词汇），沿用本仓 `README-zh.md` 的先例。
- **零机器味。** 只为字节恒等或编译器记账存在的字段（排序键、序号、代码生成提示）
  不属于这里——那是编译器的事。

## 机器门禁

```bash
python3 truth/tools/validate-tree.py truth/
```

检查：每个文件可解析、kind/id 合法、id 全局唯一、所有交叉引用可解析、
每个聚合至少一个 slice、每个 policy 的触发事件都有 slice 产出。
领域真理由人定；工具只报告结构损坏。

## 这棵树不是什么

- 不是代码的第二份拷贝——它记录的是*领域事实*（步骤词汇、OCAP 预案、
  分类阈值、终态），不是实现。
- 不是从工作坊生成的——它从可运行代码提取，所以词汇是
  pipeline/slice/policy，而不是事件风暴便签。
- 代码没拍板的有歧义处，在 `EXTRACTION-NOTES.md` 里标 `[NEEDS CALL]`——
  树记录代码事实，不做仲裁。
