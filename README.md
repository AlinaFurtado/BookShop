# AI Book Buddy

A playful personal bookshelf built with **Java 21+, Spring Boot, React, and H2**, with an optional **PostgreSQL/Neon** connection profile. This version deliberately leaves out AI free-text recommendations. Pip is a visual reading companion; no model, AI service, API key, billing account, or subscription is required.

## What works

- Create a local account, log in, and keep a personal bookshelf.
- Browse Open Library's live search catalog from the first page, or search by title/author. The 36 bundled books are only an offline fallback.
- Browse using genre alone, minimum rating alone, or both, even with the search box empty. Unrated books are included unless a minimum rating is selected. Use Load more to browse further matches.
- Load discovery results in batches of up to 11 matching books, with continuation for further results.
- Save directly to Want to Read or Already Read; change a saved book to Reading or Read and remove it.
- Choose Kids for children's books. General discovery and the other genres exclude children's audience labels; Surprise Me always uses the general collection.
- Default discovery, Surprise Me and Trending now suggest works first published in the last five calendar years (2022–2026 in 2026). Title/author searches include older books. Cards show their first-publication year.
- Default suggestions use English editions, including English translations. Explicit title/author searches can still find books in other languages.
- Browse a separate Trending now shelf across genres, with genre/rating controls, 11 picks per batch, horizontal browsing, book details and the usual save/read actions.
- Record or edit a finish date. Historical dates can remain unknown; no completion date is invented.
- Browse manageable shelf pages and open a searchable full-library view with status, genre, year and sorting controls.
- Find a book on Amazon India through an ordinary title/author search link.
- Read and write genuine Book Buddy community reviews, with personal star ratings, spoiler controls, and ownership checks for editing/deleting.
- Read existing, attributed review excerpts from linked Internet Archive editions. Source links, reviewer names, dates and supplied star ratings stay separate from Book Buddy reviews.
- Every book marked Read appears in Reading Passports. Open the original plum-and-gold booklet with Identity, Destinations, Immigration and Visa pages. Book identity fills automatically; choose a destination, issue its boarding pass, stamp the finish date and save private keepsakes.
- Click a book spine or card for details. Your shelf survives browser refreshes and app restarts.
- A responsive cream/purple/lime design, animated Pip, colorful book spines, and wooden shelf rows inspired by the original demo.

## Start and stop on Windows

Required: Java 21 or newer and Node.js compatible with the installed Vite version (your existing Java/Node installation is used).

From this folder, double-click **Start-BookBuddy.cmd**. It builds the app when needed and opens:

**http://localhost:8080/**

Normal use runs one Java process serving the compiled React interface. Vite and Node do not need to stay running. Java's heap is capped at 512 MB; total process memory also includes non-heap/native memory and can be higher. This is ordinary app resource use, not a promise of zero impact on your work laptop.

Use **Stop-BookBuddy.cmd** when finished. Closing the browser alone does not stop the server. The scripts do not create a Windows service or an automatic startup task. Use **Build-BookBuddy.cmd** after code changes if you want to explicitly rebuild and run the checks.

If a server was started manually in a terminal, use Ctrl+C in that terminal. The stop script only stops the process recorded by the start script, to avoid stopping unrelated Java or Node applications.

### Develop with live updates in two terminals

Backend, from `myBooks/myBooks`:

```powershell
.\mvnw.cmd spring-boot:run
```

Frontend, from `frontend`:

```powershell
npm.cmd ci    # First setup only
npm.cmd run dev
```

Keep both terminals open and use **http://localhost:5173/** for this development mode. The frontend forwards `/api` requests to the backend on `127.0.0.1:8080`; the browser uses a single origin, so login cookies and CSRF protection work together. Stop a launcher-managed app before starting this development backend on the same port.

### Connect to Neon on localhost

The default launch still uses H2. The `postgres` profile replaces its connection settings with three process environment variables: `BOOKBUDDY_DB_URL`, `BOOKBUDDY_DB_USER` and `BOOKBUDDY_DB_PASSWORD`. The PostgreSQL JDBC driver is included through Maven; Spring Boot manages its version. No database server installation is needed on your computer.

