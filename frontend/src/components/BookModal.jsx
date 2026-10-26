import { useState } from 'react'
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
  const hasPassportNotes = [entry?.passportDestination, entry?.passportMood, entry?.passportFavoriteCharacter, entry?.passportQuote, entry?.passportMemory].some(Boolean)
  const amazonLink = `https://www.amazon.in/s?${new URLSearchParams({ k: `${book.title} ${book.author || ''}`.trim(), i: 'stripbooks' })}`
  return (
    <Modal titleId="book-detail-title" onClose={onClose} className="book-modal">
      <div className="book-detail-top"><BookCover book={book} /><div className="book-detail-heading"><span className="eyebrow">YOUR NEXT CHAPTER?</span><h2 id="book-detail-title">{book.title}</h2><p className="detail-author">by {book.author}</p><div className="detail-meta"><span className="genre-tag">{book.genre || 'Genre unavailable'}</span><span className="external-rating"><Rating book={book} /><small>Open Library</small></span>{book.year && <span>{book.year}</span>}</div>{book.reason && <p className="detail-reason"><Icon name="spark" size={16} />{book.reason}</p>}</div></div>
      <p className="book-description">{book.description || 'A description is not available for this title yet.'}</p>
      <div className="book-copy-link"><p>Found your next chapter?</p><a className="amazon-copy-link" href={amazonLink} target="_blank" rel="noopener noreferrer"><span>Get your copy on<br /><strong>Amazon India ↗</strong></span><Icon name="arrow" size={17} /><span className="sr-only"> (opens a book search in a new tab)</span></a></div>
      <div className="book-detail-actions">
        {entry ? <>
          <p className="detail-action-label">Where does it belong?</p>
          <div className="status-buttons">{STATUSES.map((status) => <button className={`button status-choice ${entry.status === status.value ? 'selected' : ''}`} key={status.value} disabled={busy} aria-pressed={entry.status === status.value} onClick={() => onStatus(entry.id, status.value)}><Icon name={status.icon} size={15} />{status.label}</button>)}</div>
          {entry.status === 'READ' && <FinishDateEditor key={`${entry.id}:${entry.status}:${entry.finishDate || ''}`} entry={entry} busy={busy} onStatus={onStatus} />}
          {entry.status === 'READ' && <button className="button open-passport-button" disabled={busy} onClick={() => onOpenPassport(entry)}><Icon name="book" size={16} />Open book passport <Icon name="arrow" size={15} /></button>}
          {confirmRemove ? <div className="remove-confirm"><span>{hasPassportNotes ? 'Remove this book and its private passport notes from your shelf?' : 'Remove this book from your shelf?'}</span><div><button className="text-button" disabled={busy} onClick={() => setConfirmRemove(false)}>Keep it</button><button className="button button-coral small-button" disabled={busy} onClick={() => onRemove(entry.id)}>{busy ? 'Removing…' : 'Yes, remove'}</button></div></div> : <button className="text-button remove-link" disabled={busy} onClick={() => setConfirmRemove(true)}><Icon name="trash" size={14} />Remove from my shelf</button>}
        </> : <>
          <div className="direct-save-buttons"><button className="button button-lime" disabled={busy} onClick={() => onAdd(book, 'WANT_TO_READ')}><Icon name="plus" size={16} />Want to Read</button><button className="button button-purple" disabled={busy} onClick={() => onAdd(book, 'READ', finishDate || null)}><Icon name="check" size={16} />Already Read</button></div>
          <label className="new-finish-date" htmlFor="new-finish-date">Already finished it? Add a date if you remember.<input id="new-finish-date" type="date" max={localToday()} value={finishDate} disabled={busy} onChange={(e) => setFinishDate(e.target.value)} /></label>
        </>}
      </div>
      <ExternalReviewsPanel book={book} />
      <ReviewsPanel workId={workId} user={user} onAuth={onReviewAuth} onNotify={onNotify} />
    </Modal>
  )
}
