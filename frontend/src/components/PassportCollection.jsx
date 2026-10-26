import { useState } from 'react'
import { BookCover } from './BookCard'
import Icon from './Icon'

const PAGE_SIZE = 12

export default function PassportCollection({ entries, user, loading, error, onRetry, onOpen, onAuth, onShelf }) {
  const [query, setQuery] = useState('')
  const [year, setYear] = useState('')
  const [page, setPage] = useState(0)
  const completed = entries.filter((entry) => entry.status === 'READ')
  const years = [...new Set(completed.map((entry) => entry.finishDate?.slice(0, 4)).filter(Boolean))].sort().reverse()
  const effectiveYear = year === 'unknown' || years.includes(year) ? year : ''
  const books = completed.filter((entry) => `${entry.title} ${entry.author}`.toLowerCase().includes(query.trim().toLowerCase()) && (!effectiveYear || (effectiveYear === 'unknown' ? !entry.finishDate : entry.finishDate?.startsWith(effectiveYear))))
    .sort((a, b) => (b.finishDate || '').localeCompare(a.finishDate || '') || a.title.localeCompare(b.title))
  const lastPage = Math.max(0, Math.ceil(books.length / PAGE_SIZE) - 1)
  const currentPage = Math.min(page, lastPage)
  const visibleBooks = books.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)

  return (
    <section className="panel passport-collection" aria-labelledby="passports-title">
      <div className="passport-collection-heading"><div><span className="eyebrow">THE STORIES THAT STAY WITH YOU</span><h2 id="passports-title">My reading passports<span>✦</span></h2></div><span className="passport-count">{completed.length} {completed.length === 1 ? 'book' : 'books'} completed</span></div>
      <p className="passport-collection-intro">A little booklet for every book you’ve read. Keep its worlds, characters, and memories close.</p>
      {error && <div className="inline-error" role="alert">{error}<button className="text-button" onClick={onRetry}>Try loading again</button></div>}
      {completed.length > 0 && <div className="passport-collection-filters"><label>Find a book<input type="search" placeholder="Search title or author…" value={query} onChange={(e) => { setQuery(e.target.value); setPage(0) }} /></label><label>Finished in<select value={effectiveYear} onChange={(e) => { setYear(e.target.value); setPage(0) }}><option value="">Any year</option>{years.map((item) => <option key={item}>{item}</option>)}<option value="unknown">Date not recorded</option></select></label></div>}
      {loading ? <div className="shelf-loading" role="status"><span className="spinner" />Opening your book passports…</div> : error && !entries.length ? <p className="passport-load-failed">Your passports couldn’t be loaded. Try again to see your saved books and notes.</p> : books.length ? <><p className="passport-collection-count" aria-live="polite">Showing {currentPage * PAGE_SIZE + 1}–{Math.min((currentPage + 1) * PAGE_SIZE, books.length)} of {books.length} passports</p><div className="passport-cards">{visibleBooks.map((book) => {
        const notes = [book.passportDestination, book.passportMood, book.passportFavoriteCharacter, book.passportQuote, book.passportMemory].filter(Boolean).length
        return <article className="passport-card" key={book.id}>
          <button className="passport-card-cover" onClick={() => onOpen(book)} aria-label={`Open reading passport for ${book.title}`}><BookCover book={book} /><span className="passport-card-seal" aria-hidden="true"><Icon name="check" size={14} />READ</span></button>
          <div className="passport-card-copy"><span className="passport-card-kicker">A BOOK. A WHOLE JOURNEY.</span><h3>{book.title}</h3><p>{book.author}</p><span className="passport-card-date">{book.finishDate ? `Finished ${book.finishDate}` : 'Finish date not recorded'}</span><small>{notes ? `${notes} private ${notes === 1 ? 'note' : 'notes'} tucked inside` : 'Your private notes await'}</small><button className="text-button" onClick={() => onOpen(book)}>Open book passport <Icon name="arrow" size={14} /></button></div>
        </article>
      })}</div><div className="library-page-controls"><button className="button" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>← Previous</button><span>Page {currentPage + 1} of {lastPage + 1}</span><button className="button" disabled={currentPage >= lastPage} onClick={() => setPage(currentPage + 1)}>Next →</button></div></> : <div className="passport-empty"><div className="passport-empty-book" aria-hidden="true"><Icon name="book" size={39} /><span>READING<br />PASSPORT</span></div><h3>{completed.length ? 'No passports match that search.' : 'Every finished book gets a passport.'}</h3><p>{completed.length ? 'Try another title, author, or finish year.' : 'Mark a book as Read to open its own booklet. The book’s details are already there; the memories are yours to add.'}</p>{!user ? <button className="button button-purple" onClick={onAuth}>Sign in to see my passports <Icon name="arrow" size={15} /></button> : !completed.length ? <button className="button button-lime" onClick={onShelf}>Back to my shelf <Icon name="arrow" size={15} /></button> : null}</div>}
      <p className="passport-collection-privacy"><Icon name="shield" size={13} />Your passport notes are private to your account.</p>
    </section>
  )
}
