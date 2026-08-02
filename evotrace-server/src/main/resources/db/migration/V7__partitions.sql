-- V7: pre-create change_event monthly partitions (2026-08 .. 2027-01).
-- PartitionMaintainer keeps creating upcoming partitions automatically each month.
DO $$
DECLARE m date := '2026-08-01'::date;
BEGIN
    FOR i IN 1..6 LOOP
        IF to_regclass(format('change_event_%s', to_char(m, 'YYYY_MM'))) IS NULL THEN
            EXECUTE format(
                'CREATE TABLE change_event_%s PARTITION OF change_event
                 FOR VALUES FROM (%L) TO (%L)',
                to_char(m, 'YYYY_MM'), m, m + interval '1 month');
        END IF;
        m := m + interval '1 month';
    END LOOP;
END $$;
