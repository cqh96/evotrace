-- SQL 终端第二跳 SSH:跳板机 → 数据库机(内网,仅 SSH 可达)→ 本地转发数据库端口
ALTER TABLE sql_console_connection
    ADD COLUMN IF NOT EXISTS db_ssh_user     VARCHAR(128),
    ADD COLUMN IF NOT EXISTS db_ssh_password VARCHAR(256),
    ADD COLUMN IF NOT EXISTS db_ssh_key_path VARCHAR(512),
    ADD COLUMN IF NOT EXISTS db_ssh_port     INTEGER NOT NULL DEFAULT 22;
