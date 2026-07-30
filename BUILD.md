# Building Wagoe Uberjar

This document describes how to build and run the Wagoe application as a standalone uberjar.

## Prerequisites

- Clojure CLI tools (1.12+)
- Java 17 or higher
- Git (for version numbering)

## Building the Uberjar

### Clean Build Artifacts

```bash
clojure -T:build clean
```

### Build Uberjar

```bash
clojure -T:build uber
```

This will:
- Clean the `target/` directory
- Copy source files and resources
- Compile the main namespace for faster startup
- Package all dependencies including database drivers (SQLite, PostgreSQL, H2, MySQL)
- Create `target/wagoe-1.2.X-standalone.jar` (where X is the git commit count)

The resulting jar file is approximately 60MB and includes:
- All Clojure source code
- All dependencies (Integrant, Ring, Reitit, Logback, etc.)
- Database drivers (SQLite, PostgreSQL, H2, MySQL)
- Configuration files (logback.xml)
- Static resources

## Running the Uberjar

### Server Mode (Default)

Start the HTTP server:

```bash
java -jar target/wagoe-1.2.X-standalone.jar
```

Or explicitly:

```bash
java -jar target/wagoe-1.2.X-standalone.jar server
```

The server will:
- Load configuration from environment or defaults
- Initialize the database
- Start HTTP server on port 3000 (configurable via `HTTP_PORT`)
- Log to console and `logs/` directory
- Run until Ctrl+C is pressed

### CLI Mode

Run CLI commands:

```bash
java -jar target/wagoe-1.2.X-standalone.jar cli user list
java -jar target/wagoe-1.2.X-standalone.jar cli user create --email test@example.com
```

### Help

Show usage information:

```bash
java -jar target/wagoe-1.2.X-standalone.jar help
```

## Configuration

### Environment Variables

- `HTTP_PORT` - HTTP server port (default: 3000)
- `HTTP_HOST` - HTTP server host (default: 0.0.0.0)
- `ENV` - Environment profile (dev, prod, test)

### Running in Production

```bash
ENV=prod HTTP_PORT=8080 java -jar wagoe-1.2.X-standalone.jar server
```

### Database Configuration

The uberjar includes all database drivers. Configure the active database in `resources/conf/{env}/config.edn`:

```edn
:wagoe/sqlite {:db "production.db"}
;; or
:wagoe/postgresql {:host "localhost" :port 5432 ...}
```

## Logging

Logging is configured via `resources/logback.xml` (included in the jar).

Logs are written to:
- Console (stdout)
- `logs/wagoe.log` - Main application log
- `logs/audit.log` - Audit events
- `logs/security.log` - Security events

To change log levels without rebuilding:
1. Extract logback.xml from the jar
2. Modify it
3. Place it in the same directory as the jar
4. Run with: `java -Dlogback.configurationFile=./logback.xml -jar wagoe.jar`

Or set environment variable:
```bash
JAVA_OPTS="-Dlogback.configurationFile=/path/to/logback.xml" java -jar wagoe.jar
```

## Performance Tuning

### JVM Options

```bash
java -Xmx2g -Xms512m -XX:+UseG1GC -jar wagoe.jar
```

### Recommended Production Settings

```bash
java \
  -Xmx2g \
  -Xms512m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -Dlogback.configurationFile=/etc/wagoe/logback.xml \
  -jar wagoe.jar server
```

## Docker Deployment

Create a `Dockerfile`:

```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY target/wagoe-1.2.X-standalone.jar wagoe.jar
COPY resources/conf/prod/config.edn /app/config/config.edn

ENV ENV=prod
ENV HTTP_PORT=8080

EXPOSE 8080

CMD ["java", "-jar", "wagoe.jar", "server"]
```

Build and run:

```bash
docker build -t wagoe:latest .
docker run -p 8080:8080 wagoe:latest
```

## Systemd Service

Create `/etc/systemd/system/wagoe.service`:

```ini
[Unit]
Description=Wagoe HTTP Server
After=network.target

[Service]
Type=simple
User=wagoe
WorkingDirectory=/opt/wagoe
Environment="ENV=prod"
Environment="HTTP_PORT=8080"
ExecStart=/usr/bin/java -Xmx2g -jar /opt/wagoe/wagoe.jar server
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl enable wagoe
sudo systemctl start wagoe
sudo systemctl status wagoe
```

## Troubleshooting

### Check Version

```bash
jar xf wagoe-1.2.X-standalone.jar META-INF/MANIFEST.MF
cat META-INF/MANIFEST.MF
```

### Verify Main Class

```bash
jar tf wagoe-1.2.X-standalone.jar | grep "wagoe/main"
```

### Test Database Drivers

```bash
jar tf wagoe-1.2.X-standalone.jar | grep -E "(sqlite|postgresql|h2|mysql)"
```

### Enable Debug Logging

```bash
java -Dlogback.debug=true -jar wagoe.jar
```

## Build Automation

### CI/CD Pipeline Example (GitHub Actions)

```yaml
name: Build Uberjar

on:
  push:
    tags:
      - 'v*'

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
      - uses: DeLaGuardo/setup-clojure@12.5
        with:
          cli: 1.12.4.1597
      - run: clojure -T:build uber
      - uses: actions/upload-artifact@v3
        with:
          name: wagoe-uberjar
          path: target/wagoe-*-standalone.jar
```

## File Size Optimization

Current uberjar is ~60MB. To reduce size:

1. **Exclude unused drivers** - Modify `build.clj` to include only needed drivers
2. **Use ProGuard** - Shrink and optimize the jar
3. **GraalVM native-image** - Compile to native binary (advanced)

### Example: SQLite Only

In `build.clj`, change:

```clojure
(def basis (b/create-basis {:project "deps.edn"
                            :aliases [:db]}))
```

To:

```clojure
(def basis (b/create-basis {:project "deps.edn"
                            :extra-deps {'org.xerial/sqlite-jdbc {:mvn/version "3.51.0.0"}}}))
```

## Support

For issues or questions:
- Check logs in `logs/` directory
- Review configuration in `resources/conf/`
- Consult the main README.md