After creating a free Neon project, open **Connect** and find the hostname, database name, role/user and password. Stop any app using port 8080, then run from this project folder:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-bookbuddy-postgres.ps1
```

The launcher asks for each field, with hidden password input. It assembles an SSL connection, passes credentials through process environment variables, and restores the previous values when the app exits. Credentials are not written to source files or launcher-managed logs. The app runs in this terminal with a 320 MB heap cap; Ctrl+C stops it. No automatic startup or background service is installed. Subsequent Neon launches ask for the credentials again.

Open `/api/health` and check `database: PostgreSQL`. This label reports the configured database; it is not a continuous database availability probe. Register a new account, save a book, then inspect `app_user` and `shelf_entry` in Neon's Tables view to verify a real write. A fresh Neon database starts empty: local H2 accounts, shelves and passports are not imported, and this connection step creates no H2 backup. On first connection, Hibernate's `update` creates the entity tables. This is a localhost learning profile; versioned schema migrations and deployment configuration come before the public launch.

The connection pool opens at most three connections, releasing idle ones after a minute with a zero minimum. This keeps connection use small; it does not replace the Neon plan's usage limits. Only the running app uses these connections. The helper accepts Neon hostnames; connection details should be entered locally instead of shared in chat.

## Your data

H2 uses the existing file at `myBooks/myBooks/bookbuddy.mv.db`. Do not delete or commit it. Accounts, password hashes, shelf entries, finish dates, private passport notes, and Book Buddy reviews are stored locally. Email addresses are account identifiers; this app sends no emails. Reviews are visible to other readers using this instance; shelves and passport notes remain private to their owners.

A passport belongs to a saved shelf entry. Marking a book Read makes it appear automatically, including books already marked Read before this feature. Moving it back to Reading hides it from completed passports while preserving its notes; marking it Read again restores them. Removing the book from your shelf also removes the notes attached to that entry. Existing unknown finish dates stay unknown.

The first account registered after this upgrade inherits any books saved before accounts existed. If the earlier version saved duplicate copies of one work, the most advanced reading status is claimed and the other rows remain preserved in H2 as unowned backup rows. Later accounts have separate shelves. The original database is backed up before installing this upgrade.

The initial browse, blank search, title/author search and filters all query Open Library's live search index. Results arrive in batches of up to 11 works; Load more requests the next batch. This searches the catalog remotely, rather than downloading it onto your laptop. If Open Library is unavailable, the interface clearly identifies the smaller 36-book offline fallback and offers Retry Open Library. Sign-in and saved shelves load independently of the external catalog. Covers need Internet. Availability of a genre, description, cover, or rating depends on the source; missing values are not invented.

Open Library supports rating-based sorting but does not support filtering its `ratings_average` field in a search query. With a minimum rating, Java requests source pages of rated books sorted by the source's rating order, then checks each actual average. The app gathers matches toward an 11-book batch. A cursor names the provider page and its first unused matching item, so surplus matches are not skipped. There is no 1,000-match retention cutoff, and an expired cache does not require replaying earlier pages. A bounded 100-page cache keeps memory use limited. Each browse request scans at most six source pages and observes a time budget; sparse matches or an outage can produce a smaller batch with a continuation. Open Library's own availability, search ranking, metadata and pagination still determine which works can be returned.

The passport's five optional keepsakes are story destination, mood after reading, favourite character, memorable quote and personal memory. Each box opens its editor and shows Not added yet when empty. These are private reader-written notes, not public ratings or automatically generated facts.

### Kids and general discovery

Kids is a separate choice in the Genre filter. Filtered Open Library requests include children's/juvenile audience subjects for Kids and exclude them for the other choices. General browse requests the recent original-publication year range and checks audience labels after fetching, avoiding an expensive whole-index negative subject query. The backend checks every returned subject before shortening the visible tags, rejecting children's works from general discovery and gathering the next 11 matching books. It also recognizes the offline collection's five children's classics by work ID. The same separation applies to offline browsing and title searches. For example, choose Kids and search The Hobbit to find that older title. Surprise Me leaves the Kids filter before fetching its random general-audience pick; the chosen rating still applies. Existing saved shelves and passports are retained.

Audience comes from source cataloging, so missing or inconsistent tags can still cause mistakes; this is not an age/content safety filter. A book merely about children is not classified as Kids, and a young-adult label alone is not treated as a children's label. Children’s fiction, picture books, juvenile literature and similar explicit audience subjects are. A small, documented set of work-ID exceptions keeps older-reader novels with conflicting children's tags out of Kids; these rules apply both remotely and after fetching. Source documentation: [Open Library Search API](https://openlibrary.org/dev/docs/api/search).

### Trending now

The extra shelf uses Open Library's current trending sort with `trending_score_hourly_sum:[1 TO *]` and `readinglog_count:[4 TO *]`, matching its homepage Trending Books query. It represents recent activity on that platform across genres, rather than a worldwide sales or review ranking. Selecting a genre narrows the trend results; choosing Kids keeps the children's section separate. A minimum rating filters actual averages while retaining the source's trend order. Titles, ratings and covers are source data.

The shelf loads as it approaches the screen, with 11 matching books per batch and a Load more continuation. It uses the same book-details, account and shelf actions. Aborted/older genre requests cannot replace the new results. There is no background polling, model, additional dependency, paid API or account requirement for browsing. Successful trend pages are cached in the existing bounded in-memory cache for five minutes; Refresh picks rechecks through that cache. If current data cannot be reached, the shelf offers a retry and never calls the offline collection 'trending'.

Source implementation: [Open Library homepage trending query](https://github.com/internetarchive/openlibrary/blob/master/openlibrary/templates/home/index.html) and [trend sorting](https://github.com/internetarchive/openlibrary/blob/master/openlibrary/plugins/worksearch/schemes/works.py).

### Recent suggestions and searches for older books

`PublicationWindow` computes five inclusive calendar years from the current UTC year: 2022–2026 during 2026, advancing automatically with the year. Discovery with a blank title, genre/rating-only browsing, Surprise Me and Trending now send `first_publish_year:[start TO end]` and check the returned work's original publication year again in Java. Records with an unknown year, an older original year or a future year are omitted from those suggestions. A recent reprint's edition year does not qualify an older work. Discovery requests the newest originals first; selected ratings retain their rating order, and Trending retains its trend order within the recent window.

Entering any nonblank title or author is an explicit search and removes the publication window, while keeping the chosen genre, rating and Kids separation. Old classics therefore appear through a specific search; a Classics-only recent browse can legitimately be empty. The same recency filter applies to the offline collection, whose eligible subset can be small or empty rather than being filled with older books. Saved shelves, passports and existing dates are unaffected. The paginated response includes `minPublicationYear` and `maxPublicationYear` for suggestions and null values for all-year searches, so the frontend displays the applied range. Publication metadata is supplied by the catalog and may contain errors; a year is not a guarantee of release or retail availability.

Book Buddy reviews are written by this app's signed-in readers. They are separate from Open Library's aggregate ratings, and no Amazon or Goodreads written reviews are imported. The Amazon button is an ordinary outgoing search link, not an exact-edition guarantee or an affiliate earning link. It uses no retail API and does not retrieve prices.

### English suggestions

Blank discovery, genre/rating-only browsing, Surprise Me and Trending now add `language:eng` to the source query and `lang=en` to the request. The first is a filter; the second is only a preference. The request includes matching edition titles, covers and language codes. Java requires a matching edition tagged `eng` and displays its title and cover instead of the multilingual work defaults. A missing English cover uses the illustrated fallback, and records without confirmed English edition metadata are skipped. English translations qualify regardless of the author's name or original language. Work IDs, ratings and first-publication years are retained, so the five-year rule is about the original work, not its translation/reprint date.

Explicit title/author searches keep all publication years and languages, with an English preference. The bundled offline collection already contains English titles/editions and follows the same recent-year and Kids rules. Existing saved entries are not rewritten. No model, paid service, new dependency or background process is introduced. Language metadata can contain catalog errors. Source: [Open Library Search API, language and matching editions](https://openlibrary.org/dev/docs/api/search).

### Existing reviews from another platform

Book details also load public written review excerpts from **Internet Archive**. The backend uses the exact Open Library work ID to find reviewed text editions with matching `openlibrary_work` metadata. It checks up to two editions and returns up to six attributed excerpts, with original source links. Coverage depends on available records; reviews may discuss the scan or edition rather than the story. Empty results and provider outages are reported separately. Unknown ratings/dates stay unknown. Excerpts are hidden behind a spoiler reveal, rendered as plain text and never mixed into the Book Buddy average.

This uses Internet Archive's free public search and metadata APIs: no API key, billing account, subscription or new dependency is required. Public results are cached in memory for 30 minutes with a bounded cache; no imported reviews are written into H2. Failed/partial reads can be retried. To disable the lookup independently, set `BOOKBUDDY_EXTERNAL_REVIEWS_ENABLED=false` (or Spring property `bookbuddy.external-reviews.enabled=false`). Otherwise it follows the catalog's online/offline setting.

Google Books' public Volume API exposes aggregate rating fields and the authenticated user's own review, not a feed of everyone's written reviews. Goodreads no longer issues new public API keys. The UI offers clearly labelled searches on these sites, while written excerpts displayed in the app come from Internet Archive.

Source documentation: [Internet Archive reviews](https://archive.org/developers/reviews.html), [partial metadata reads](https://archive.org/developers/md-read.html), [Open Library / Archive identifiers](https://docs.openlibrary.org/advanced/archive-org-open-library-synchronisation.html), [Google Books Volume reference](https://developers.google.com/books/docs/v1/reference/volumes), [Goodreads developer notice](https://www.goodreads.com/group/show/8095-goodreads-developers).

## Explore the code

Start with [the code-flow guide](docs/CODE_FLOW.md). It follows one feature from a button click through React, HTTP, Spring, JPA, H2, and back to the screen.

- `frontend/src/App.jsx`: application state and interactions.
- `frontend/src/components`: bookshelf, cards, dialogs, and account form.
- `frontend/src/api.js`: HTTP requests and CSRF tokens.
- `myBooks/myBooks/src/main/java/com/bookshelf/myBooks`: Java controllers, services, entities, and repositories.
- `myBooks/myBooks/src/main/resources/catalog.json`: real books with original summaries and theme tags.

## Check the project

Backend, from `myBooks/myBooks`:

```powershell
.\mvnw.cmd test
```

Frontend, from `frontend`:

```powershell
npm.cmd run lint
npm.cmd run build
```

See [the demo checklist](docs/DEMO_CHECKLIST.md) for browser checks. This project currently runs locally; public deployment and production database migration are separate future steps.

## Troubleshooting

- **Port already in use:** stop your earlier Book Buddy terminal with Ctrl+C, or use Stop-BookBuddy.cmd if the launcher started it. Do not stop every Java/Node process on your work computer.
- **Login expired:** log in again. Each modifying request fetches a fresh CSRF token.
- **No results with a rating filter:** try Any rating. Missing source ratings cannot satisfy a minimum.
- **Open Library offline:** the bundled catalog remains available; cover cards have a built-in illustrated fallback.
- **Launcher failed:** inspect `logs/backend.log` and `logs/backend-error.log`.
- **Changes in VS Code don't match disk:** reload a changed file from disk before saving an older unsaved editor copy.

## Free services and attribution

Book search, community ratings, and cover images use [Open Library](https://openlibrary.org/developers/api) and its [Covers API](https://openlibrary.org/dev/docs/api/covers). Ratings are Open Library community ratings, not Goodreads ratings. Cached catalog metadata is a snapshot and may change. `catalog-sources.json`, when present, records verification URLs and retrieval time.

The optional `scripts/refresh-catalog.ps1` refreshes metadata for the small selected catalog; it is never run automatically. Please respect Open Library's usage limits.

## Later

AI free-text recommendations are intentionally excluded. There is no AI process to install, run, or pay for. Reading Passport is now a working feature for individual completed books; yearly goals and reading quests are not part of this version. Spotify integration, public hosting, and production account features such as email verification/password recovery are outside this localhost version.
