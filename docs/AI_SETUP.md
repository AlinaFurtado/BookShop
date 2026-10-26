# Pip recommendations: setup and code flow

Pip can accept a description such as “A cosy fantasy about friendship, no horror.”
This is optional. Ordinary title/author search, filters, shelves, passports and
Book Buddy reviews continue to work without an AI key.

## Enable it on the existing Render service

In the service's **Environment** section, save these secrets privately:

| Variable | Purpose |
| --- | --- |
| `GROQ_API_KEY` | Hosted preference extraction and ranking |
| `GOOGLE_BOOKS_API_KEY` | Primary discovery catalog, reference books and descriptions |

Keep the existing Neon variables. Do not paste keys into GitHub, React, a JDBC
URL, a screenshot or this document. No frontend environment variable is needed.
Keep Groq on its Free plan and the Google project unlinked from billing.
Book Buddy cannot control account-level billing settings, so those settings
remain part of maintaining the requested no-charge deployment.

Upload the changed sources to GitHub at their existing paths, then allow Render
to deploy the new commit. Keys saved with **Save only** become available to the
new server process on its next deploy/restart. The Dockerfile already packages
React with Spring Boot; no second service or model installation is required.

After the service is Live:

1. Open `/api/recommendations/status`. With the Groq key configured, `available`
   should be `true`; `googleBooksAvailable` indicates Google is configured.
   This endpoint checks configuration, not live provider readiness, and spends
   no provider quota.
2. Sign in and use the main search's **Describe a book · AI** mode, then press
   **Ask Pip**. Obvious requests such as “I need a fantasy book with dragons”
   automatically select this mode before submission, as do “a book like Fourth
   Wing” and “books similar to Fourth Wing”. **Auto** shows the detected route;
   **Title or author** is an explicit override for the current draft. Editing
   that draft returns to Auto. Pip's separate form also works.
3. Check that up to four real books appear with catalog evidence, source labels
   and the chosen filters. Fewer or no books is a valid result when metadata is
   insufficient; no book or rating is fabricated.
4. Open a book's details, then save it to Want to Read or Already Read. Confirm
   it remains after refresh. Completed books keep the normal passport flow.
5. Inspect **Search connections** after a request. It distinguishes a successful
   provider response, no matches, filtered records, configuration problems,
   quota limits and temporary failures. These outcomes describe that request;
   the `/status` booleans alone do not verify a working provider connection.
6. To explore another batch for the same request, click **Find different picks**.
   It explicitly bypasses the AI response cache and excludes up to 12 recently
   shown IDs. This uses the ordinary shared allowance; more matches are not
   guaranteed. Repeating the same ordinary submit can reuse its labelled cache.

Google improves metadata but does not provide a public feed of everyone's
written Google reviews. Existing verified Internet Archive excerpts and
Book Buddy readers' reviews remain separate. Goodreads/Google links, where
shown, open their sites rather than importing their review databases.

## Follow one request through the code

```text
App.jsx main search or PipPanel.jsx: reader types a description and explicitly submits
  -> App.jsx: POST /api/recommendations with prompt + filters
  -> api.js: same-origin session cookie and fresh CSRF token
  -> RecommendationController: login, 8 KiB body limit, JSON conversion
  -> RecommendationService: validate, reader-specific cache, request allowance
  -> GroqRecommendationClient.extract: structured genre/keywords/avoids
  -> If a named reference exists: resolve its real catalog record
  -> Optional grounded refinement: derive traits from that record's metadata
  -> RecommendationCatalog: Google Books first + Open Library support
  -> Remove the reference book, study aids, duplicates and excluded previous IDs
  -> GroqRecommendationClient.rank: choose only supplied candidate IDs
  -> Java checks IDs, duplicates and literal catalog evidence
  -> React renders up to four BookCards with existing shelf/detail actions
```

`searchIntent.js` recognizes conservative request phrasing in the main input.
It does not call a provider while the reader types. AI and literal catalog
results have separate labels; an unavailable AI request never falls back to
searching the full sentence as a title. AI filter changes wait for explicit
submission, and the displayed results retain their submitted prompt/filters.
Signing in preserves the request but does not send it automatically.

The model is `openai/gpt-oss-20b` hosted by Groq. Java makes HTTP requests; it
does not download or run a model on the laptop or Render instance. At most three
bounded AI calls are possible per fresh search, including optional reference
refinement. No web-search tools, automatic transport retries,
paid model fallback or new runtime dependencies are added.

The model is not a book database and does not search the whole internet.
Descriptions, authors, ratings and IDs come from the configured catalogs.
Returned explanations display an exact phrase from supplied catalog metadata,
so a generated plot summary cannot silently become a book fact.

### Filters and publication years

- Suggestions require a verified English edition.
- The default date window is five inclusive calendar years: in 2026 this means
  2022–2026. Google records use `publishedDate`, which describes an **edition**;
  Open Library records use original first-publication years. The UI distinguishes
  those dates. Known old originals matched in available metadata are rejected
  even if Google lists a new edition. An unknown original year is not proof of
  a new original, and the app does not present it as one.
- Google candidates no longer require an Open Library duplicate. This removes
  the earlier bottleneck that discarded most independently valid Google matches.
- **Include older books, too** removes the recent-year condition. Known future
  publications remain omitted.
