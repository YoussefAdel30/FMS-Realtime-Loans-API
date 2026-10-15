# Manual testing guide

Run these tests on a non-production host. Tests 14, 15, 16, and 17 stop dependencies or the process. Do not run them against production.

Record the date, the JAR checksum, the blacklist version, and the pass or fail result for each test.

## Test 1 — Verify Java installation

**Objective.** Confirm the process will run on Java 17.

**Prerequisites.** JDK 17 is installed at `/opt/java/jdk-17` or the distribution path.

**Command.**

```bash
/opt/java/jdk-17/bin/java -version
```

On this build host the equivalent command is `/usr/lib/jvm/java-17-openjdk-amd64/bin/java -version`.

**Expected response.** The first line contains `openjdk version "17`, for example `17.0.20.1`.

**Verification.** Repeat with `scripts/start.sh` after installation and confirm the script logs the same binary.

**Pass.** The version is 17. **Fail.** Any other major version.

## Test 2 — Verify JAR startup

**Objective.** The executable JAR starts from the shell without Maven or an IDE.

**Prerequisites.** `release/` is installed, `application.yml` is next to the JAR, and a ready blacklist file is in the incoming directory. For a local classpath run, Oracle and security may stay disabled.

**Command.**

```bash
/opt/msisdn-api/scripts/start.sh
echo $?
```

**Expected response.** Exit 0 and a log line `MSISDN eligibility API lifecycle is up`. Exit 2 means the process is alive but the blacklist is not ready yet.

**Verification.** `scripts/status.sh` prints `process: running` and a PID. The PID command line contains `msisdn-api.jar`.

**Pass.** One Java process and exit 0 or a documented exit 2 while a large file is still loading. **Fail.** Exit 1, or a second Java process.

## Test 3 — Verify health endpoint

**Objective.** Separate liveness from readiness.

**Prerequisites.** Test 2 passed.

**Command.**

```bash
curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/actuator/health/liveness
curl --fail --silent --show-error --connect-timeout 2 --max-time 5 \
  http://127.0.0.1:8080/actuator/health/readiness
```

**Expected response.** Both bodies contain `"status":"UP"` when a fresh blacklist is loaded. Liveness stays UP when Oracle is down. Readiness is DOWN when no blacklist is loaded.

**Verification.** `scripts/healthcheck.sh` exits 0 when liveness is UP even if you temporarily point Oracle at a closed port.

**Pass.** Liveness and readiness match the rules above. **Fail.** A hung curl, or readiness UP with no blacklist.

## Test 4 — Test eligible MSISDN

**Objective.** A number that is absent from the blacklist is `OK`.

**Prerequisites.** The sample file is loaded. It does not contain `201055555555`.

**Command.**

```bash
curl -sS -D - http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: ${MSISDN_API_KEY}" \
  -d '{"msisdn":"201055555555"}'
```

Omit the API key header when `security.enabled` is false.

**Expected response.** HTTP 200 and a body shaped like:

```json
{"requestId":"req-...","msisdn":"201055555555","result":"OK","reasons":[],"blacklistVersion":"20261015"}
```

**Verification.** `X-Request-Id` equals `requestId`.

**Pass.** HTTP 200 and `OK`. **Fail.** `KO` or any other status.

## Test 5 — Test blacklisted MSISDN

**Objective.** One active flag returns `KO` and one reason.

**Prerequisites.** Sample row `201098765432,1,0,0` is loaded, or use `201012345678` when only `Suspicious_IMEI` is set.

**Command.**

```bash
curl -sS http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: ${MSISDN_API_KEY}" \
  -d '{"msisdn":"201098765432"}'
```

**Expected response.** HTTP 200, `"result":"KO"`, and `reasons` containing only `Anti-Fraud (Vishing)`.

**Verification.** Compare the reason with `blacklist.sources.display-names.Vishing`.

**Pass.** Exactly one reason. **Fail.** `OK`, or extra reasons.

## Test 6 — Multiple rejection reasons

**Objective.** Every active flag is returned, in column order.

**Command.**

```bash
curl -sS http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: ${MSISDN_API_KEY}" \
  -d '{"msisdn":"201066257228"}'
```

**Expected response.** HTTP 200, `KO`, and both `Anti-Fraud (Vishing)` and `Anti-Fraud (Financial Fraud)` when the sample row `201066257228,1,0,1` is loaded.

**Verification.** The status payload `sourceCount` matches the CSV header.

**Pass.** Both reasons, Vishing before Financial Fraud. **Fail.** A missing or reordered reason.

## Test 7 — Invalid MSISDN

