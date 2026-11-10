-- Secondary indexes for the eligibility audit table.
-- Execute as the schema owner FMSPRD6 after 01_create_tables.sql.
--
-- The primary key index on REQUEST_ID is created with the table. It is required
-- for idempotent replay: a repeated insert of the same request id must not create
-- a second row.
--
-- Additional indexes are limited to the two access paths used by the operational
-- queries in 06_audit_queries.sql. Every extra index increases the cost of the
-- high-volume insert path, so none are created for low-frequency investigations
-- that can filter after a timestamp range scan.

SET DEFINE ON
WHENEVER SQLERROR EXIT SQL.SQLCODE
DEFINE schema_owner = FMSPRD6

PROMPT Creating lookup index for a single MSISDN history
-- Supports "requests for a particular MSISDN" ordered by time without sorting
-- the whole table. MSISDN is the leading column because that query is selective.
CREATE INDEX &schema_owner..IDX_MSISDN_ELIG_MSISDN_TS
    ON &schema_owner..MSISDN_ELIGIBILITY_AUDIT (MSISDN, REQUEST_TIMESTAMP);

PROMPT Creating time index for volume and recent-activity queries
-- Supports latest requests, per-minute counts, per-hour counts, and daily volume.
-- RESULT is not indexed. OK/KO counts in a time window use this index and then
-- filter RESULT. A separate RESULT index would be large and would slow inserts
-- more than it would help those bounded time queries.
CREATE INDEX &schema_owner..IDX_MSISDN_ELIG_REQ_TS
    ON &schema_owner..MSISDN_ELIGIBILITY_AUDIT (REQUEST_TIMESTAMP);

PROMPT Indexes created on &schema_owner..MSISDN_ELIGIBILITY_AUDIT.
