package io.evotrace.server.sqlconsole;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQL 终端：通过 SSH 跳板连接内网数据库，执行 SQL 并返回表格化结果。
 * <p>SSH 会话按连接缓存（断线自动重连），JDBC 连接按次执行开合；
 * 单语句最多返回 {@link #MAX_ROWS} 行（超出标记 truncated），查询超时
 * {@link #QUERY_TIMEOUT_SECONDS} 秒兜底。</p>
 */
@Service
public class SqlConsoleService {

    private static final Logger log = LoggerFactory.getLogger(SqlConsoleService.class);
    private static final int MAX_ROWS = 500;
    private static final int QUERY_TIMEOUT_SECONDS = 60;
    private static final int SSH_CONNECT_TIMEOUT_MS = 10_000;

    private final JdbcTemplate jdbc;
    private final Map<Long, Tunnel> tunnels = new ConcurrentHashMap<>();

    public SqlConsoleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Tunnel(Session hop1, Session hop2, int localPort) {
        boolean alive() {
            return hop1.isConnected() && (hop2 == null || hop2.isConnected());
        }
    }

    /* ==================== 连接配置 CRUD ==================== */

    public List<Map<String, Object>> list() {
        // 不回传任何密码;仅回传"是否已设置"提示
        return jdbc.queryForList("""
                SELECT id, name, ssh_host AS "sshHost", ssh_port AS "sshPort", ssh_user AS "sshUser",
                       (ssh_password IS NOT NULL) AS "hasSshPassword",
                       (ssh_key_path IS NOT NULL) AS "hasSshKey",
                       db_type AS "dbType", db_host AS "dbHost", db_port AS "dbPort",
                       db_name AS "dbName", db_user AS "dbUser",
                       (db_password IS NOT NULL) AS "hasDbPassword",
                       db_ssh_user AS "dbSshUser", db_ssh_port AS "dbSshPort",
                       (db_ssh_password IS NOT NULL) AS "hasDbSshPassword",
                       (db_ssh_key_path IS NOT NULL) AS "hasDbSshKey",
                       created_at AS "createdAt", updated_at AS "updatedAt"
                FROM sql_console_connection ORDER BY id
                """);
    }

    @Transactional
    public Long create(Map<String, Object> body) {
        Long id = jdbc.queryForObject("""
                INSERT INTO sql_console_connection(name, ssh_host, ssh_port, ssh_user, ssh_password,
                        ssh_key_path, db_type, db_host, db_port, db_name, db_user, db_password,
                        db_ssh_user, db_ssh_password, db_ssh_key_path, db_ssh_port, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class,
                required(body, "name"), required(body, "sshHost"),
                intOf(body, "sshPort", 22), required(body, "sshUser"),
                strOf(body, "sshPassword"), strOf(body, "sshKeyPath"),
                strOf(body, "dbType") == null ? "postgres" : strOf(body, "dbType"),
                required(body, "dbHost"), intOf(body, "dbPort", 0),
                required(body, "dbName"), required(body, "dbUser"),
                required(body, "dbPassword"),
                strOf(body, "dbSshUser"), strOf(body, "dbSshPassword"), strOf(body, "dbSshKeyPath"),
                intOf(body, "dbSshPort", 22), strOf(body, "createdBy"));
        return id;
    }

    @Transactional
    public void update(Long id, Map<String, Object> body) {
        jdbc.update("""
                UPDATE sql_console_connection SET
                    name = COALESCE(?, name),
                    ssh_host = COALESCE(?, ssh_host),
                    ssh_port = COALESCE(?, ssh_port),
                    ssh_user = COALESCE(?, ssh_user),
                    ssh_password = COALESCE(?, ssh_password),
                    ssh_key_path = COALESCE(?, ssh_key_path),
                    db_type = COALESCE(?, db_type),
                    db_host = COALESCE(?, db_host),
                    db_port = COALESCE(?, db_port),
                    db_name = COALESCE(?, db_name),
                    db_user = COALESCE(?, db_user),
                    db_password = COALESCE(?, db_password),
                    db_ssh_user = COALESCE(?, db_ssh_user),
                    db_ssh_password = COALESCE(?, db_ssh_password),
                    db_ssh_key_path = COALESCE(?, db_ssh_key_path),
                    db_ssh_port = COALESCE(?, db_ssh_port),
                    updated_at = now()
                WHERE id = ?
                """,
                strOf(body, "name"), strOf(body, "sshHost"), body.get("sshPort") instanceof Number n ? n.intValue() : null,
                strOf(body, "sshUser"), strOf(body, "sshPassword"), strOf(body, "sshKeyPath"),
                strOf(body, "dbType"), strOf(body, "dbHost"), body.get("dbPort") instanceof Number n ? n.intValue() : null,
                strOf(body, "dbName"), strOf(body, "dbUser"), strOf(body, "dbPassword"),
                strOf(body, "dbSshUser"), strOf(body, "dbSshPassword"), strOf(body, "dbSshKeyPath"),
                body.get("dbSshPort") instanceof Number n ? n.intValue() : null, id);
        closeTunnel(id); // 配置变更后失效旧隧道
    }

    @Transactional
    public void delete(Long id) {
        jdbc.update("DELETE FROM sql_console_connection WHERE id = ?", id);
        closeTunnel(id);
    }

    /* ==================== 测试与执行 ==================== */

    /** 测试 SSH 握手:仅验证跳板可达与认证,不建隧道、不连数据库。
     *  传 id 时用已保存配置,未保存的字段用 overrides 覆盖(留空不覆盖)。 */
    public Map<String, Object> testSsh(Long id, Map<String, Object> overrides) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> cfg = new LinkedHashMap<>();
        try {
            if (id != null) {
                Map<String, Object> saved = jdbc.queryForMap("""
                        SELECT ssh_host AS "sshHost", ssh_port AS "sshPort", ssh_user AS "sshUser",
                               ssh_password AS "sshPassword", ssh_key_path AS "sshKeyPath"
                        FROM sql_console_connection WHERE id = ?
                        """, id);
                cfg.putAll(saved);
            }
            if (overrides != null) {
                for (Map.Entry<String, Object> e : overrides.entrySet()) {
                    Object v = e.getValue();
                    if (v != null && !String.valueOf(v).isBlank()) {
                        cfg.put(e.getKey(), v);
                    }
                }
            }
            String host = cfg.get("sshHost") == null ? "" : String.valueOf(cfg.get("sshHost"));
            if (host.isBlank()) {
                throw new IllegalArgumentException("请填写 SSH 主机");
            }
            Session session = openSshFromCfg(cfg);
            String user = String.valueOf(cfg.get("sshUser"));
            String remoteVersion = session.getServerVersion();
            session.disconnect();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("message", "SSH 连接成功(" + user + "@" + host + ",服务端 " + remoteVersion + ")");
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            return out;
        } catch (Exception e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            String msg = e.getMessage();
            log.warn("sql console ssh test failed: conn={} host={} error={}", id,
                    cfg.get("sshHost"), msg);
            if (msg != null && msg.contains("Auth fail")) {
                msg += "。排查:1) sshd 是否允许 PasswordAuthentication / KbdInteractiveAuthentication;"
                        + "2) 账号密码是否过期或被锁定;3) 若服务器用密钥登录,请配置「SSH 私钥」路径";
            }
            out.put("message", msg);
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            return out;
        }
    }

    /** 第一跳:按连接配置(sshHost/sshPort/sshUser/sshPassword/sshKeyPath)建立 SSH 会话。 */
    private Session openSshFromCfg(Map<String, Object> cfg) throws Exception {
        return openSsh(strOfCfg(cfg, "sshHost"), numOfCfg(cfg, "sshPort", 22),
                strOfCfg(cfg, "sshUser"), strOfCfg(cfg, "sshPassword"), strOfCfg(cfg, "sshKeyPath"));
    }

    /** 建立 SSH 会话(认证握手,不建隧道),参数显式,支持第二跳复用。 */
    private Session openSsh(String host, int port, String user, String password, String keyPath) throws Exception {
        JSch jsch = new JSch();
        if (keyPath != null && !keyPath.isBlank()) {
            jsch.addIdentity(keyPath);
        }
        Session session = jsch.getSession(user, host, port);
        if (password != null) {
            session.setPassword(password);
        }
        session.setConfig("StrictHostKeyChecking", "no");
        // 与 OpenSSH 客户端惯例一致:优先 keyboard-interactive(PAM/2FA 加固环境
        // 常只允许这种方式),再 password、publickey
        session.setConfig("PreferredAuthentications", "keyboard-interactive,password,publickey");
        session.connect(SSH_CONNECT_TIMEOUT_MS);
        return session;
    }

    private String strOfCfg(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private int numOfCfg(Map<String, Object> cfg, String key, int def) {
        return cfg.get(key) instanceof Number n ? n.intValue() : def;
    }

    /** 测试连接:SSH 握手 + 隧道 + 数据库 SELECT 1,分段报错便于定位。 */
    public Map<String, Object> test(Long id) {
        long t0 = System.currentTimeMillis();
        Map<String, Object> out = new LinkedHashMap<>();
        try (var ctx = connect(id)) {
            try (Statement st = ctx.connection().createStatement()) {
                st.setQueryTimeout(10);
                try (ResultSet rs = st.executeQuery("SELECT 1")) {
                    rs.next();
                    out.put("ok", true);
                    out.put("message", "连接成功(SSH + 隧道 + " + dbTypeOf(id) + ")");
                    out.put("elapsedMs", System.currentTimeMillis() - t0);
                    return out;
                }
            }
        } catch (ConnectPhaseException cpe) {
            out.put("ok", false);
            out.put("message", cpe.getMessage());
            log.warn("sql console connection test failed: conn={} error={}", id, cpe.getMessage());
        } catch (Exception e) {
            out.put("ok", false);
            String msg = e.getMessage();
            if (msg != null && msg.contains("Communications link failure")) {
                msg += "。数据库无响应:从跳板机验证可达性—— ssh 登录后执行 "
                        + "nc -z " + dbHostOf(id) + " " + dbPortOf(id)
                        + ";若数据库与跳板机同机且只监听 127.0.0.1,请把「数据库主机」改为 127.0.0.1";
            }
            out.put("message", "数据库连接失败: " + msg);
            log.warn("sql console connection test failed: conn={} error={}", id, msg);
        }
        out.put("elapsedMs", System.currentTimeMillis() - t0);
        return out;
    }

    private String dbHostOf(Long id) {
        try {
            return jdbc.queryForObject("SELECT db_host FROM sql_console_connection WHERE id = ?",
                    String.class, id);
        } catch (Exception e) {
            return "<dbHost>";
        }
    }

    private int dbPortOf(Long id) {
        try {
            Integer p = jdbc.queryForObject("SELECT db_port FROM sql_console_connection WHERE id = ?",
                    Integer.class, id);
            return p != null ? p : 3306;
        } catch (Exception e) {
            return 3306;
        }
    }

    /** 连接分段异常:标明失败发生在 SSH / 隧道 / 哪一段。 */
    private static class ConnectPhaseException extends Exception {
        ConnectPhaseException(String phase, String detail) {
            super(phase + ": " + detail);
        }
    }

    /**
     * 执行 SQL(可多条,按行尾分号拆分,顺序执行)。返回逐条结果:
     * {sql, columns, rows, rowCount, truncated, affectedRows, elapsedMs, error}
     */
    public List<Map<String, Object>> execute(Long id, String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("请输入 SQL");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        try (var ctx = connect(id)) {
            for (String statement : splitStatements(sql)) {
                results.add(executeOne(ctx.connection(), statement));
            }
        } catch (Exception e) {
            log.warn("sql console connect failed for conn {}: {}", id, e.getMessage());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("sql", sql);
            r.put("error", e instanceof ConnectPhaseException ? e.getMessage() : "数据库连接失败: " + e.getMessage());
            results.add(r);
        }
        return results;
    }

    private Map<String, Object> executeOne(Connection conn, String sql) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("sql", sql.trim());
        long t0 = System.currentTimeMillis();
        try (Statement st = conn.createStatement()) {
            st.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            boolean hasResultSet = st.execute(sql);
            r.put("elapsedMs", System.currentTimeMillis() - t0);
            if (hasResultSet) {
                try (ResultSet rs = st.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    List<String> columns = new ArrayList<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        columns.add(meta.getColumnLabel(i));
                    }
                    r.put("columns", columns);
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= MAX_ROWS) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.add(rs.getObject(i));
                        }
                        rows.add(row);
                    }
                    r.put("rows", rows);
                    r.put("rowCount", rows.size());
                    r.put("truncated", truncated);
                }
            } else {
                r.put("affectedRows", st.getUpdateCount());
            }
        } catch (Exception e) {
            r.put("elapsedMs", System.currentTimeMillis() - t0);
            r.put("error", e.getMessage());
        }
        return r;
    }

    /* ==================== SSH 隧道与 JDBC ==================== */

    private record Ctx(Tunnel tunnel, Connection connection) implements AutoCloseable {
        @Override public void close() throws Exception {
            if (connection != null) connection.close();
        }
    }

    /** 建立(或复用)SSH 隧道 + 打开 JDBC 连接;失败时重连隧道一次。 */
    private Ctx connect(Long id) throws Exception {
        Map<String, Object> cfg = jdbc.queryForMap("""
                SELECT ssh_host AS "sshHost", ssh_port AS "sshPort", ssh_user AS "sshUser",
                       ssh_password AS "sshPassword", ssh_key_path AS "sshKeyPath",
                       db_type AS "dbType", db_host AS "dbHost", db_port AS "dbPort",
                       db_name AS "dbName", db_user AS "dbUser", db_password AS "dbPassword",
                       db_ssh_user AS "dbSshUser", db_ssh_password AS "dbSshPassword",
                       db_ssh_key_path AS "dbSshKeyPath", db_ssh_port AS "dbSshPort"
                FROM sql_console_connection WHERE id = ?
                """, id);
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Tunnel tunnel = tunnel(id, cfg);
                Connection conn = openJdbc(tunnel.localPort(), cfg);
                return new Ctx(tunnel, conn);
            } catch (ConnectPhaseException e) {
                throw e; // 阶段明确(SSH/隧道)不重试,直接带诊断信息返回
            } catch (Exception e) {
                last = e;
                closeTunnel(id); // 隧道可能已死,重连一次
            }
        }
        throw last != null ? last : new IllegalStateException("连接失败");
    }

    private Tunnel tunnel(Long id, Map<String, Object> cfg) throws Exception {
        Tunnel cached = tunnels.get(id);
        if (cached != null && cached.alive()) {
            return cached;
        }
        tunnels.remove(id);
        Session hop1 = null;
        Session hop2 = null;
        try {
            // 第一跳:公网跳板机
            try {
                hop1 = openSshFromCfg(cfg);
            } catch (Exception e) {
                throw new ConnectPhaseException("SSH 连接失败", e.getMessage());
            }
            String dbSshUser = strOfCfg(cfg, "dbSshUser");
            if (dbSshUser != null && !dbSshUser.isBlank()) {
                // 第二跳:跳板机 → 数据库机 SSH(22 端口) → 在数据库机本地转发数据库端口
                int l1;
                try {
                    l1 = hop1.setPortForwardingL("127.0.0.1", 0,
                            strOfCfg(cfg, "dbHost"), numOfCfg(cfg, "dbSshPort", 22));
                } catch (Exception e) {
                    throw new ConnectPhaseException("第二跳 SSH 转发失败(跳板机→数据库机 " + strOfCfg(cfg, "dbHost") + ":" + numOfCfg(cfg, "dbSshPort", 22) + ")",
                            e.getMessage());
                }
                try {
                    hop2 = openSsh("127.0.0.1", l1, dbSshUser,
                            strOfCfg(cfg, "dbSshPassword"), strOfCfg(cfg, "dbSshKeyPath"));
                } catch (Exception e) {
                    throw new ConnectPhaseException("第二跳 SSH 认证失败(数据库机 " + dbSshUser + ")", e.getMessage());
                }
                int l2;
                try {
                    l2 = hop2.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", numOfCfg(cfg, "dbPort", 0));
                } catch (Exception e) {
                    throw new ConnectPhaseException("数据库端口转发失败(数据库机本地 " + numOfCfg(cfg, "dbPort", 0) + ")", e.getMessage());
                }
                Tunnel t = new Tunnel(hop1, hop2, l2);
                tunnels.put(id, t);
                log.info("sql console 2-hop tunnel up: conn={} localPort={}", id, l2);
                return t;
            }
            // 单跳:跳板机直接转发数据库地址
            int localPort;
            try {
                localPort = hop1.setPortForwardingL("127.0.0.1", 0,
                        strOfCfg(cfg, "dbHost"), numOfCfg(cfg, "dbPort", 0));
            } catch (Exception e) {
                throw new ConnectPhaseException("端口转发失败",
                        e.getMessage() + "(服务器 sshd 可能禁用了 AllowTcpForwarding)");
            }
            Tunnel t = new Tunnel(hop1, null, localPort);
            tunnels.put(id, t);
            log.info("sql console tunnel up: conn={} localPort={}", id, localPort);
            return t;
        } catch (Exception e) {
            if (hop1 != null) { try { hop1.disconnect(); } catch (Exception ignore) {} }
            if (hop2 != null) { try { hop2.disconnect(); } catch (Exception ignore) {} }
            throw e;
        }
    }

    private Connection openJdbc(int localPort, Map<String, Object> cfg) throws Exception {
        String type = String.valueOf(cfg.get("dbType"));
        String dbName = String.valueOf(cfg.get("dbName"));
        String url = switch (type) {
            case "mysql" -> "jdbc:mysql://127.0.0.1:" + localPort + "/" + dbName
                    + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=10000";
            default -> "jdbc:postgresql://127.0.0.1:" + localPort + "/" + dbName;
        };
        return DriverManager.getConnection(url, String.valueOf(cfg.get("dbUser")),
                String.valueOf(cfg.get("dbPassword")));
    }

    private void closeTunnel(Long id) {
        Tunnel t = tunnels.remove(id);
        if (t != null) {
            try { t.hop1().disconnect(); } catch (Exception ignore) {}
            if (t.hop2() != null) {
                try { t.hop2().disconnect(); } catch (Exception ignore) {}
            }
        }
    }

    private String dbTypeOf(Long id) {
        try {
            return jdbc.queryForObject("SELECT db_type FROM sql_console_connection WHERE id = ?",
                    String.class, id);
        } catch (Exception e) {
            return "postgres";
        }
    }

    /** 按分号拆分多条语句:跳过引号字符串、行注释与块注释内的分号,任意位置分号均生效。 */
    private List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char n = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (lineComment) {
                cur.append(c);
                if (c == '\n') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (c == '*' && n == '/') {
                    blockComment = false;
                    cur.append(c).append(n);
                    i++;
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    if (n == quote) { // 转义引号(如 '' / "" )
                        cur.append(n);
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                cur.append(c);
                continue;
            }
            if (c == '-' && n == '-') {
                lineComment = true;
                cur.append(c).append(n);
                i++;
                continue;
            }
            if (c == '/' && n == '*') {
                blockComment = true;
                cur.append(c).append(n);
                i++;
                continue;
            }
            if (c == ';') {
                String s = cur.toString().trim();
                if (!s.isEmpty()) out.add(s);
                cur.setLength(0);
                continue;
            }
            cur.append(c);
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }

    private String required(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("缺少必填项: " + key);
        }
        return String.valueOf(v).trim();
    }

    private String strOf(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private int intOf(Map<String, Object> body, String key, int def) {
        return body.get(key) instanceof Number n ? n.intValue() : def;
    }
}
