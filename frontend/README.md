# Book Buddy frontend

A React app for browsing real books and managing a private reading shelf. The
paper colors, wooden shelves, animated Pip goblin, and illustrated fallback
covers are built with CSS and small SVG icons. No UI subscription, external font
service, paid API, language model, or AI text search is used.

## Run locally

For the simplest full application startup, use `Start-BookBuddy.cmd` in the
project root. The built frontend is served by Spring Boot on port 8080.

For frontend development, keep the backend running, open a second terminal in
this `frontend` folder, then run:

```powershell
npm.cmd ci
npm.cmd run dev
```

Open `http://127.0.0.1:5173`. Vite proxies `/api` to the backend on port 8080.
Use `BOOKBUDDY_API_TARGET` to point the proxy to another local backend port.

```powershell
$env:BOOKBUDDY_API_TARGET = 'http://127.0.0.1:8081'
npm.cmd run dev -- --port 5174
```

## Find your way through the code

| File | Responsibility |
| --- | --- |
| `src/App.jsx` | Account, shelf, catalog state and the actions connecting components to API calls |
| `src/api.js` | Fetch requests, JSON, credentials, CSRF headers, timeouts, and readable errors |
| `src/bookData.js` | Reading status constants and stable colors for each book |
| `src/components/Bookshelf.jsx` | Groups saved entries and shows 12 spines per status page |
| `src/components/BookCard.jsx` | Catalog card, cover, rating, and direct Want to Read / Already Read actions |
| `src/components/BookModal.jsx` | Book details, status, optional finish date, removal, and outgoing shop links |
| `src/components/LibraryModal.jsx` | All saved books with search, status/genre/year filters, sorting and grid/list layouts |
| `src/components/PassportCollection.jsx` | Every Read book's passport, with search, finish-year filtering and 12 cards per page |
| `src/components/PassportModal.jsx` | Book identity and five private editable journey/souvenir fields |
| `src/components/ReviewsPanel.jsx` | Genuine Book Buddy reviews, community average, spoilers and the reader's own edit/delete controls |
| `src/components/AuthModal.jsx` | Registration and sign-in forms |
| `src/components/Modal.jsx` | Native dialog, keyboard focus, Escape, and background interaction |
| `src/components/PipPanel.jsx` | Animated mascot, reading tips and explicit genre buttons |
| `src/App.css` | Page layout and component styles, including small-screen layouts |
| `src/index.css` | Shared colors, typography, focus rings and reduced-motion preference |

## Trace one action

1. Click **Want to Read** in `BookCard`.
2. `App.addBook` checks whether the reader is signed in. A guest sees the account
   dialog, and the selected book, intended status, and optional date are
   remembered until authentication succeeds.
3. `App.performAdd` calls `api('/api/shelf', ...)` with the catalog book.
4. `api.js` fetches the CSRF token, then sends JSON to Spring Boot.
5. The backend saves the row and returns its numeric database `id`.
6. `setShelf` adds that saved entry to React state. React redraws `Bookshelf`.
7. A refresh loads `/api/auth/me` and `/api/shelf`, restoring the saved entries.

The catalog book's `id` is an Open Library key, such as `/works/OL27482W`.
On a saved shelf entry it becomes `workId`; its numeric `id` identifies our
database row. Status changes and deletion use the numeric `id`.

Status updates use **PATCH** and replace a matching entry with `map`. Removing
an entry uses **DELETE**, then `filter` keeps every other entry. Catalog searches
cancel older requests so a slow earlier response cannot replace newer results.

The catalog displays up to 11 books initially and requests another batch when
you choose **Load 11 more books**. `App.loadResults` keeps the successful search
and the server's opaque `nextCursor`; changing input without submitting cannot
accidentally change the next page. Duplicate Open Library work IDs are removed
when results are appended. A sparse upstream catalog can return a smaller batch.

Finish dates are optional. An unknown date stays unknown rather than becoming
today. The date editor sends an ISO date when provided, or `clearFinishDate` to
remove a previously recorded date. Moving a book out of Read clears its finish
date, and a newly mounted date editor reflects that server response.

Marking a book Read opens its own reading passport. Its identity comes from the
saved book; the destination, mood, favorite character, quote and memory are
written by the reader. `App.savePassport` PATCHes only those five fields, then
replaces the saved shelf entry from the server response. Unknown finish dates
remain unknown. Failed saves keep the draft open, and every exit from a dirty
passport asks whether to discard its unsaved changes. These notes are private;
they are not community reviews.

Book Buddy review ratings belong to this application and are displayed
separately from Open Library's reader rating. `ReviewsPanel` uses `/api/reviews`
to fetch real reviews and their average; it never imports retailer reviews or
seeds made-up comments. Creating, editing and deleting require a signed-in
account. The server enforces review ownership, and spoiler text starts collapsed.

## Checks

```powershell
npm.cmd run lint
npm.cmd run build
```

`build` generates `dist`, which the backend build copies into its packaged
static resources. Source files remain in `src`; generated `dist` is not where
you should make changes.

The only direct remote request from the frontend is for optional cover images
from Open Library. Account and catalog API requests go through our own backend.
Missing ratings are shown as **Not rated**, and minimum-rating filters exclude
unrated books.