**Objective.** Malformed input returns HTTP 400 and does not produce an eligibility decision.

**Commands.**

```bash
curl -sS -o /tmp/bad1.json -w '%{http_code}\n' http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' -H "X-API-Key: ${MSISDN_API_KEY}" \
  -d '{"msisdn":"123"}'
curl -sS -o /tmp/bad2.json -w '%{http_code}\n' http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' -H "X-API-Key: ${MSISDN_API_KEY}" \
  -d '{"msisdn":"2010ABCDEFGH"}'
curl -sS -o /tmp/bad3.json -w '%{http_code}\n' http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' -H "X-API-Key: ${MSISDN_API_KEY}" \
  -d '{"msisdn":""}'
```

**Expected response.** `400` three times. The body contains the request id and a validation message. The full MSISDN is not required in the message.

**Verification.** Application log shows a masked value or `***`, not the raw invalid string when masking applies.

**Pass.** All three calls return 400. **Fail.** 200 or 500.

## Test 8 — Verify audit file

**Objective.** The request id used in the HTTP response is in the JSONL audit segment.

**Prerequisites.** Tests 4 and 6 were just executed. `audit.flush-interval` has elapsed or the segment has rotated.

**Command.**

```bash
grep -R "201066257228" /opt/msisdn-api/logs/audit/active /opt/msisdn-api/logs/audit/completed
```

**Expected response.** A single-line JSON object with `requestId`, `msisdn`, `result`, `reasons`, `reasonMask`, `blacklistVersion`, `requestTimestamp`, and `responseTimestamp`.

**Verification.** `reasonMask` for Vishing plus Financial Fraud (bits 0 and 2) is the decimal string `5`.

**Pass.** The response request id is present and the fields match. **Fail.** No file, or a line that does not end with a newline.

## Test 9 — Verify Oracle records

**Objective.** The same request id is inserted into `FMSPRD6.MSISDN_ELIGIBILITY_AUDIT`.

**Prerequisites.** Oracle is enabled and the writer has flushed a completed segment. This test cannot pass while `oracle-audit.enabled` is false.

**Command.** Run as `MSISDN_API`:

```sql
SELECT request_id, msisdn, result, reason_mask, reasons, blacklist_version, http_status
  FROM FMSPRD6.MSISDN_ELIGIBILITY_AUDIT
 WHERE request_id = '&request_id';
```

**Expected response.** One row. `REASON_MASK` is `0000000000000005` for mask 5. `MSISDN` is `VARCHAR2` text.

**Verification.** Run the same query again after restarting the API. The count stays 1.

**Pass.** One row whose request id matches the HTTP response. **Fail.** Zero rows after the segment is complete and Oracle is healthy, or two rows.

## Test 10 — Verify CSV export

**Objective.** The completed segment is exported to a final CSV, not left as `.part`.

**Command.**

```bash
ls -l /opt/msisdn-api/data/export
head -n 5 /opt/msisdn-api/data/export/eligibility-audit-*.csv
```

**Expected response.** A `.csv` file with a header and the same request id, MSISDN, result, and reasons. No `.part` file remains for a finished export.

**Verification.** Row count of the CSV, excluding the header, equals the number of JSONL lines in the segment that produced it.

**Pass.** Final file only, header present, values match. **Fail.** A `.part` file presented as complete output, or a missing request id.

## Test 11 — Daily blacklist reload

**Objective.** A new file changes decisions without a restart.

**Prerequisites.** Version `20261015` is active and `201066257228` is `KO`.

**Commands.**

```bash
cat > /tmp/blacklist-20261016.csv <<'EOF'
MSISDN,Vishing,Suspicious_IMEI,Financial_Fraud
201066257228,0,0,0
EOF
cp /tmp/blacklist-20261016.csv /opt/msisdn-api/data/blacklist/incoming/
# The ready marker is created only after the CSV copy has finished.
touch /opt/msisdn-api/data/blacklist/incoming/blacklist-20261016.csv.ready
sleep 70
curl -sS http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' -H "X-API-Key: ${MSISDN_API_KEY}" \
  -d '{"msisdn":"201066257228"}'
```

The sleep covers the default 30 second reload interval and the stability observation. Set `blacklist.stability.enabled: false` in a lab if you need the next scan to load immediately.

**Expected response.** `"result":"OK"` and `"blacklistVersion":"20261016"`. The process PID does not change.

**Verification.** `scripts/status.sh` shows version `20261016`. The previous snapshot file remains under `data/blacklist/snapshot` until retention removes older versions.

**Pass.** New version, new decision, same PID. **Fail.** A restart was required, or the old decision remains.

