# wagoe/platform-sql

[![Status](https://img.shields.io/badge/status-stable-brightgreen)]()
[![Clojars Project](https://img.shields.io/clojars/v/com.wagoe/wagoe-platform-sql.svg)](https://clojars.org/com.wagoe/wagoe-platform-sql)

The SQL layer of Wagoe: JDBC adapters for PostgreSQL, MySQL, SQLite and H2,
migrations, seeding, leases and the transit wire format. No HTTP stack.

`wagoe-platform` depends on it, so most applications never name it. Depend on it
directly when you want Wagoe's data access without Jetty, Reitit or Muuntaja —
for example next to your own HTTP wiring.

```clojure
{:deps {com.wagoe/wagoe-platform-sql {:mvn/version "1.2.0"}
        ;; Choose your database driver
        org.postgresql/postgresql {:mvn/version "42.7.12"}}}
```

Namespaces keep their `wagoe.platform.*` names. See [AGENTS.md](AGENTS.md).
