-- Verify the FMSPRD6 audit installation.
-- This script can be run as FMSPRD6, as a DBA, or as MSISDN_API.
-- It raises an application error when a required object, column, index,
-- constraint, or privilege is missing. A successful run prints VERIFICATION OK.

SET DEFINE ON
SET SERVEROUTPUT ON
WHENEVER SQLERROR EXIT SQL.SQLCODE
DEFINE schema_owner = FMSPRD6
DEFINE app_user = MSISDN_API

DECLARE
    PROCEDURE require(p_condition BOOLEAN, p_message VARCHAR2) IS
    BEGIN
        IF NOT p_condition THEN
            RAISE_APPLICATION_ERROR(-20001, p_message);
        END IF;
    END;

    FUNCTION table_exists RETURN BOOLEAN IS
        l_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO l_count
          FROM all_tables
         WHERE owner = UPPER('&schema_owner')
           AND table_name = 'MSISDN_ELIGIBILITY_AUDIT';
        RETURN l_count = 1;
    END;

    FUNCTION column_exists(p_column VARCHAR2) RETURN BOOLEAN IS
        l_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO l_count
          FROM all_tab_columns
         WHERE owner = UPPER('&schema_owner')
           AND table_name = 'MSISDN_ELIGIBILITY_AUDIT'
           AND column_name = p_column;
        RETURN l_count = 1;
    END;

    FUNCTION index_exists(p_index VARCHAR2) RETURN BOOLEAN IS
        l_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO l_count
          FROM all_indexes
         WHERE owner = UPPER('&schema_owner')
           AND index_name = p_index;
        RETURN l_count = 1;
    END;

    FUNCTION constraint_exists(p_constraint VARCHAR2) RETURN BOOLEAN IS
        l_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO l_count
          FROM all_constraints
         WHERE owner = UPPER('&schema_owner')
           AND constraint_name = p_constraint;
        RETURN l_count = 1;
    END;

    FUNCTION privilege_exists(p_privilege VARCHAR2) RETURN BOOLEAN IS
        l_count NUMBER;
    BEGIN
        SELECT COUNT(*) INTO l_count
          FROM all_tab_privs
         WHERE owner = UPPER('&schema_owner')
           AND table_name = 'MSISDN_ELIGIBILITY_AUDIT'
           AND grantee = UPPER('&app_user')
           AND privilege = p_privilege;
        RETURN l_count = 1;
    END;
BEGIN
    require(table_exists, 'Table &schema_owner..MSISDN_ELIGIBILITY_AUDIT does not exist');
    require(column_exists('REQUEST_ID'), 'Missing column REQUEST_ID');
    require(column_exists('REQUEST_TIMESTAMP'), 'Missing column REQUEST_TIMESTAMP');
    require(column_exists('RESPONSE_TIMESTAMP'), 'Missing column RESPONSE_TIMESTAMP');
    require(column_exists('MSISDN'), 'Missing column MSISDN');
    require(column_exists('NID'), 'Missing column NID');
    require(column_exists('RESULT'), 'Missing column RESULT');
    require(column_exists('REASON_MASK'), 'Missing column REASON_MASK');
    require(column_exists('REASONS'), 'Missing column REASONS');
    require(column_exists('BLACKLIST_VERSION'), 'Missing column BLACKLIST_VERSION');
    require(column_exists('PROCESSING_TIME_MS'), 'Missing column PROCESSING_TIME_MS');
    require(column_exists('HTTP_STATUS'), 'Missing column HTTP_STATUS');
    require(column_exists('API_INSTANCE'), 'Missing column API_INSTANCE');
    require(column_exists('INGESTED_AT'), 'Missing column INGESTED_AT');
    require(index_exists('PK_MSISDN_ELIG_AUDIT'), 'Missing primary key index PK_MSISDN_ELIG_AUDIT');
    require(index_exists('IDX_MSISDN_ELIG_MSISDN_TS'), 'Missing index IDX_MSISDN_ELIG_MSISDN_TS');
    require(index_exists('IDX_MSISDN_ELIG_PAIR_TS'), 'Missing index IDX_MSISDN_ELIG_PAIR_TS');
    require(index_exists('IDX_MSISDN_ELIG_REQ_TS'), 'Missing index IDX_MSISDN_ELIG_REQ_TS');
    require(constraint_exists('PK_MSISDN_ELIG_AUDIT'), 'Missing primary key constraint');
    require(constraint_exists('CK_MSISDN_ELIG_MASK'), 'Missing reason mask check');
    require(constraint_exists('CK_MSISDN_ELIG_HTTP'), 'Missing HTTP status check');
    require(constraint_exists('CK_MSISDN_ELIG_ELAPSED'), 'Missing elapsed-time check');
    require(privilege_exists('INSERT'), 'MSISDN_API is missing INSERT');
    require(privilege_exists('SELECT'), 'MSISDN_API is missing SELECT');
    DBMS_OUTPUT.PUT_LINE('VERIFICATION OK for &schema_owner..MSISDN_ELIGIBILITY_AUDIT and &app_user');
END;
/

PROMPT Connectivity probe. Run this while connected as the runtime user:
PROMPT   SELECT 1 FROM DUAL;
PROMPT   INSERT INTO &schema_owner..MSISDN_ELIGIBILITY_AUDIT (
PROMPT       REQUEST_ID, REQUEST_TIMESTAMP, RESPONSE_TIMESTAMP, MSISDN, NID, RESULT,
PROMPT       REASON_MASK, REASONS, BLACKLIST_VERSION, PROCESSING_TIME_MS, HTTP_STATUS, API_INSTANCE)
PROMPT   SELECT 'verify-' || TO_CHAR(SYSTIMESTAMP, 'YYYYMMDDHH24MISSFF3'),
PROMPT          SYSTIMESTAMP, SYSTIMESTAMP, '201000000000', '29901011234567', 'OK',
PROMPT          '0000000000000000', NULL, 'verify', 0, 200, 'verify'
PROMPT     FROM DUAL
PROMPT    WHERE NOT EXISTS (
PROMPT          SELECT 1 FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
PROMPT           WHERE REQUEST_ID = 'verify-skip');
PROMPT Review the inserted verification row and delete it as FMSPRD6 if it should not remain.