- The selected genre and minimum rating remain authoritative. Missing ratings
  are excluded only when a minimum is selected. Ratings from different sources
  are not combined.
- Kids requires explicit selection of the Kids genre. General recommendations
  reject records classified as children's books by available source metadata.
  Incomplete provider classification can still require manual review.
- Avoids are checked against whole words/phrases in available metadata and sent
  to the ranker. Missing content warnings cannot prove that a book is free of a
  topic, so this is not a content-safety guarantee.

### Ordinary catalog browsing

Google Books is the primary source when configured. Blank discovery requests
recent English editions ordered newest first; selected genres and minimum
ratings still apply. Study guides, summaries, workbooks, magazines and blank
journals are filtered out. Known old originals are not promoted as new simply
because a recent reprint exists. Explicit title/author searches allow older
editions; Google still requires English metadata. Open Library fallback retains
its original language behavior.

Display pages target 11 matches and preserve overflow from each 20-record Google
page using `gb:page:offset` cursors. Browsing is bounded to Google's exposed
search window rather than claiming to enumerate its entire database. Google
totals are not shown as exact filtered counts. Appended duplicate editions are
merged by title and known author.

A Google configuration, quota or network failure can start a clearly labelled
Open Library fallback. A successful Google response with no matching records
does not silently change sources. An existing Google continuation keeps its
source and position through errors. Actual **Trending now** remains Open Library
reader activity; Google search relevance is not falsely labelled a live trend.

### Opening details

`BookDetailsController` first uses an existing bundled description or requests
the exact Open Library work. When that has no description, it verifies the
work's title and author before trying a Google Books match. Google-volume IDs
are also checked against the provider record. Missing or mismatched records
stay unavailable. Descriptions are rendered as text, with source attribution;
the modal never replaces a known cover, rating, publication year or work ID
with a partial details response.

Provider IDs remain distinct from database row IDs. `googlebooks:VolumeId`
is accepted alongside `/works/OL123W` and existing curated IDs. Google editions
and Open Library works without a verified crosswalk remain separate records;
the app does not assume that matching-looking titles identify the same edition.

### Allowances and performance

| Guard | Limit |
| --- | --- |
| Shared fresh AI searches | 40 per UTC day |
| Fresh AI searches per signed-in reader | 10 per UTC day; 3 per minute |
| Concurrent AI searches | 1; another reader gets a retry message |
| Identical AI response cache | 20 minutes, scoped to reader, prompt, filters and exclusions |
| Google Books outgoing requests | 600 per UTC day; 20 per minute per process |
| Prompt | 10–600 characters |

Attempts that reach the provider consume the local allowance even if they fail.
Cache hits do not. These small counters and caches are in server memory and
reset on restart. They are guardrails for this single-instance demo, not a
distributed billing meter. Groq organization and Google project quotas remain
authoritative and may stop requests earlier. Free allowances can change.

HTTP calls have timeouts and bounded responses. The catalog phase has a soft
18-second budget for starting supporting lookups; lock waits and active calls
can extend total time. The browser waits up to 75 seconds for recommendations.
Cancelling stops its wait; a server/provider request may still finish and use
quota. Opening details may take several provider calls and can time out without
affecting shelf actions.

No scheduled model jobs, keep-alive polling or local model processes are added.
Hosting runs on Render. A local Java build or running the local website still
uses ordinary CPU/RAM; hosted AI does not make local activity resource-free.

Only the typed reading request, extracted preferences, chosen filters and public
candidate metadata are sent to Groq. Emails, account credentials, saved shelves,
reviews and private passport notes are not included. Prompts and provider keys
are not logged by this integration; response caches use hashed request keys.

## Disable or troubleshoot

- Set `BOOKBUDDY_AI_ENABLED=false` to disable AI requests.
- Set `BOOKBUDDY_GOOGLE_BOOKS_ENABLED=false` to disable Google lookups.
- `BOOKBUDDY_CATALOG_ONLINE=false` disables both new remote integrations as
  well as ordinary online catalog discovery.
- Missing keys disable their respective integration. They are never sent to
  React; `/status` returns booleans and a safe message only.
- HTTP `429` means an app allowance or concurrent request limit was hit.
  Wait as instructed; repeatedly clicking does not bypass the limit.
- HTTP `503` means AI is disabled. A failed provider call can instead return
  an empty recommendation response with an explicit provider outcome and safe
  error message. It is not described as a successful search with no matches.
- A Google rejection reports API/key restrictions separately from recognised
  quota errors. Its short cooldown avoids repeatedly spending requests on a
  rejected configuration. Correct the settings privately in Google/Render.
  Provider keys, URLs and raw error bodies never appear in diagnostics.
- Google's API key should allow only **Books API**. These calls originate in
  Java, so browser website-referrer restrictions do not apply to them.

## Provider references

- [Groq structured outputs](https://console.groq.com/docs/structured-outputs)
- [Groq reasoning parameters](https://console.groq.com/docs/reasoning)
- [Groq current rate limits](https://console.groq.com/docs/rate-limits)
- [Google Books API use](https://developers.google.com/books/docs/v1/using)
- [Google Books volume fields](https://developers.google.com/books/docs/v1/reference/volumes)
- [Google API key restrictions](https://docs.cloud.google.com/api-keys/docs/add-restrictions-api-keys)
