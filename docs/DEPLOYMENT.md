# Deploy Book Buddy on Render with Neon

This is the first public demo deployment. The application runs on Render and
stores accounts, shelves, passports and Book Buddy reviews in Neon PostgreSQL.
Visitors open the public HTTPS address; your laptop can be switched off.

No AI provider, paid API, local model, Docker Desktop installation or additional
database server is required. Use the Free service plan and leave payment details
unset for the requested no-charge setup. Free quotas and provider restrictions
still apply. Render can suspend services or disable builds when allowances run
out. A sleeping Free service can take about a minute to wake.

## 1. Upload the deployment changes

Keep the repository layout exactly as in the project, especially the nested
`myBooks/myBooks` backend. Upload these new or changed files to GitHub:

```text
Dockerfile
.dockerignore
.gitignore
frontend/src/api.js
myBooks/myBooks/pom.xml
myBooks/myBooks/src/main/resources/application.properties
myBooks/myBooks/src/main/resources/application-prod.properties
myBooks/myBooks/src/main/resources/db/migration/postgresql/V1__initial_book_buddy_schema.sql
myBooks/myBooks/src/test/java/com/bookshelf/myBooks/ProductionProfileTests.java
myBooks/myBooks/src/test/java/com/bookshelf/myBooks/DatabaseMigrationTests.java
docs/DEPLOYMENT.md
```

The repository must also contain the existing React sources and lockfile,
backend sources and tests, `mvnw`, and `.mvn/wrapper/maven-wrapper.properties`.
Do not upload `node_modules`, `dist`, `target`, `.run`, database files, backups,
logs, archives or credentials. The Docker context uses an explicit source
allowlist. The image copies only the built application into its runtime stage.

## 2. Configure the web service

Connect Render to `AlinaFurtado/BookShop`, then use these settings:

| Setting | Value |
| --- | --- |
| Service type | Web Service |
| Name | `book-buddy` or another available name |
| Branch | The branch containing the uploaded deployment files |
| Language / Runtime | Docker |
| Root Directory | Leave empty |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context, if shown | `.` |
| Docker Command | Leave empty; use the Dockerfile entrypoint |
| Instance Type | Free |
| Health Check Path | `/api/health` |

Choose a region near your Neon database from the regions available on the Free
plan. Do not create a second Render PostgreSQL database or a separate static
site for this setup. React is packaged inside the Java application.

## 3. Enter database environment variables

Under Environment Variables, enter these three values privately in Render:

| Key | Value |
| --- | --- |
| `BOOKBUDDY_DB_URL` | `jdbc:postgresql://YOUR_NEON_HOST:5432/neondb?sslmode=require&channelBinding=require` |
| `BOOKBUDDY_DB_USER` | Your Neon database role |
| `BOOKBUDDY_DB_PASSWORD` | Your Neon database password |

Replace the example host and database with the values in your Neon **Connect**
panel. For this small three-connection pool, use the direct hostname from that
panel; a pooled hostname is not required. Copy only the password value, without
shell quotes. Keep the username/password out of the JDBC URL. Do not paste a
`psql` command or a `postgresql://user:password@...` URL into this field.

The image already enables `SPRING_PROFILES_ACTIVE=prod`. Render supplies `PORT`.
Do not override the datasource settings with H2 or set `ddl-auto=create`.
Database credentials are runtime variables; the Dockerfile never references
them as build arguments or copies them into a file. They remain accessible to
the running server and your hosting account, as required to connect.

## 4. Build and launch

Click **Deploy Web Service / Create Web Service** after all settings are entered.
Render builds React, runs frontend linting and backend tests, then starts the
packaged Java application. The first build downloads its dependencies.

The runtime profile listens on `0.0.0.0` and Render's port, uses secure session
cookies behind HTTPS, and connects to PostgreSQL with a pool of at most three
connections. Java has a 256 MB heap cap plus separately budgeted native memory;
this is not a guarantee of a particular concurrent-user capacity.

On the first production start, Flyway creates a migration history. For the
existing Hibernate-created database, it records baseline version 0 and runs
version 1 with `CREATE TABLE IF NOT EXISTS`. Existing rows and identity counters
are retained. For an empty database, version 1 creates the tables directly.
Hibernate then validates the mapped schema instead of automatically changing it.
Do not edit an already-applied migration; add `V2__description.sql` for the next
schema change. This adoption is tested using disposable H2 fixtures; the actual
Neon/PostgreSQL startup is confirmed during the first Render deployment.

## 5. Verify the public website

Use the HTTPS URL Render shows after the service becomes Live:

1. Open the website and check the interface loads.
2. Open `/api/health`. It should report `PostgreSQL`. The database label is
   configuration information, not a continuous database availability check.
3. Sign in with an existing Neon account, or register a new account.
4. Search for a book, save it, change its reading status and refresh the page.
5. Check a completed book's passport and existing private notes.
6. Confirm the saved entry in Neon's Tables view.
7. Open the public URL on your phone with Wi-Fi off to verify public access.

Sessions currently live in server memory. A restart or sleep can require signing
in again; saved data remains in Neon. Then replace “Coming soon” in the README
with the actual public URL. AI free-text recommendations remain a later step.

## Troubleshooting

- **Dockerfile not found:** upload it at the repository root and leave Root
  Directory empty. Ensure Windows has not renamed it `Dockerfile.txt`.
- **Missing wrapper/lockfile:** upload the existing `.mvn` folder, `mvnw`, and
  `frontend/package-lock.json` at their original paths.
- **Database connection error:** check the three Render variables and Neon
  hostname/database. Do not share screenshots containing credentials.
- **Schema validation error:** keep the database intact and inspect the specific
  missing/different column. Do not switch to `create` or drop tables.
- **Login does not persist:** use the HTTPS URL and check the `prod` profile is
  active. Do not test its secure cookie settings over plain local HTTP.
- **Cold start:** a sleeping Free instance needs time to wake. Wait and retry.
- **Out of memory:** inspect the host logs; do not assume a paid upgrade is
  authorised. The memory settings and workload can be reviewed first.

## References

- [Docker on Render](https://render.com/docs/docker)
- [Render web services and port binding](https://render.com/docs/web-services)
- [Render Free service limitations](https://render.com/docs/free)
- [Neon Java connections](https://neon.com/docs/guides/java)
- [Spring Boot database initialization and migrations](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
