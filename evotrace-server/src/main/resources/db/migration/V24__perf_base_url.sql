-- 压测任务支持自定义压测地址(创建时填写,运行时优先使用)
ALTER TABLE performance_test ADD COLUMN IF NOT EXISTS base_url VARCHAR(512);
