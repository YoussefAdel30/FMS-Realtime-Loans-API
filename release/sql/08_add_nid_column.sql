-- Add NID to an audit table that was created before the composite MSISDN + NID key,
-- then place NID immediately after MSISDN so SELECT * returns those two columns together.
-- Safe to run again: an existing NID column and an existing pair index are left in place,
-- and the column order is rebuilt.
--
-- A new installation should use 01_create_tables.sql and 02_create_indexes.sql instead.
-- Those scripts already create NID as NOT NULL, directly after MSISDN.
--
-- Override the schema before running in SQL*Plus or SQLcl. On the FMS host:
--   DEFINE schema_owner = FMSUSER

SET DEFINE ON
SET SERVEROUTPUT ON
WHENEVER SQLERROR EXIT SQL.SQLCODE
DEFINE schema_owner = FMSPRD6

PROMPT Adding NID to &schema_owner..MSISDN_ELIGIBILITY_AUDIT when it is missing

DECLARE
    l_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO l_count
      FROM all_tab_columns
     WHERE owner = UPPER('&schema_owner')
       AND table_name = 'MSISDN_ELIGIBILITY_AUDIT'
       AND column_name = 'NID';
    IF l_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT ADD (NID VARCHAR2(32 CHAR))';
        DBMS_OUTPUT.PUT_LINE('NID column added');
    ELSE
        DBMS_OUTPUT.PUT_LINE('NID column already exists');
    END IF;
END;
/

COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.NID IS
    'National id as text. Stored with the MSISDN as the blacklist lookup pair.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.REASONS IS
    'KO reason text, starting with ''Anti-fraud : ''. Empty when the decision is not KO.';

PROMPT Creating pair lookup index when it is missing

DECLARE
    l_count NUMBER;
BEGIN
    SELECT COUNT(*) INTO l_count
      FROM all_indexes
     WHERE owner = UPPER('&schema_owner')
       AND index_name = 'IDX_MSISDN_ELIG_PAIR_TS';
    IF l_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE INDEX &schema_owner..IDX_MSISDN_ELIG_PAIR_TS ON &schema_owner..MSISDN_ELIGIBILITY_AUDIT (MSISDN, NID, REQUEST_TIMESTAMP)';
        DBMS_OUTPUT.PUT_LINE('IDX_MSISDN_ELIG_PAIR_TS created');
    ELSE
        DBMS_OUTPUT.PUT_LINE('IDX_MSISDN_ELIG_PAIR_TS already exists');
    END IF;
END;
/

PROMPT Placing NID immediately after MSISDN

DECLARE
    PROCEDURE hide(p_column VARCHAR2) IS
        l_hidden VARCHAR2(3);
    BEGIN
        SELECT hidden_column INTO l_hidden
          FROM all_tab_cols
         WHERE owner = UPPER('&schema_owner')
           AND table_name = 'MSISDN_ELIGIBILITY_AUDIT'
           AND column_name = p_column;
        IF l_hidden = 'NO' THEN
            EXECUTE IMMEDIATE 'ALTER TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT MODIFY ' || p_column || ' INVISIBLE';
        END IF;
    END;

    PROCEDURE show(p_column VARCHAR2) IS
        l_hidden VARCHAR2(3);
    BEGIN
        SELECT hidden_column INTO l_hidden
          FROM all_tab_cols
         WHERE owner = UPPER('&schema_owner')
           AND table_name = 'MSISDN_ELIGIBILITY_AUDIT'
           AND column_name = p_column;
        IF l_hidden = 'YES' THEN
            EXECUTE IMMEDIATE 'ALTER TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT MODIFY ' || p_column || ' VISIBLE';
        END IF;
    END;
BEGIN
    -- REQUEST_ID stays first because it is the primary key and cannot be hidden.
    -- Every other column is hidden, then shown in the order SELECT * should use.
    hide('REQUEST_TIMESTAMP');
    hide('RESPONSE_TIMESTAMP');
    hide('MSISDN');
    hide('NID');
    hide('RESULT');
    hide('REASON_MASK');
    hide('REASONS');
    hide('BLACKLIST_VERSION');
    hide('PROCESSING_TIME_MS');
    hide('HTTP_STATUS');
    hide('API_INSTANCE');
    hide('INGESTED_AT');

    show('REQUEST_TIMESTAMP');
    show('RESPONSE_TIMESTAMP');
    show('MSISDN');
    show('NID');
    show('RESULT');
    show('REASON_MASK');
    show('REASONS');
    show('BLACKLIST_VERSION');
    show('PROCESSING_TIME_MS');
    show('HTTP_STATUS');
    show('API_INSTANCE');
    show('INGESTED_AT');
END;
/

PROMPT NID is now the column immediately after MSISDN.
PROMPT If the table is empty, tighten the column so it matches a fresh install:
PROMPT   ALTER TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT MODIFY (NID VARCHAR2(32 CHAR) NOT NULL);
