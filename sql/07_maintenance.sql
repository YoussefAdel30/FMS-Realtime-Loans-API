-- Maintenance examples for FMSPRD6.MSISDN_ELIGIBILITY_AUDIT.
--
-- WARNING: statements that delete or move production audit data are commented out.
-- Do not remove the comments until an approved retention decision exists.
-- Run destructive statements as FMSPRD6 or a DBA, never as MSISDN_API.
-- The runtime account cannot delete rows.
--
-- Take an export or a backup before deleting any production partition or row range.

SET DEFINE ON
DEFINE schema_owner = FMSPRD6

PROMPT Current table size. Run this while connected as the schema owner.
SELECT segment_name, bytes / 1024 / 1024 AS size_mb
  FROM user_segments
 WHERE segment_name IN (
       'MSISDN_ELIGIBILITY_AUDIT',
       'PK_MSISDN_ELIG_AUDIT',
       'IDX_MSISDN_ELIG_MSISDN_TS',
       'IDX_MSISDN_ELIG_REQ_TS'
   )
 ORDER BY segment_name;

PROMPT A DBA can use DBA_SEGMENTS with OWNER = 'FMSPRD6' when the schema owner cannot see that view.

PROMPT Row estimate and oldest timestamp. NUM_ROWS is from the last statistics gather.
SELECT num_rows, last_analyzed
  FROM all_tables
 WHERE owner = UPPER('&schema_owner')
   AND table_name = 'MSISDN_ELIGIBILITY_AUDIT';

SELECT MIN(request_timestamp) AS oldest_request,
       MAX(request_timestamp) AS newest_request,
       COUNT(*) AS exact_count
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT;

PROMPT Gather statistics after a large initial load or a retention change.
PROMPT Review the estimate percent with the DBA before using a higher sample.
-- EXEC DBMS_STATS.GATHER_TABLE_STATS(ownname => '&schema_owner', tabname => 'MSISDN_ELIGIBILITY_AUDIT', cascade => TRUE);

PROMPT Example retention delete. This is NOT executed by this script.
PROMPT A delete of a large range can generate substantial undo and redo.
PROMPT Prefer partitioning by month before the table becomes large, then drop old partitions.
-- DELETE FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
--  WHERE REQUEST_TIMESTAMP < TIMESTAMP '2026-01-01 00:00:00';
-- COMMIT;

PROMPT Example monthly range partition for a future rebuild. Do not run this against a live non-partitioned table.
-- ALTER TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT
--     MODIFY PARTITION BY RANGE (REQUEST_TIMESTAMP)
--     INTERVAL (NUMTOYMINTERVAL(1, 'MONTH'))
--     (PARTITION P_INIT VALUES LESS THAN (TIMESTAMP '2026-01-01 00:00:00'))
--     ONLINE;

PROMPT Rebuild a secondary index only when the DBA has measured fragmentation.
-- ALTER INDEX &schema_owner..IDX_MSISDN_ELIG_REQ_TS REBUILD ONLINE;

PROMPT Stop. Leave the data in place unless a change ticket names the exact row range or partition.
