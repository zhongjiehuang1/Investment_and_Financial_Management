# Deployment validation — 2026-11-08

> Historical design/validation record. It is not the current deployment status or authorization to enable writes. See CURRENT_VALIDATION_2026-11-11.txt and the current server operations guide.

## Release behavior

All four applications are deployed on both logical servers. Each public entrance reports only its local server number. Portfolio and the other applications' static pages are served locally by Nginx, independently of MySQL and Tomcat. Database operations use a single active coordinator; an unavailable required participant closes database access on both entrances. There is no automatic writer promotion.

Finance creates new business rows on the authenticated ingress server, merges reads from both databases, and updates balances on the account's owning server. Durable XA decisions, participant receipts, request identities and generation-qualified references protect retries, cross-node changes and recycled integer IDs. UCost stores each user's versioned snapshot under one authoritative database transaction. Star_Hub distributes immutable file bodies with a global catalogue, quota and capacity reservations.

Star_Hub enforces a 20,000,000,000-byte per-user quota, including trash and pending reservations. Admission preserves at least 7,000,000,000 bytes on the selected server after its conservative physical allocation estimate. It tries the ingress server first, selects another eligible server before admission, and rejects a request if neither qualifies. An uncertain admitted operation retains its original destination. The deployed filesystem reserve and managed process privileges were checked on both nodes.

## Completed checks

- Migration rehearsal used isolated copies of the existing databases. Original fields and row counts were preserved, and repeating migrations did not change assigned generations. The finance copy contained 23,966 rows across its 17 original tables; Star_Hub contained five rows across its five original tables. No user financial data or private credentials were placed in the repository.
- Java reports: finance 123 tests with three optional skips; UCost 20 tests; Star_Hub 81 tests with one existing optional skip. No failures or errors. JavaScript, real-browser and portfolio structural/link checks passed separately.
- Additional disposable MySQL tests covered recycled identities, concurrent mutations, merged pagination, cross-node cascades, money rounding, pinned exchange rates, quota competition, full-capacity rejection, replay conflicts and interrupted storage transactions.
- A separate finance JVM was forcibly halted after the first physical MySQL participant committed. A fresh JVM recovered the second participant, released the gate and returned the original receipt without repeating the mutation. This was tested against both physical servers, using isolated schemas.
- Live finance HTTPS checks created accounts on different servers, recorded income from the opposite entrance, replayed the same request, edited the record, transferred between the accounts, reversed deletions and read one atomic financial report. Balances and placement matched expectations. All test business data and the temporary user were removed through the application.
- Live Star_Hub HTTPS checks uploaded through both entrances, verified physical placement and content hashes, replayed operations, rejected changed payloads under the same identity, and tested trash, restore and permanent deletion. The global quota was exactly 20 GB and final test usage/reservations were zero.
- Live UCost HTTPS checks covered cross-entrance reads and writes, receipt replay without a revision increase, stale-revision and changed-payload conflicts, user isolation, request identity checks and password-change session revocation.
- Eight HTTPS homepages, local server identifiers and TLS verification passed. Public access to recovery endpoints and application-private directories was denied. After startup recovery, 120 consecutive public health checks and 40 private readiness checks passed across both nodes.
- Seven controlled outage scenarios passed: either MySQL stopped, both MySQL services stopped, either Tomcat stopped, both Tomcat services stopped, and the private node connection interrupted. In every scenario all eight static homepages returned HTTP 200 and all six application health entrances returned HTTP 503 with `服务器故障，请等待恢复`. Stateful access recovered after the dependencies returned. Write attempts during the Tomcat/network scenarios were refused; the same durable operation identity was confirmed absent after recovery.

## Operating limits

These checks cover application, database-service, connection and coordinator-process failures. They are not a physical power-cut or permanent disk-loss certification. Recovery requires surviving durable database and coordinator media. Never discard prepared transactions, receipts, gates or coordinator logs to force readiness.

Two DNS address records do not guarantee how quickly every client will retry the surviving server. Portfolio is independently served on each node; a guaranteed client failover time requires an external health-aware entry point. If both machines are off, neither can serve a page until infrastructure recovers.

The capacity protection applies to the configured storage and managed writers. Arbitrary privileged administrator writes, new unconfigured services and hardware failures remain outside that admission guarantee. Throughput is deliberately secondary to consistency; no high-concurrency capacity claim is made.
