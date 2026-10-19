-- Add NID to an audit table that was created before the composite MSISDN + NID key.
-- Run this once, as the schema owner, when 01_create_tables.sql has already been applied
-- and the table has no NID column.
--
-- A new installation should use 01_create_tables.sql and 02_create_indexes.sql instead.
-- Those scripts already create NID as NOT NULL and create IDX_MSISDN_ELIG_PAIR_TS.
--
-- Override the schema before running in SQL*Plus or SQLcl. On the FMS host:
--   DEFINE schema_owner = FMSUSER

SET DEFINE ON
WHENEVER SQLERROR EXIT SQL.SQLCODE
DEFINE schema_owner = FMSPRD6

PROMPT Adding NID to &schema_owner..MSISDN_ELIGIBILITY_AUDIT

ALTER TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT ADD (
    NID VARCHAR2(32 CHAR)
);

COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.NID IS
    'National id as text. Stored with the MSISDN as the blacklist lookup pair.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.REASONS IS
    'KO reason text, starting with ''Anti-fraud : ''. Empty when the decision is not KO.';

PROMPT Creating pair lookup index
CREATE INDEX &schema_owner..IDX_MSISDN_ELIG_PAIR_TS
    ON &schema_owner..MSISDN_ELIGIBILITY_AUDIT (MSISDN, NID, REQUEST_TIMESTAMP);

PROMPT NID column added.
PROMPT If the table is empty, tighten the column so it matches a fresh install:
PROMPT   ALTER TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT MODIFY (NID VARCHAR2(32 CHAR) NOT NULL);
