你是一名资深接口测试工程师。请根据以下接口定义，生成 3-6 条接口测试用例建议（JSON 数组），每条包含：
- title：用例标题
- method：HTTP 方法（与接口一致）
- path：请求路径
- valid：是否为正例（true/false）
- assertion：断言说明

接口：
方法 {method}  路径 {path}  摘要 {summary}
参数 {params}
请求体 {requestBody}
响应模型 {responseSchema}

请只输出 JSON 数组，形如 [{"title":"...","method":"GET","path":"/x","valid":true,"assertion":"..."}]，不要额外解释。