import { useState } from 'react'
import { STATUSES, bookStyle } from '../bookData'
import Icon from './Icon'

const SPINES_PER_PAGE = 12

export default function Bookshelf({ user, entries, loading, error, onOpen, onAuth, onDiscover, onRetry, onViewAll }) {
  const [pages, setPages] = useState({})
  return (
    <section className="panel shelf-panel" id="my-shelf" aria-labelledby="shelf-title">
      <div className="panel-heading">
        <div><span className="eyebrow">A HOME FOR YOUR STORIES</span><h2 id="shelf-title">My wonderfully chaotic shelf<span className="purple">.</span></h2></div>
        <span className="count-pill">{entries.length} {entries.length === 1 ? 'book' : 'books'}</span>
      </div>
      {user && <button className="text-button shelf-view-all" onClick={onViewAll}>View all my books <Icon name="arrow" size={14} /></button>}
      {error && <div className="inline-error" role="alert">{error}<button className="text-button" onClick={onRetry}>Try loading again</button></div>}
      {loading ? <div className="shelf-loading" role="status"><span className="spinner" /> Dusting off your shelves…</div> : <>
        {!entries.length && <div className="shelf-welcome">
          <span className="welcome-doodle" aria-hidden="true"><Icon name="book" size={31} /><span>✦</span></span>
          <div><strong>Your next obsession goes here.</strong><p>{user ? 'Find a story below and give it a home.' : 'Browse freely. Make an account to keep your own little library.'}</p></div>
          <button className="small-link" onClick={user ? onDiscover : onAuth}>{user ? 'Find a book' : 'Make it yours'} <Icon name="arrow" size={15} /></button>
        </div>}
        <div className="shelf-rows">{STATUSES.map((status) => {
          const books = entries.filter((entry) => entry.status === status.value)
          const pageCount = Math.max(1, Math.ceil(books.length / SPINES_PER_PAGE))
          const page = Math.min(pages[status.value] || 0, pageCount - 1)
          const visibleBooks = books.slice(page * SPINES_PER_PAGE, (page + 1) * SPINES_PER_PAGE)
          return (
            <div className={`shelf-row shelf-${status.value.toLowerCase()}`} key={status.value}>
              <div className="row-heading"><span className="row-icon"><Icon name={status.icon} size={14} /></span><h3>{status.label}</h3><span>{books.length.toString().padStart(2, '0')}</span></div>
              <div className="wooden-shelf">
                <div className="shelf-books">{visibleBooks.length ? visibleBooks.map((book) => (
                  <button key={book.id} className="book-spine" style={bookStyle(book)} onClick={() => onOpen(book)} aria-label={`${book.title} by ${book.author}. ${status.label}. Open details.`}>
                    <span className="spine-stripe" /><span className="spine-title">{book.title}</span><span className="spine-symbol" aria-hidden="true">✦</span>
                  </button>
                )) : <p className="empty-row">{status.value === 'WANT_TO_READ' ? 'So much shelf. So many possibilities.' : status.value === 'READING' ? 'A bookmark is waiting for its moment.' : 'Future you is going to be very well read.'}</p>}</div>
                <div className="wooden-plank" aria-hidden="true" />
              </div>
              {books.length > 0 && <div className="spine-pagination" aria-label={`${status.label} shelf pages`}>
                <button disabled={page === 0} aria-label={`Previous ${status.label} shelf page`} onClick={() => setPages((current) => ({ ...current, [status.value]: page - 1 }))}>← Previous</button>
                <span aria-live="polite">{page * SPINES_PER_PAGE + 1}–{Math.min((page + 1) * SPINES_PER_PAGE, books.length)} of {books.length}</span>
                <button disabled={page + 1 >= pageCount} aria-label={`Next ${status.label} shelf page`} onClick={() => setPages((current) => ({ ...current, [status.value]: page + 1 }))}>Next →</button>
              </div>}
            </div>
          )
        })}</div>
      </>}
      <div className="shelf-footer"><span><span className="tiny-dot" />So many stories. Room for every one.</span><span>Tap a spine to peek inside ↗</span></div>
    </section>
  )
}
