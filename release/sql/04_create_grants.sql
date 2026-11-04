-- Least-privilege grants for the runtime account.
--
-- Two different accounts are involved:
--   schema owner  FMSPRD6      owns the table and is used only for DDL and maintenance
--   runtime user  MSISDN_API   used by the Spring Boot JDBC pool
--
-- The application must not connect as FMSPRD6 and must not have DBA privileges.
--
-- Section A is a DBA action. It creates the runtime user if you choose to run it.
-- Review the password replacement and the quota before executing it. The password
-- below is a placeholder token, not a real secret. Replace CHANGE_ME before use
-- and do not commit the real password.
--
-- Section B is executed as FMSPRD6 or as a DBA. It grants only the privileges the
-- running service needs: connect, insert audit rows, and select them for
-- verification. UPDATE and DELETE are intentionally not granted.

SET DEFINE ON
WHENEVER SQLERROR EXIT SQL.SQLCODE
DEFINE schema_owner = FMSPRD6
DEFINE app_user = MSISDN_API

PROMPT ===== Section A: DBA only. Skip if &app_user already exists. =====
PROMPT Review and run the statements in this section manually if the user does not exist:
PROMPT   CREATE USER &app_user IDENTIFIED BY "CHANGE_ME" ACCOUNT UNLOCK;
PROMPT   ALTER USER &app_user DEFAULT TABLESPACE USERS QUOTA 0 ON USERS;
PROMPT The runtime user needs no tablespace quota because it does not create objects.

PROMPT ===== Section B: object grants. Run as &schema_owner or as a DBA. =====

GRANT CREATE SESSION TO &app_user;

GRANT INSERT, SELECT ON &schema_owner..MSISDN_ELIGIBILITY_AUDIT TO &app_user;

PROMPT Granted CREATE SESSION, INSERT, and SELECT on &schema_owner..MSISDN_ELIGIBILITY_AUDIT to &app_user.
PROMPT UPDATE and DELETE were not granted.
