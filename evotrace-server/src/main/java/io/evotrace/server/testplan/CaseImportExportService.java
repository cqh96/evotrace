package io.evotrace.server.testplan;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用例 Excel 批量导入/导出（对标 MeterSphere 数据迁移）。
 * <p>导出包含模块路径、标题、类型、优先级、详细步骤、预期、标签；导入自动建模块树。</p>
 */
@Service
public class CaseImportExportService {

    private static final String[] HEADERS = {"模块路径", "标题", "用例类型", "优先级", "步骤", "预期结果", "标签"};

    private final JdbcTemplate jdbc;

    public CaseImportExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 导出某项目全部用例为 Excel 字节流。 */
    public byte[] export(Long projectId) {
        List<Map<String, Object>> cases = jdbc.queryForList("""
                WITH RECURSIVE mods AS (
                    SELECT id, COALESCE(parent_id, 0) AS parent, title, title AS path
                    FROM test_case WHERE project_id = ? AND node_type = 'MODULE'
                    UNION ALL
                    SELECT c.id, COALESCE(c.parent_id, 0), c.title, m.path || '/' || c.title
                    FROM test_case c JOIN mods m ON c.parent_id = m.id
                    WHERE c.node_type = 'MODULE'
                )
                SELECT tc.id, COALESCE(m.path, '') AS modPath, tc.title, tc.test_type AS "testType",
                       tc.priority, tc.steps, tc.tags
                FROM test_case tc LEFT JOIN mods m ON m.id = tc.parent_id
                WHERE tc.project_id = ? AND tc.node_type = 'CASE'
                ORDER BY modPath, tc.id
                """, projectId, projectId);

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("测试用例");
            Row header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                header.createCell(i).setCellValue(HEADERS[i]);
            }
            int r = 1;
            for (Map<String, Object> c : cases) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(str(c.get("modPath")));
                row.createCell(1).setCellValue(str(c.get("title")));
                row.createCell(2).setCellValue(str(c.get("testType")));
                row.createCell(3).setCellValue(str(c.get("priority")));
                String stepsHtml = str(c.get("steps"));
                StringBuilder steps = new StringBuilder();
                StringBuilder expected = new StringBuilder();
                try {
                    Object parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(stepsHtml, Object.class);
                    if (parsed instanceof List<?> list) {
                        for (Object o : list) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> step = (Map<String, Object>) o;
                            steps.append(str(step.get("step"))).append("\n");
                            expected.append(str(step.get("expected"))).append("\n");
                        }
                    } else {
                        steps.append(stepsHtml);
                    }
                } catch (Exception e) {
                    steps.append(stepsHtml);
                }
                row.createCell(4).setCellValue(steps.toString().stripTrailing());
                row.createCell(5).setCellValue(expected.toString().stripTrailing());
                row.createCell(6).setCellValue(str(c.get("tags")));
            }
            wb.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("导出用例失败: " + e.getMessage(), e);
        }
    }

    /** 从 Excel 字节流导入用例（自动建模块，返回新增条数）。 */
    @Transactional
    public Map<String, Object> importExcel(Long projectId, byte[] content) {
        int created = 0;
        int updated = 0;
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String modPath = cell(row, 0);
                String title = cell(row, 1);
                if (title.isBlank()) continue;
                String testType = cell(row, 2);
                String priority = cell(row, 3);
                String steps = cell(row, 4);
                String expected = cell(row, 5);
                String tags = cell(row, 6);

                String stepsJson = buildStepsJson(steps, expected);
                Long parentId = ensureModule(projectId, modPath);

                int existing = jdbc.queryForObject("""
                        SELECT count(*) FROM test_case WHERE project_id = ? AND node_type = 'CASE'
                        AND title = ? AND COALESCE(parent_id, 0) = COALESCE(?, 0)
                        """, Integer.class, projectId, title, parentId);
                if (existing > 0) {
                    jdbc.update("""
                            UPDATE test_case SET steps = ?, test_type = ?, priority = ?, tags = ?, updated_at = now()
                            WHERE project_id = ? AND node_type = 'CASE' AND title = ? AND COALESCE(parent_id, 0) = COALESCE(?, 0)
                            """, stepsJson, testType, priority, tags, projectId, title, parentId);
                    updated++;
                } else {
                    jdbc.update("""
                            INSERT INTO test_case(project_id, parent_id, title, node_type, test_type, priority, steps, tags)
                            VALUES (?, ?, ?, 'CASE', ?, ?, ?, ?)
                            """, projectId, parentId, title, testType, priority, stepsJson, tags);
                    created++;
                }
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("导入用例失败: " + e.getMessage());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("updated", updated);
        return out;
    }

    /** 根据 "模块/子模块" 路径逐级创建模块节点，返回叶子模块 id（空路径返回 null）。 */
    private Long ensureModule(Long projectId, String modPath) {
        if (modPath == null || modPath.isBlank()) return null;
        Long parentId = null;
        for (String seg : modPath.split("/")) {
            seg = seg.trim();
            if (seg.isBlank()) continue;
            List<Long> found = jdbc.queryForList(
                    "SELECT id FROM test_case WHERE project_id = ? AND node_type = 'MODULE' AND title = ? AND COALESCE(parent_id, 0) = COALESCE(?, 0)",
                    Long.class, projectId, seg, parentId);
            if (found.isEmpty()) {
                parentId = jdbc.queryForObject("""
                        INSERT INTO test_case(project_id, parent_id, title, node_type) VALUES (?, ?, ?, 'MODULE') RETURNING id
                        """, Long.class, projectId, parentId, seg);
            } else {
                parentId = found.get(0);
            }
        }
        return parentId;
    }

    private String buildStepsJson(String steps, String expected) {
        List<Map<String, Object>> list = new ArrayList<>();
        String[] sArr = steps.split("\n");
        String[] eArr = expected.isBlank() ? new String[0] : expected.split("\n");
        for (int i = 0; i < sArr.length; i++) {
            String s = sArr[i].trim();
            if (s.isEmpty()) continue;
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("step", s);
            step.put("expected", i < eArr.length ? eArr[i].trim() : "");
            list.add(step);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String cell(Row row, int idx) {
        Cell c = row.getCell(idx);
        if (c == null) return "";
        return switch (c.getCellType()) {
            case STRING -> c.getStringCellValue();
            case NUMERIC -> String.valueOf(c.getNumericCellValue()).replace("\\.0$", "");
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            default -> "";
        };
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}