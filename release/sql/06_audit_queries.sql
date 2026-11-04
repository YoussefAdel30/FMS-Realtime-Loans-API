-- Operational queries for FMSPRD6.MSISDN_ELIGIBILITY_AUDIT.
-- Run as FMSPRD6 or as MSISDN_API (SELECT is granted).
-- Bind values are shown as SQL*Plus substitution variables. Replace them before use.
-- Timestamps stored by the application are UTC.

SET DEFINE ON
DEFINE schema_owner = FMSPRD6
DEFINE msisdn = 201066257228
DEFINE hours_back = 24

PROMPT Latest 50 API requests
SELECT request_id,
       request_timestamp,
       msisdn,
       nid,
       result,
       reason_mask,
       reasons,
       blacklist_version,
       http_status,
       processing_time_ms,
       api_instance,
       ingested_at
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
 ORDER BY request_timestamp DESC
 FETCH FIRST 50 ROWS ONLY;

PROMPT Requests for one MSISDN
SELECT request_timestamp, request_id, msisdn, nid, result, reasons, blacklist_version, http_status, api_instance
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
 WHERE msisdn = '&msisdn'
 ORDER BY request_timestamp DESC
 FETCH FIRST 100 ROWS ONLY;

PROMPT OK and KO counts for the recent window
SELECT result, COUNT(*) AS decisions
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
 WHERE request_timestamp >= SYSTIMESTAMP - NUMTODSINTERVAL(&hours_back, 'HOUR')
   AND result IN ('OK', 'KO')
 GROUP BY result
 ORDER BY result;

PROMPT Requests per minute for the last hour
SELECT TRUNC(request_timestamp, 'MI') AS minute_utc,
       COUNT(*) AS requests,
       SUM(CASE WHEN result = 'KO' THEN 1 ELSE 0 END) AS rejected
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
 WHERE request_timestamp >= SYSTIMESTAMP - INTERVAL '1' HOUR
 GROUP BY TRUNC(request_timestamp, 'MI')
 ORDER BY minute_utc;

PROMPT Requests per hour for the recent window
SELECT TRUNC(request_timestamp, 'HH24') AS hour_utc,
       COUNT(*) AS requests,
       SUM(CASE WHEN result = 'OK' THEN 1 ELSE 0 END) AS eligible,
       SUM(CASE WHEN result = 'KO' THEN 1 ELSE 0 END) AS ineligible
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
 WHERE request_timestamp >= SYSTIMESTAMP - NUMTODSINTERVAL(&hours_back, 'HOUR')
 GROUP BY TRUNC(request_timestamp, 'HH24')
 ORDER BY hour_utc;

PROMPT Recent rejection reasons
SELECT reasons, COUNT(*) AS rejected_requests
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
 WHERE result = 'KO'
   AND request_timestamp >= SYSTIMESTAMP - NUMTODSINTERVAL(&hours_back, 'HOUR')
 GROUP BY reasons
 ORDER BY rejected_requests DESC
 FETCH FIRST 50 ROWS ONLY;

PROMPT Recent database insertion activity
SELECT TRUNC(ingested_at, 'MI') AS ingested_minute_utc,
       COUNT(*) AS rows_inserted,
       MIN(request_timestamp) AS oldest_request,
       MAX(request_timestamp) AS newest_request
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
 WHERE ingested_at >= SYSTIMESTAMP - INTERVAL '2' HOUR
 GROUP BY TRUNC(ingested_at, 'MI')
 ORDER BY ingested_minute_utc;

PROMPT Daily audit volumes for the last 14 days
SELECT TRUNC(request_timestamp) AS day_utc,
       COUNT(*) AS requests,
       SUM(CASE WHEN result = 'KO' THEN 1 ELSE 0 END) AS rejected
  FROM &schema_owner..MSISDN_ELIGIBILITY_AUDIT
 WHERE request_timestamp >= TRUNC(SYSTIMESTAMP) - 14
 GROUP BY TRUNC(request_timestamp)
 ORDER BY day_utc;
