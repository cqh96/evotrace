-- SQL 终端:SSH 跳板连接内网数据库并执行 SQL 查询
CREATE TABLE IF NOT EXISTS sql_console_connection (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    -- SSH 跳板配置
    ssh_host     VARCHAR(256) NOT NULL,
    ssh_port     INTEGER NOT NULL DEFAULT 22,
    ssh_user     VARCHAR(128) NOT NULL,
    ssh_password VARCHAR(256),
    ssh_key_path VARCHAR(512),
    -- 目标数据库配置(相对 SSH 服务器可达的内网地址)
    db_type      VARCHAR(16)  NOT NULL DEFAULT 'postgres', -- postgres | mysql
    db_host      VARCHAR(256) NOT NULL,
    db_port      INTEGER NOT NULL,
    db_name      VARCHAR(128) NOT NULL,
    db_user      VARCHAR(128) NOT NULL,
    db_password  VARCHAR(256) NOT NULL,
    created_by   VARCHAR(128),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
