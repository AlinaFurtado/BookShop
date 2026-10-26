# Verification record

## 27 October 2026: hosted AI and Google Books integration

### Main search routing correction

- The main search now selects AI for obvious reading requests and offers
  explicit Title or author / Describe a book modes. AI errors and sign-in
  requirements do not substitute literal Open Library matches.
- Four Node test groups passed for request classification, real title/author
  exceptions, ambiguous text and invalid input. Frontend lint/build passed.
- AI results display below the main form with the submitted request and filters.
  Provider behavior still requires a real end-to-end check after deployment.

- Final React lint and production build passed for the new Pip panel, result
  cards, Google cover/rating labels and detail enrichment.
- The backend package/test command was requested and declined. The new Java
  sources and tests have been statically reviewed, but have **not** been compiled
  or executed locally. No successful backend result is claimed for this update.
- New tests use local HTTP stubs and disposable H2 databases. They cover Groq
  strict output/evidence validation, quota and cache boundaries, Google metadata,
  exact Open Library identity/description reads, recent original-year and Kids
  filters, and Google-ID shelf/passport/review operations.
- Provider keys remain in Render. No live Groq/Google request or Neon mutation
  was performed during development. End-to-end provider behavior and actual
  recommendation quality still need checking after Render builds the commit.
- No local model, dependency installation or database schema change was added.
  The current running website is unchanged until the new sources are deployed.

The sections below are historical verification results for earlier versions;
they do not substitute for testing this integration.

## Earlier verified local build

Verified on 15 October 2026 on this Windows laptop.

- Java: `mvnw.cmd package` passed, including 5 tests across `BookBuddyApiTests` and `MyBooksApplicationTests`; zero failures/errors/skips.
- React: `npm.cmd run lint` and `npm.cmd run build` passed.
- Packaged jar contains the compiled React index and assets and serves them directly from Spring Boot.
- Browser checks on a separate in-memory H2 test server: guest browse; create account while saving a selected book; move books to Reading and Read; retain status on refresh; title search outside the bundled catalog (Dune); author search (Andy Weir); combined Science Fiction/4.5+ filter; removal confirmation; logout clears shelf; login restores it.
- Responsive layout inspected at a mobile breakpoint as well as the normal desktop view.
- Actual installed start/stop commands tested: stopping releases port 8080, H2 can be read, restarting serves frontend and API successfully.
- The installed H2 database had zero shelf rows and zero accounts at the upgrade check, matching the earlier empty shelf. Browser QA accounts were isolated and were not copied into the installed database.
- Earlier source and H2 database preserved under `backups/before-website-upgrade-20261015-172349`.
- Temporary QA server stopped. Final app runs as one launcher-managed Java process at localhost:8080. No AI service/model was installed or started.

This is a localhost build. Public hosting and AI recommendations are not part of this version. A 512 MB Java heap cap is not a cap on total process memory; resource use can vary with activity.

## Independent filter update

- Java package build passed all 13 tests, including 8 catalog tests using a local mock HTTP server.
- Tests cover rating-only search, genre-only search including unrated books, combined filters, separate cache entries for different ratings, pagination, initial highlights, and offline fallback.
- React lint and production build passed.
- Filtering now searches the wider Open Library catalog. Results are paginated; rating-filtered totals are not guessed because exact averages are checked after retrieval.

## Reader features update

- Java package build passed all 18 tests with zero failures, errors or skips. React lint and production build passed.
- Catalog tests cover the 11-book display target, retaining surplus matches without skipping works, bounded requests for sparse matching data, and offline fallback.
- New HTTP tests use an isolated in-memory H2 database to verify atomic status selection, unknown and recorded finish dates, future-date rejection, explicit clearing, review validation, real averages, CSRF/login requirements, and review ownership for editing/deleting.
- No paid APIs, local AI model, retail scraping, or imported written reviews were added. Amazon India is an ordinary outgoing search link.
- Reading Passport remains a separate interactive concept and is not enabled in the live application. No Reading Quest was added.

## Per-book Reading Passport update

- The passport is now implemented in the application for individual completed books.
- All 21 backend tests passed with zero failures, errors or skips, including three new real-HTTP passport tests using a separate in-memory H2 database.
- New tests cover existing Read entries with unknown finish dates, saving/reloading private reflections, partial edits and clearing, note retention through reading-status changes, ownership, CSRF, metadata protection and validation limits.
- The identity page uses the saved book's metadata. User reflection fields are initially blank, and no completion date or memory is invented.
- Passport reflections stay on the private shelf entry and do not appear in public review or catalog responses.
- React lint and production build passed. The verified frontend assets were packaged with the tested backend.
