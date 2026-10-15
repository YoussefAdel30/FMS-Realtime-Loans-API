# MSISDN Eligibility Validation API

Real-time eligibility checks against an in-memory blacklist of up to 10 million MSISDNs. The request path does not query Oracle. Audit records are queued, written to local segments, then inserted into `FMSPRD6.MSISDN_ELIGIBILITY_AUDIT` and exported to CSV.

The operations guide is [docs.md](docs.md). Oracle installation is [sql/README.md](sql/README.md). The manual test procedure is [tests/manual-testing.md](tests/manual-testing.md).

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
mvn clean verify
```

The executable JAR is `target/msisdn-api.jar`. The release directory is assembled after that build.

## Run locally

The classpath `application.yml` keeps Oracle and API-key authentication off so a developer machine can start. Production uses `config/application.yml`, which enables both and expects `ORACLE_PASSWORD` and `MSISDN_API_KEY`.

```bash
mkdir -p data/blacklist/incoming
cp samples/blacklist_sample.csv data/blacklist/incoming/blacklist-20261015.csv
touch data/blacklist/incoming/blacklist-20261015.csv.ready
java -jar target/msisdn-api.jar
```

With the default stability checks enabled, the file is loaded on the second scan. For an immediate load, set `blacklist.stability.enabled: false` or wait for `blacklist.reload-interval`.

```bash
curl -s http://127.0.0.1:8080/api/v1/eligibility/check \
  -H 'Content-Type: application/json' \
  -d '{"msisdn":"201066257228"}'
```

## Production

On a host you reach as `FMSUser`, do not use sudo. From the release directory run `scripts/install.sh`. It installs into `$HOME/msisdn-api`, rewrites the `/opt/msisdn-api` paths, and leaves systemd alone. Start with `scripts/start.sh` and install the one-minute watchdog with `scripts/setup-cron.sh`. See chapters 6, 13, and 14 of `docs.md`.
