package io.evotrace.server.change;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Keeps the monthly RANGE partitions of {@code change_event} ahead of time.
 * <p>
 * Runs on the 1st of each month (configurable) and creates the partitions for
 * the current and next {@code months-ahead} months. Idempotent via a
 * {@code pg_class} existence check, so re-runs and overlaps with the initial
 * pre-created partitions (V7) are safe.
 */
@Component
public class PartitionMaintainer {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintainer.class);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy_MM");

    private final JdbcTemplate jdbc;

    public PartitionMaintainer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Value("${evotrace.partition.months-ahead:2}")
    private int monthsAhead;

    @Scheduled(cron = "${evotrace.partition.maintenance-cron:0 30 0 1 * *}")
    public void ensurePartitions() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        int created = 0;
        for (int i = 0; i <= monthsAhead; i++) {
            LocalDate m = monthStart.plusMonths(i);
            String name = "change_event_" + m.format(MONTH);
            Integer exists = jdbc.queryForObject(
                    "SELECT 1 FROM pg_class WHERE relname = ? AND relkind = 'r'", Integer.class, name);
            if (exists != null) {
                continue;
            }
            try {
                jdbc.execute("CREATE TABLE " + name + " PARTITION OF change_event"
                        + " FOR VALUES FROM ('" + m + "') TO ('" + m.plusMonths(1) + "')");
                created++;
            } catch (Exception e) {
                log.warn("failed to create partition {}: {}", name, e.getMessage());
            }
        }
        if (created > 0) {
            log.info("partition maintenance: created {} change_event partition(s) ahead", created);
        }
    }
}
