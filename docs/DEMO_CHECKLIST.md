# Browser demo checklist

Use disposable local test accounts while checking behavior; no email is sent.

1. Open localhost:8080 as a guest (localhost:5173 in two-terminal development mode). Confirm the three shelf rows, Pip mascot, discover cards, and responsive layout render.
2. Search for a known title, then an author. Try a search with no matching books.
3. Use genre and minimum-rating filters. Unrated books should not pass a minimum-rating filter.
4. Click Add as a guest. Create an account when prompted; confirm the selected book can be saved.
5. Open a shelf spine. Move the book to Reading, then Read. Confirm the row and counts change immediately.
6. Refresh. Confirm login and the book's status remain.
7. Add the same work again. Confirm there is one shelf entry, not a duplicate.
8. Remove a disposable test entry. Confirm it disappears and remains absent after refresh.
9. Log out and create a second test account. Confirm it cannot see the first account's books.
10. Log back into the first account and confirm its books remain.
11. Check a narrow mobile-sized viewport. Search, filters, shelf controls, and dialogs should remain usable.
12. Tab through controls and open/close a book dialog with the keyboard. Escape closes a dialog and focus returns to its opener.
13. Stop Book Buddy using Stop-BookBuddy.cmd, restart it, and confirm saved books remain. Closing the browser alone does not stop the servers.

## Per-book passports

1. Mark a disposable book Read. Its own passport should open with title, author, cover, genre and catalog reference filled automatically.
2. Confirm that a book already marked Read appears under Reading Passport without adding it again. An unknown finish date should remain unknown.
3. Add your own destination, mood, favourite character, quote and memory; save and refresh. Reopen that book's passport and confirm the notes remain.
4. Clear a note, save, and confirm it stays cleared. Switching between Journey and Souvenirs should preserve unsaved form values.
5. Edit a note and try closing or opening Book details; the form should offer to keep editing or discard before leaving.
6. Move a book to Reading and then back to Read. Its passport notes should be retained.
7. Confirm that a second account cannot see the first reader's passport notes and that private notes never appear in community reviews.
8. With a larger collection, use search, finish-year selection and page controls to find completed books.
9. Confirm the book detail dialog has a compact Amazon button and no Explore on Open Library link. The rating's source attribution should still be visible.

The automated Java tests cover server behaviors separately from this visual checklist. There is no AI free-text feature or local model in this build.
# Retrieval correction: check after the new Render deployment

Use the public HTTPS site, sign in, and perform these checks:

1. In **Auto**, enter `Fourth Wing`. It should choose **Title or author** and
   ordinary discovery should report **Google Books** when its request succeeds.
   Check **Search connections** for the real provider outcome, not just the
   configured booleans from `/api/recommendations/status`.
2. Replace the draft with `a book like Fourth Wing`. Auto must choose AI. Submit
   it once. A resolved reference should show the book and author; returned picks
   should not be Fourth Wing itself or a summary/workbook/study guide.
3. Check the provider cards. `Unavailable`, `Not configured` and `Quota reached`
   are problems to investigate, not evidence that no suitable books exist.
   Keep keys and raw server/provider error bodies private.
4. Click **Find different picks**. It must explicitly search again, keep the
   submitted filters and exclude recent displayed IDs. More matches depend on
   available source records and the shared free allowance.
5. Enter a different request, such as `I want a cosy mystery without horror`.
   Earlier cards should disappear while the draft is unsent, and the new result
   must show this request. Genre/rating changes alone must not submit an AI call.
6. Browse Fantasy or Mystery with an empty title. The default range stays the
   last five calendar years. Google years say **Edition published**; original
   years can differ. An explicit title search may return an older book.
7. Load more ordinary results. Eleven-book batches retain provider overflow and
   merge duplicate title/author editions. Save a Google book, mark it Read, open
   its passport and refresh to check persistence.

These live checks are pending until the user uploads and deploys the update.

