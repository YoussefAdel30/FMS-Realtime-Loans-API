-- MSISDN eligibility audit table.
-- Execute as the schema owner FMSPRD6, or as a DBA while connected with permission
-- to create objects in that schema.
--
-- The script does not name a tablespace. The table is created in the schema owner's
-- default tablespace. A DBA may add a TABLESPACE clause after review.
--
-- Override the schema before running in SQL*Plus or SQLcl:
--   DEFINE schema_owner = FMSPRD6

SET DEFINE ON
WHENEVER SQLERROR EXIT SQL.SQLCODE
DEFINE schema_owner = FMSPRD6

PROMPT Creating &schema_owner..MSISDN_ELIGIBILITY_AUDIT

CREATE TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT (
    REQUEST_ID          VARCHAR2(64 CHAR)    NOT NULL,
    REQUEST_TIMESTAMP   TIMESTAMP(3)         NOT NULL,
    RESPONSE_TIMESTAMP  TIMESTAMP(3)         NOT NULL,
    MSISDN              VARCHAR2(32 CHAR)    NOT NULL,
    NID                 VARCHAR2(32 CHAR)    NOT NULL,
    RESULT              VARCHAR2(16 CHAR)    NOT NULL,
    REASON_MASK         VARCHAR2(16 CHAR)    NOT NULL,
    REASONS             VARCHAR2(4000 CHAR),
    BLACKLIST_VERSION   VARCHAR2(64 CHAR)    NOT NULL,
    PROCESSING_TIME_MS  NUMBER(10)           NOT NULL,
    HTTP_STATUS         NUMBER(3)            NOT NULL,
    API_INSTANCE        VARCHAR2(128 CHAR)   NOT NULL,
    INGESTED_AT         TIMESTAMP(3)         DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT PK_MSISDN_ELIG_AUDIT PRIMARY KEY (REQUEST_ID),
    CONSTRAINT CK_MSISDN_ELIG_MASK CHECK (REGEXP_LIKE(REASON_MASK, '^[0-9A-F]{16}$')),
    CONSTRAINT CK_MSISDN_ELIG_HTTP CHECK (HTTP_STATUS BETWEEN 100 AND 599),
    CONSTRAINT CK_MSISDN_ELIG_ELAPSED CHECK (PROCESSING_TIME_MS >= 0)
);

COMMENT ON TABLE &schema_owner..MSISDN_ELIGIBILITY_AUDIT IS
    'Eligibility decisions audited by the MSISDN API. REQUEST_ID is the idempotency key.';

COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.REQUEST_ID IS
    'Application-generated request id. Primary key and replay idempotency key.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.REQUEST_TIMESTAMP IS
    'UTC timestamp when the API accepted the request.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.RESPONSE_TIMESTAMP IS
    'UTC timestamp when the API finished the decision.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.MSISDN IS
    'Subscriber number as text. Never store it as NUMBER.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.NID IS
    'National id as text. Stored with the MSISDN as the blacklist lookup pair.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.RESULT IS
    'Eligibility result configured by the API, normally OK or KO. INVALID, UNAVAILABLE, and ERROR are also written.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.REASON_MASK IS
    'Unsigned 64-bit source bitmask as 16 uppercase hexadecimal characters. Bit 63 is the first character''s high bit.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.REASONS IS
    'KO reason text, starting with ''Anti-fraud : ''. Empty when the decision is not KO.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.BLACKLIST_VERSION IS
    'Version parsed from the blacklist file name, normally YYYYMMDD.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.PROCESSING_TIME_MS IS
    'Elapsed API processing time in milliseconds, including audit enqueue wait.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.HTTP_STATUS IS
    'HTTP status returned to the caller.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.API_INSTANCE IS
    'api.instance-id of the JVM that handled the request.';
COMMENT ON COLUMN &schema_owner..MSISDN_ELIGIBILITY_AUDIT.INGESTED_AT IS
    'UTC timestamp when this row was committed by the Oracle writer.';

PROMPT Table &schema_owner..MSISDN_ELIGIBILITY_AUDIT created.
