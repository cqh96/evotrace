package io.evotrace.server.sqlconsole;

import io.evotrace.common.Result;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * SQL 终端 REST 入口:管理 SSH 跳板连接,并透过隧道执行 SQL。
 * 仅 ADMIN / OPS 可用(可访问生产内网数据库,权限从严)。
 */
@RestController
@RequestMapping("/api/v1/sql-console")
@PreAuthorize("hasAnyRole('ADMIN','OPS')")
public class SqlConsoleController {

    private final SqlConsoleService service;

    public SqlConsoleController(SqlConsoleService service) {
        this.service = service;
    }

    @GetMapping("/connections")
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(service.list());
    }

    @PostMapping("/connections")
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return Result.ok(Map.of("id", service.create(body)));
    }

    @PutMapping("/connections/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        service.update(id, body);
        return Result.ok(null);
    }

    @DeleteMapping("/connections/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok(null);
    }

    @PostMapping("/connections/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.ok(service.test(id));
    }

    @PostMapping("/connections/{id}/execute")
    public Result<List<Map<String, Object>>> execute(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> body) {
        return Result.ok(service.execute(id, body.get("sql") == null ? null : String.valueOf(body.get("sql"))));
    }
}
