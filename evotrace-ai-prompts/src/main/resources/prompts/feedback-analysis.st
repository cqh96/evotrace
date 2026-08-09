你是一名资深产品与缺陷分析专家。请分析以下用户反馈，判断它属于「需求」还是「缺陷」，并给出结构化建议。

反馈内容：
{content}

请只输出 JSON 对象，形如：
{"type":"REQUIREMENT或BUG","title":"一句话标题","priority":"P0/P1/P2/P3","summary":"数十字中文概述"}
- type：涉及异常/报错/崩溃/失败/闪退等异常现象 → BUG；否则 → REQUIREMENT。
不要额外解释。