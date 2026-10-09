# 让 ChatGPT 生成步骤文件的提示词

把下面整段连同你的实验描述发给 ChatGPT，把它回复的 JSON 存为 `xxx.protocol.json` 后导入 App。App 会逐项校验，有错误不能开始。

```text
请把我描述的实验整理成一个 JSON 文件，只输出 JSON，不要任何解释或 Markdown 代码块标记。必须严格符合以下格式（lab-protocol-draft 0.1）：

{
  "format": "lab-protocol-draft",
  "format_version": "0.1",
  "protocol_id": "小写英文/数字/下划线/连字符，≤64 字符",
  "protocol_version": "1",
  "title": "实验标题",
  "description": "可选说明",
  "time_unit": "seconds",
  "run_start_anchor_id": "run_start",
  "anchors": [
    { "id": "run_start", "label": "实验开始", "create_on": { "event": "run_started" } },
    { "id": "某锚点", "label": "说明", "create_on": { "event": "step_confirmed", "step_id": "建立它的步骤 id" } },
    { "id": "某锚点", "label": "说明", "create_on": { "event": "manual" } }
  ],
  "steps": [
    { "id": "步骤 id", "title": "简短标题", "instruction": "具体操作说明", "completion": "manual_confirmation",
      "sampling_plan_ids": ["可选：本步骤期间进行的采样计划 id"],
      "not_before": { "anchor_id": "可选：锚点 id", "offset": 秒数 } }
  ],
  "sampling_plans": [
    { "id": "计划 id", "label": "说明", "anchor_id": "以哪个锚点为 T0", "offsets": [相对该锚点的秒数，严格递增],
      "capture_mode": "manual",
      "fields": [ { "id": "字段 id", "label": "显示名", "type": "decimal|integer|text", "unit": "单位" } ] }
  ],
  "free_observations": { "enabled": true, "step_association": "optional", "advances_step": false }
}

规则：
1. 所有 id 只用小写字母、数字、下划线、连字符，且各自唯一。
2. 第一个锚点必须是 run_start（点击开始那一刻）。“某操作完成后开始计时”就为该步骤建一个 step_confirmed 锚点；需要临场决定的计时起点用 manual 锚点。
3. 定时测量写成 sampling_plans，offsets 是相对锚点的秒数（例如每 5 分钟测一次共 1 小时：[300,600,...,3600]）。
4. “等待 X 分钟后再做下一步”写成该步骤的 not_before。
5. 每一步都由人手动确认完成；不要写任何脚本、公式或表达式。
6. 不确定的地方在 description 里说明，不要编造数值。

我的实验：
（在此描述实验）
```
