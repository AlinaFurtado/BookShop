import { useEffect, useState } from 'react'
import { api } from '../api'
import Modal from './Modal'
import Icon from './Icon'
import { BookCover, Rating } from './BookCard'
import { STATUSES } from '../bookData'
import ReviewsPanel from './ReviewsPanel'
import ExternalReviewsPanel from './ExternalReviewsPanel'

function localToday() {
  const today = new Date()
  return `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`
}

function FinishDateEditor({ entry, busy, onStatus }) {
  const [date, setDate] = useState(entry.finishDate || '')
  return <form className="finish-date-form" onSubmit={(event) => { event.preventDefault(); onStatus(entry.id, 'READ', date || undefined, !date) }}><label htmlFor="finish-date">Finished on <span>(optional)</span><input type="date" id="finish-date" max={localToday()} value={date} disabled={busy} onChange={(e) => setDate(e.target.value)} /></label><button className="button" disabled={busy} type="submit">Save date</button><button className="text-button" disabled={busy} type="button" onClick={() => { const today = localToday(); setDate(today); onStatus(entry.id, 'READ', today) }}>Finished today</button><p>{entry.finishDate ? `Saved finish date: ${entry.finishDate}` : 'Finish date not recorded. We won’t guess when you read it.'} Clear the field and save to leave the date unknown.</p></form>
}