## Test 12 — Invalid blacklist

**Objective.** A bad file does not replace the active snapshot.

**Command.**

```bash
printf 'MSISDN,Vishing\nNOT-A-NUMBER,1\n' > /opt/msisdn-api/data/blacklist/incoming/blacklist-20261017.csv
touch /opt/msisdn-api/data/blacklist/incoming/blacklist-20261017.csv.ready
sleep 70
scripts/status.sh
```

**Expected response.** The status version is still the previous valid version. The bad file is under `data/blacklist/rejected` with an `.error.txt` sidecar.

**Verification.** Repeat Test 6 or the post-reload decision from Test 11. It is unchanged.

**Pass.** Previous snapshot still serves. **Fail.** Readiness flips to an empty blacklist or the version becomes `20261017`.

## Test 13 — Application restart

**Objective.** A restart reloads the last valid snapshot and continues audit processing.

**Command.**

```bash
/opt/msisdn-api/scripts/restart.sh
/opt/msisdn-api/scripts/status.sh
```

**Expected response.** Exit 0, one PID, the same blacklist version, and liveness UP.

**Verification.** A new eligibility request returns the same decision and a new request id. Completed audit segments that were not yet inserted remain in `logs/audit/completed`.

**Pass.** Service returns with the same version and no duplicate Oracle row for an already inserted request id. **Fail.** The API comes up ready with no blacklist, or a second process appears.

## Test 14 — Oracle outage

**Objective.** Eligibility continues while Oracle is unreachable, and the backlog drains after recovery.

**Prerequisites.** Non-production database. You can block the listener or change the URL in a copy of the environment file.

**Commands.**

```bash
# Block the database port from this host only, for example with a local firewall rule you can remove.
sudo firewall-cmd --add-rich-rule='rule family="ipv4" port port="1521" protocol="tcp" reject' 
for i in 1 2 3; do
  curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/v1/eligibility/check \
    -H 'Content-Type: application/json' -H "X-API-Key: ${MSISDN_API_KEY}" \
    -d '{"msisdn":"201055555555"}'
done
ls /opt/msisdn-api/logs/audit/completed
sudo firewall-cmd --remove-rich-rule='rule family="ipv4" port port="1521" protocol="tcp" reject'
```

**Expected response.** The three API calls return 200. Completed segments accumulate while the database is blocked. After the port is restored, new rows appear and the checkpoints become `complete: true`. Liveness stays UP during the outage. The watchdog log does not show a restart caused by the outage.

**Verification.** Count rows before and after recovery. The difference matches the segment line count, without duplicates.

**Pass.** API stays up, backlog drains once. **Fail.** HTTP 500 on the eligibility call, or a watchdog restart.

## Test 15 — Audit queue saturation

**Objective.** A full audit queue returns HTTP 503 instead of dropping the event.

**Prerequisites.** Non-production. Point `audit.log.directory` at a filesystem that you can remount read-only, or set `audit.async.queue-size` to a small value and pause the writer by filling the disk. Restart is required after the configuration change.

**Command.** Send requests in a loop after the writer can no longer drain:

```bash
for i in $(seq 1 100); do
  curl -sS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/v1/eligibility/check \
    -H 'Content-Type: application/json' -H "X-API-Key: ${MSISDN_API_KEY}" \
    -d '{"msisdn":"201055555555"}'
done
```

**Expected response.** Calls return 200 until the bounded queue reaches the readiness threshold or the offer times out. Further calls return 503 with an audit-queue message. No audit line is missing for a 200 response.

**Verification.** Restore the disk or the queue size, restart, and confirm 200 responses return.

**Pass.** 503 appears only when the queue cannot accept, and those calls are not silently discarded. **Fail.** 200 responses with no corresponding audit record.

## Test 16 — CSV export failure

**Objective.** An unavailable export directory retains the source segment, and export resumes later.

**Command.**

```bash
sudo chattr +i /opt/msisdn-api/data/export 2>/dev/null || sudo mount -o remount,ro /opt/msisdn-api/data/export
# Generate a completed segment with Test 4, then:
ls /opt/msisdn-api/logs/audit/completed
sudo chattr -i /opt/msisdn-api/data/export 2>/dev/null || sudo mount -o remount,rw /opt/msisdn-api/data/export
sleep 5
ls /opt/msisdn-api/data/export
```

Use a directory you are allowed to make read-only. Do not remount a production filesystem.

**Expected response.** While the destination is unavailable, completed JSONL files remain. After it is writable, a `.csv` file appears and the checkpoint completes.

