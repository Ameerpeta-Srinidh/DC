# Frame by Frame
### A Letterboxd-inspired film diary built with Java socket programming

Frame by Frame is a distributed-systems mini-project with a browser interface and Java TCP socket services. It demonstrates film discovery, personal watch diaries, ratings and reviews, user follows, and a social activity feed.

## Features

- Register, sign in, and log out using server-issued session tokens.
- Browse and search a seeded catalogue of 33 films; filter by genre.
- Log a film with a watch date, 1–5 star rating, and optional review.
- View a personal diary, public diaries, and activity from followed accounts.
- Run two stateless application servers behind a round-robin TCP load balancer.
- Write to a primary SQLite database and asynchronously mirror selected data to a replica.

Film-cover illustrations are generated locally with CSS. The application does not depend on external movie APIs or image assets.

## Requirements

- Java JDK 17 or newer.
- A modern browser.
- Windows: use the included `.bat` launchers. macOS/Linux: use the `.sh` launchers.
- Gson and SQLite JDBC dependencies are included in `lib/`.

## Run on Windows

1. Extract the submission ZIP to a writable folder.
2. Double-click `start_demo.bat`.
3. Wait for the five service terminals to start, then open `http://localhost:8080`.
4. Choose **Sign in**, then **Create an account**. Create an account, sign in, browse films, and log a watch.
5. To try the activity feed, create another account in a private browser window, log a film there, and follow that account from the first window.
6. Close the service terminals when finished.

The launch configuration uses ports 9800 (database), 9876 and 9877 (application servers), 9870 (load balancer), and 8080 (web interface). Stop conflicting local services before launching.

## Run services manually

Run each command from the extracted project folder in a separate terminal:

```text
build.bat
run_database_server.bat 9800 primary.db replica.db 750
run_app_server.bat 9876 localhost 9800
run_app_server.bat 9877 localhost 9800
run_load_balancer.bat 9870 localhost:9876,localhost:9877
run_web.bat localhost 9870 8080
```

On macOS/Linux, use the corresponding `.sh` scripts. The final database-server argument sets a simulated replication delay in milliseconds.

## Architecture

```text
Browser
   │ HTTP (local browser adapter)
   ▼
WebGateway :8080
   │ TCP + newline-delimited JSON
   ▼
LoadBalancer :9870 ── round robin ──┬── AppServer :9876
                                    └── AppServer :9877
                                               │ TCP + newline-delimited JSON
                                               ▼
                              DatabaseServer :9800
                               ├── Primary SQLite (writes)
                               ├── Replica SQLite (reads)
                               └── asynchronous replication worker
```

The local web gateway adapts browser HTTP requests to the Java socket protocol; it does not replace the socket-based application tiers. Each Java request is one JSON line over TCP. The application servers remain stateless, while the database server validates session tokens and coordinates storage.

| Project area | Main files |
|---|---|
| Browser interface | `web/index.html`, `web/app.css`, `web/app.js` |
| Browser-to-socket gateway | `server/WebGateway.java` |
| Client protocol and routing | `protocol/Message.java`, `server/RequestRouter.java`, `server/ConnectionHandler.java` |
| App tier and load balancer | `server/LetterboxdAppServer.java`, `server/LoadBalancer.java` |
| Storage, sessions, and replication | `data/PrimaryDatabase.java`, `data/ReplicaDatabase.java`, `data/ReplicationManager.java`, `data/SessionManager.java` |

## Documents and screenshots

- `ArchitectureofApp_23MCCE04.docx` — architecture study.
- `ParadigmsandAlgorithms_23MCCE04.docx` — distributed design patterns and algorithms.
- `RequirementSpecificationSimpleApp_23MCCE04.docx` — simplified application requirements.
- `Tutorial_Installation_Usage_23MCCE04.docx` — installation and usage manual.
- `Presentation_23MCCE04.pptx` — mini-project presentation.
- `screenshots/` — captures of the running interface.

The screenshots show example diary entries in the running app for `srinidh`, `piyush`, `nanditha`, `bhargav`, and `charan`. These are demonstration records; a fresh run starts with an empty user database.

## Limitations

This is a local learning prototype, not Letterboxd source code or a reconstruction of its private production infrastructure. Public Letterboxd product information does not establish the exact technologies or algorithms used by its production system; the architecture report distinguishes product references from general design analysis.

The demo load balancer does not check backend health or provide automatic failover. The primary and replica SQLite files run on one machine. Replication is asynchronous, so replica reads can briefly be stale; pending replication tasks and session tokens are held in memory. Authentication, transport security, backups, and recovery are not production-ready. Do not expose the demo to the public internet.

## References

- Letterboxd: <https://letterboxd.com/>
- Letterboxd About: <https://letterboxd.com/about/>
- Letterboxd API information: <https://letterboxd.com/api-beta/>
- Letterboxd Help: <https://letterboxd.com/help/>
- TMDB developer documentation (general metadata API reference): <https://developer.themoviedb.org/docs>