export default function BookModal({ book, entry, user, busy, onClose, onAdd, onStatus, onRemove, onReviewAuth, onNotify, onOpenPassport }) {
  const [confirmRemove, setConfirmRemove] = useState(false)
  const [finishDate, setFinishDate] = useState('')
  const workId = book.workId || book.id
  const [details, setDetails] = useState(null)
  const [detailsLoading, setDetailsLoading] = useState(true)
  const [detailsError, setDetailsError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    let active = true
    const query = new URLSearchParams({ id: workId, title: book.title, author: book.author || '' })
    api(`/api/books/details?${query}`, { signal: controller.signal, timeout: 22000 }).then((response) => {
      if (active) setDetails(response)
    }).catch((error) => {
      if (active && !controller.signal.aborted) setDetailsError(error.message)
    }).finally(() => { if (active) setDetailsLoading(false) })
    return () => { active = false; controller.abort() }
  }, [workId, book.title, book.author])
  // The details endpoint returns a partial preview. It may have null ratings,
  // dates and covers, so only use its nonblank description to fill a gap.
  const originalDescription = book.description?.trim() || ''
  const fetchedDescription = details?.book?.description?.trim() || ''
  const useFetchedDescription = !originalDescription && !!fetchedDescription
  const displayBook = useFetchedDescription ? { ...book, description: fetchedDescription } : book
  const googleBook = /^googlebooks:/.test(workId || '') || /google/i.test(displayBook.source || '')
  // A provider label for a newly fetched description must not get attached to
  // a different, already saved description. Some Open Library matches were
  // enriched by Google earlier; metadata source alone does not prove authorship.
  const verifiedDescription = useFetchedDescription || (originalDescription && fetchedDescription === originalDescription)
  const descriptionSource = verifiedDescription ? details?.descriptionSource : /^googlebooks:/.test(workId || '') && originalDescription ? 'Google Books' : /curat|buddy/i.test(book.source || '') && originalDescription ? 'Book Buddy catalog' : ''
  const descriptionLabel = /google/i.test(descriptionSource || '') ? 'Google Books' : /open/i.test(descriptionSource || '') ? 'Open Library' : /curat|buddy/i.test(descriptionSource || '') ? 'Book Buddy catalog' : ''
  const hasExternalEditions = /^\/works\/OL\d{1,18}W$/.test(workId || '')
  const hasPassportNotes = [entry?.passportDestination, entry?.passportMood, entry?.passportFavoriteCharacter, entry?.passportQuote, entry?.passportMemory].some(Boolean)
  const amazonLink = `https://www.amazon.in/s?${new URLSearchParams({ k: `${book.title} ${book.author || ''}`.trim(), i: 'stripbooks' })}`
  return (
    <Modal titleId="book-detail-title" onClose={onClose} className="book-modal">
      <div className="book-detail-top"><BookCover book={displayBook} /><div className="book-detail-heading"><span className="eyebrow">YOUR NEXT CHAPTER?</span><h2 id="book-detail-title">{displayBook.title}</h2><p className="detail-author">by {displayBook.author}</p><div className="detail-meta"><span className="genre-tag">{displayBook.genre || 'Genre unavailable'}</span><span className="external-rating"><Rating book={displayBook} /><small>{googleBook ? 'Google Books' : 'Open Library'}</small></span>{displayBook.year && <span>{googleBook ? 'Edition: ' : ''}{displayBook.year}</span>}</div>{displayBook.reason && <p className="detail-reason"><Icon name="spark" size={16} />{displayBook.reason}</p>}</div></div>
      <p className="book-description">{displayBook.description || (detailsLoading ? 'Peeking inside the book jacket…' : 'A description is not available for this title yet.')}</p>
      <div className="description-attribution" aria-live="polite">{detailsLoading && <span><span className="spinner" />Looking for more book details…</span>}{!detailsLoading && displayBook.description && descriptionLabel && <span>Description: {descriptionLabel}.</span>}{!detailsLoading && details?.note && (useFetchedDescription || !displayBook.description) && <span>{details.note}</span>}{detailsError && <span>Additional details could not be loaded. Your shelf and reader reviews are still available.</span>}</div>
      <div className="book-copy-link"><p>Found your next chapter?</p><a className="amazon-copy-link" href={amazonLink} target="_blank" rel="noopener noreferrer"><span>Get your copy on<br /><strong>Amazon India ↗</strong></span><Icon name="arrow" size={17} /><span className="sr-only"> (opens a book search in a new tab)</span></a></div>
      <div className="book-detail-actions">
        {entry ? <>
          <p className="detail-action-label">Where does it belong?</p>
          <div className="status-buttons">{STATUSES.map((status) => <button className={`button status-choice ${entry.status === status.value ? 'selected' : ''}`} key={status.value} disabled={busy} aria-pressed={entry.status === status.value} onClick={() => onStatus(entry.id, status.value)}><Icon name={status.icon} size={15} />{status.label}</button>)}</div>
          {entry.status === 'READ' && <FinishDateEditor key={`${entry.id}:${entry.status}:${entry.finishDate || ''}`} entry={entry} busy={busy} onStatus={onStatus} />}
          {entry.status === 'READ' && <button className="button open-passport-button" disabled={busy} onClick={() => onOpenPassport(entry)}><Icon name="book" size={16} />Open book passport <Icon name="arrow" size={15} /></button>}
          {confirmRemove ? <div className="remove-confirm"><span>{hasPassportNotes ? 'Remove this book and its private passport notes from your shelf?' : 'Remove this book from your shelf?'}</span><div><button className="text-button" disabled={busy} onClick={() => setConfirmRemove(false)}>Keep it</button><button className="button button-coral small-button" disabled={busy} onClick={() => onRemove(entry.id)}>{busy ? 'Removing…' : 'Yes, remove'}</button></div></div> : <button className="text-button remove-link" disabled={busy} onClick={() => setConfirmRemove(true)}><Icon name="trash" size={14} />Remove from my shelf</button>}
        </> : <>
          <div className="direct-save-buttons"><button className="button button-lime" disabled={busy} onClick={() => onAdd(displayBook, 'WANT_TO_READ')}><Icon name="plus" size={16} />Want to Read</button><button className="button button-purple" disabled={busy} onClick={() => onAdd(displayBook, 'READ', finishDate || null)}><Icon name="check" size={16} />Already Read</button></div>
          <label className="new-finish-date" htmlFor="new-finish-date">Already finished it? Add a date if you remember.<input id="new-finish-date" type="date" max={localToday()} value={finishDate} disabled={busy} onChange={(e) => setFinishDate(e.target.value)} /></label>
        </>}
      </div>
      {hasExternalEditions ? <ExternalReviewsPanel book={displayBook} /> : <section className="external-reviews-panel"><span className="eyebrow">OPINIONS FROM BEYOND OUR SHELF</span><h3>External editions unavailable<span className="purple"> ✦</span></h3><p className="reviews-source">This Google Books match has no verified Open Library edition link for external review excerpts. You can read and share Book Buddy reviews below. The Google Books star rating, when present, stays separate.</p></section>}
      <ReviewsPanel workId={workId} user={user} onAuth={onReviewAuth} onNotify={onNotify} />
    </Modal>
  )
}