**Verification.** The JSONL file is not deleted before the checkpoint says `complete`.

**Pass.** Recovery publishes the CSV. **Fail.** The source segment was deleted or the export file is a leftover `.part`.

## Test 17 — Watchdog recovery

**Objective.** A killed process is started again, and only one instance runs.

**Prerequisites.** The watchdog cron is installed, or you run `scripts/watchdog.sh` by hand. systemd is the supervisor if the unit is enabled.

**Command.**

```bash
pid=$(scripts/status.sh | awk '/pid:/ {print $2}')
kill -TERM "$pid"
sleep 70
scripts/status.sh
pgrep -af msisdn-api.jar
```

**Expected response.** After the next watchdog run, one process is running. `logs/application/watchdog.log` contains one restart line. A second manual run of the watchdog while the process is healthy does not start another JVM.

**Verification.** `flock` prevents two watchdog runs: start one `watchdog.sh` under `sleep` by holding the lock, and confirm the second exits 0 with `watchdog already running`.

**Pass.** Exactly one `msisdn-api.jar` process. **Fail.** Zero processes after the grace window, or two processes.

## Test 18 — Server reboot recovery

**Objective.** systemd starts the service after reboot, and the watchdog does not fight it.

**Prerequisites.** `systemctl enable msisdn-api` was run. This reboots the host. Use a lab host.

**Command.**

```bash
sudo systemctl enable msisdn-api
sudo shutdown -r now
```

After the host returns:

```bash
systemctl status msisdn-api --no-pager
/opt/msisdn-api/scripts/healthcheck.sh
```

**Expected response.** The unit is active. Liveness becomes UP. Readiness becomes UP after the snapshot loads. The watchdog, if installed, does not launch a second JVM during the grace period.

**Verification.** `ps -ef | grep msisdn-api.jar` shows one process owned by `msisdn`.

**Pass.** One service, enabled unit, healthcheck exit 0. **Fail.** The unit failed, or a shell-started JVM is running beside systemd.

## Test 19 — Concurrent requests

**Objective.** Parallel calls return consistent decisions.

**Command.**

```bash
seq 1 50 | xargs -P 10 -I{} curl -sS http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' -H "X-API-Key: ${MSISDN_API_KEY}" \
  -d '{"msisdn":"201066257228"}' > /tmp/concurrent.out
grep -c '"result":"KO"' /tmp/concurrent.out
grep -c 'Exception' /opt/msisdn-api/logs/application/application.log || true
```

**Expected response.** 50 `KO` results for the sample blacklisted number. No `ConcurrentModificationException`.

**Verification.** Each line has its own `requestId`.

**Pass.** 50 successes and no concurrency error. **Fail.** A mixed OK/KO result for the same number and version, or an exception in the log.

## Test 20 — Load testing

**Objective.** Record latency at 100, 250, 500, 750, and 1,000 requests per second. This build did not execute these runs. Do not copy numbers from another environment.

**Prerequisites.** A non-production host, a loaded blacklist of realistic size, k6 installed, and monitoring for CPU, heap, audit queue, and Oracle backlog. Keep Oracle and CSV export enabled if you want those columns.

**Commands.**

```bash
for rate in 100 250 500 750 1000; do
  k6 run -e RATE="$rate" -e DURATION=2m -e BASE_URL=http://127.0.0.1:8080 \
    -e API_KEY="$MSISDN_API_KEY" \
    -e ELIGIBLE_MSISDN=201055555555 \
    -e BLOCKED_MSISDN=201066257228 \
    tests/load-test.js | tee "tests/test-results/k6-${rate}.json"
done
```

Repeat one rate while copying a new ready blacklist into the incoming directory so the reload overlaps the test.

**Record.** Successful requests, failed requests, p50, p95, p99, CPU, JVM heap, audit queue utilization from `/api/v1/system/status`, and Oracle backlog segments.

**Pass.** You stored the k6 output and the host remained within its memory limit. There is no universal numeric pass line in this repository because no baseline was measured here. **Fail.** The run was not saved, or the API returned `OK` while the blacklist was absent.

## Results template

| Test | Date | Result | Notes |
|---|---|---|---|
| 1 | | | |
| 2 | | | |
| 3 | | | |
| 4 | | | |
| 5 | | | |
| 6 | | | |
| 7 | | | |
| 8 | | | |
| 9 | | | |
| 10 | | | |
| 11 | | | |
| 12 | | | |
| 13 | | | |
| 14 | | | |
| 15 | | | |
| 16 | | | |
| 17 | | | |
| 18 | | | |
| 19 | | | |
| 20 | | | |
