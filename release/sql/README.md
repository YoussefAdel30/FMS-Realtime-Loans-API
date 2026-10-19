# Oracle installation for the MSISDN eligibility audit

The audit table lives in the **FMSPRD6** schema. The running service connects as a separate account, **MSISDN_API**, and does not need DBA privileges.

Timestamps written by the application are UTC. The JDBC pool runs `ALTER SESSION SET TIME_ZONE = 'UTC'` on every connection.

## 1. Scripts that must be executed

| Order | Script | Account | DBA required |
|---|---|---|---|
| 1 | `01_create_tables.sql` | `FMSPRD6` | No, unless FMSPRD6 cannot create tables in its default tablespace |
| 2 | `02_create_indexes.sql` | `FMSPRD6` | No |
| 3 | `03_create_sequences.sql` | `FMSPRD6` | No. This file creates nothing |
| 4 | `04_create_grants.sql` section A | DBA | Yes, only if `MSISDN_API` does not already exist |
| 5 | `04_create_grants.sql` section B | `FMSPRD6` or a DBA | Section B does not require `SYSDBA` when run as FMSPRD6 |
| 6 | `05_verify_installation.sql` | `FMSPRD6`, a DBA, or `MSISDN_API` | No |

`06_audit_queries.sql` and `07_maintenance.sql` are operational examples. They are not part of installation.

`08_add_nid_column.sql` is only for a table that was created before the NID column existed. A new installation uses `01` and `02`, which already include `NID`. On a host whose schema owner is `FMSUSER`, set `DEFINE schema_owner = FMSUSER` in that file before running it.

Run the files with SQL*Plus or SQLcl so the `DEFINE` substitutions and `WHENEVER SQLERROR EXIT` behavior are honored:

```bash
sqlplus FMSPRD6@//oracle-host:1521/service @01_create_tables.sql
sqlplus FMSPRD6@//oracle-host:1521/service @02_create_indexes.sql
sqlplus FMSPRD6@//oracle-host:1521/service @03_create_sequences.sql
sqlplus / as sysdba @04_create_grants.sql
sqlplus FMSPRD6@//oracle-host:1521/service @05_verify_installation.sql
```

If `MSISDN_API` already exists, do not run the commented `CREATE USER` text. Run section B only.

To install into a different schema, edit the `DEFINE schema_owner` line in every script before execution. The application setting `oracle-audit.schema` must be the same name. The default in this repository is `FMSPRD6`.

## 2. What each account does

- **FMSPRD6** owns `MSISDN_ELIGIBILITY_AUDIT`. Use it for DDL, index changes, statistics, and retention.
- **MSISDN_API** is the JDBC user. It receives `CREATE SESSION`, `INSERT`, and `SELECT`. It does not receive `UPDATE`, `DELETE`, or any system privilege other than `CREATE SESSION`.
- A **DBA** is required only to create the runtime user and, if your standards require it, to assign a profile or a non-default tablespace.

## 3. Verify the installation

`05_verify_installation.sql` checks the table, the required columns, the primary key, both secondary indexes, the check constraints, and the `INSERT`/`SELECT` grants to `MSISDN_API`. Success prints:

```text
VERIFICATION OK for FMSPRD6.MSISDN_ELIGIBILITY_AUDIT and MSISDN_API
```

A missing object raises `ORA-20001` and the script exits non-zero.

## 4. JDBC URL

Set these environment variables for the service. Do not put the password in `application.yml`.

```bash
ORACLE_JDBC_URL=jdbc:oracle:thin:@//oracle-host:1521/service_name
ORACLE_USERNAME=MSISDN_API
ORACLE_PASSWORD=the-runtime-password
```

The matching YAML is:

```yaml
oracle-audit:
  enabled: true
  jdbc-url: ${ORACLE_JDBC_URL}
  username: ${ORACLE_USERNAME}
  password: ${ORACLE_PASSWORD}
  schema: FMSPRD6
  table: MSISDN_ELIGIBILITY_AUDIT
```

Use the service name form of the thin URL. A SID form (`jdbc:oracle:thin:@host:1521:SID`) also works when that is how the database is registered.

## 5. Test connectivity

From the application host, with the same JDK the service uses:

```bash
/opt/java/jdk-17/bin/java -cp /opt/msisdn-api/msisdn-api.jar org.springframework.boot.loader.launch.JarLauncher --debug
```

A simpler check is to start the service and read the log line `Oracle audit writer started`. Then, as `MSISDN_API`:

```sql
SELECT 1 FROM DUAL;
SELECT COUNT(*) FROM FMSPRD6.MSISDN_ELIGIBILITY_AUDIT;
```

`SELECT 1 FROM DUAL` proves the account can connect. The table count proves the grant is visible. The application itself only inserts; it does not query the audit table on the request path.

## 6. Confirm that audit rows are arriving

Send one eligibility request, wait for the local audit segment to rotate or for the process to flush, then run the latest-request query in `06_audit_queries.sql`. A row is visible only after the Oracle writer commits it. An Oracle outage does not stop the API; the segment stays on disk and is inserted when the database accepts connections again.

`INGESTED_AT` is the commit time. `REQUEST_TIMESTAMP` is when the API handled the call. A large gap means the writer was behind.

## 7. Investigate ingestion failures

1. Read `/opt/msisdn-api/logs/application/application.log` and search for `Oracle ingestion` or `Oracle audit batch failed`.
2. Read `GET /api/v1/system/status` with the API key. `oracle.status` is `UP`, `DEGRADED`, or `DOWN`. `oracle.lastError` is a truncated message with passwords removed.
3. Count files in `/opt/msisdn-api/logs/audit/completed`. Those are the disk backlog. They are not deleted until the Oracle checkpoint and the CSV checkpoint are both complete.
4. Read `/opt/msisdn-api/data/checkpoints/oracle/*.ckpt.json`. `complete: false` means that segment has not been fully inserted. `offset` is the byte position that will be replayed.
5. Quarantine files under `/opt/msisdn-api/logs/audit/quarantine` are corrupt lines or rows Oracle rejected for data reasons. A unique-key replay is not quarantined; it is skipped.
6. Confirm the runtime account was not locked and the wallet or URL was not changed. The pool uses `initializationFailTimeout` of `-1`, so a down database does not stop the JVM.

## 8. Retention and growth

The application never deletes Oracle rows. Retention is a DBA action described in `07_maintenance.sql`. At 500 requests per second the table grows by about 43 million rows per day. Plan disk and undo for that rate, gather statistics after the first large day, and move to monthly range partitions before the first retention delete. Deletes in that file are commented out on purpose.
