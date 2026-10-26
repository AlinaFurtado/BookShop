import { useRef, useState } from 'react'
import Modal from './Modal'
import Icon from './Icon'
import { BookCover } from './BookCard'
import './ReadingPassport.css'

const PAGES = ['Identity', 'Destinations', 'Immigration', 'Visa pages']
const DESTINATIONS = [
  { id: 'fantasy', name: 'Fantasy Realm', stamp: 'FANTASY', glyph: '🏰', caption: 'Dragons, taverns & impossible quests' },
  { id: 'mystery', name: 'Mystery Borough', stamp: 'MYSTERY', glyph: '🔎', caption: 'Secrets tucked between the pages' },
  { id: 'orbit', name: 'Orbit & Beyond', stamp: 'ORBIT', glyph: '🪐', caption: 'Strange planets, stranger friends' },
]

function fieldsFrom(entry) {
  return {
    destination: entry.passportDestination || '',
    mood: entry.passportMood || '',
    favoriteCharacter: entry.passportFavoriteCharacter || '',
    quote: entry.passportQuote || '',
    memory: entry.passportMemory || '',
  }
}

function dateLabel(value) {
  if (!value) return 'Date not recorded'
  return new Date(`${value}T12:00:00`).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
}

function PageHead({ label, index = '✦' }) {
  return <div className="bbp-page-head"><span className="bbp-kicker">{label}</span><span className="bbp-page-index">{index}</span></div>
}

function Barcode() {
  return <div className="bbp-barcode" aria-hidden="true">{Array.from({ length: 28 }, (_, i) => <i key={i} />)}</div>
}

// The original demo design, backed by a real READ shelf entry and private H2 notes.
export default function PassportModal({ entry, readerName, busy, onClose, onSave, onViewBook }) {
  const [fields, setFields] = useState(() => fieldsFrom(entry))
  const [finishDate, setFinishDate] = useState(entry.finishDate || '')
  const [savedSnapshot, setSavedSnapshot] = useState(() => JSON.stringify([fieldsFrom(entry), entry.finishDate || '']))
  const [page, setPage] = useState(0)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [ink, setInk] = useState(0)
  const [discardAction, setDiscardAction] = useState(null)
  const [extraOpen, setExtraOpen] = useState(false)
  const discardRef = useRef(null)
  const spreadRef = useRef(null)
  const fieldRefs = useRef({})
  const saveLock = useRef(false)
  const dirty = JSON.stringify([fields, finishDate]) !== savedSnapshot
  const locked = busy || saving
  const destination = fields.destination.trim() || 'Somewhere between the pages'
  const stampName = DESTINATIONS.find((place) => place.name === entry.passportDestination)?.stamp || 'READ'
  const today = new Date()
  const maxDate = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, '0')}-${String(today.getDate()).padStart(2, '0')}`

  function change(name, value) {
    setFields((current) => ({ ...current, [name]: value }))
    setDiscardAction(null)
    setMessage('')
  }

  function go(next) {
    setPage(next)
    requestAnimationFrame(() => spreadRef.current?.scrollIntoView({ block: 'start', behavior: 'instant' }))
  }

  function editKeepsake(name) {
    if (locked) return
    if (name === 'destination') go(1)
    else if (name !== 'memory') setExtraOpen(true)
    requestAnimationFrame(() => {
      const input = fieldRefs.current[name]
      input?.focus({ preventScroll: true })
      input?.scrollIntoView({ block: 'center', behavior: 'instant' })
    })
  }

  function leave(action) { if (action === 'details') onViewBook(entry); else onClose() }
  function requestExit(action) {
    if (locked) return
    if (dirty) {
      setDiscardAction(action)
      requestAnimationFrame(() => discardRef.current?.scrollIntoView({ block: 'nearest' }))
    } else leave(action)
  }

  async function save(action = 'notes') {
    if (locked || saveLock.current) return
    if (finishDate && finishDate > maxDate) { setError('The finish date cannot be in the future.'); return }
    saveLock.current = true
    setSaving(true)
    setError('')
    setMessage('')
    try {
      const saved = await onSave(entry.id, fields, finishDate)
      const next = fieldsFrom(saved)
      setFields(next)
      setFinishDate(saved.finishDate || '')
      setSavedSnapshot(JSON.stringify([next, saved.finishDate || '']))
      setDiscardAction(null)
      if (action === 'board') {
        go(2)
        setMessage('Boarding pass issued. Your story has arrived.')
      } else if (action === 'stamp') {
        setInk((current) => current + 1)
        setMessage('Entry granted! One finished book, one well-earned visa.')
      } else setMessage('Your souvenir is safely tucked away.')
    } catch (err) { setError(err.message) }
    finally { saveLock.current = false; setSaving(false) }
  }

  return (
    <Modal titleId="passport-title" onClose={() => requestExit('close')} className="passport-modal passport-replica">
      <div id="bookbuddy-passport">
        <header className="bbp-header">
          <div className="bbp-logo"><span className="bbp-logo-mark" aria-hidden="true">✦</span><span className="bbp-logo-text">book buddy.</span></div>
          <div className="bbp-header-right"><span>Department of Unnecessary Adventures</span><span className="bbp-demo-pill">Your book passport</span></div>
        </header>
        <div className="bbp-main">
          <div className="bbp-overline"><span id="passport-title">Republic of Stories · Reading Passport</span><span className="bbp-count">1 book · 1 visa earned</span></div>
          <div className="bbp-book" ref={spreadRef}>
            <div className="bbp-spread" key={page}>
              {page === 0 && <>
                <section className="bbp-page bbp-cover" aria-label="Reading passport cover">
                  <div className="bbp-page-inner">
                    <span className="bbp-cover-small">Republic<br />of Stories</span>
                    <div className="bbp-cover-seal" aria-hidden="true">✦</div>
                    <h1>The Reading Passport</h1>
                    <span className="bbp-cover-motto">Every book is somewhere new.</span>
                    <span className="bbp-cover-foot">BOOK BUDDY · EST. 2026</span>
                  </div>
                </section>
                <section className="bbp-page" aria-label="Book identity">
                  <div className="bbp-page-inner">
                    <PageHead label="Page one · Identity" index="01 / 04" />
                    <h2>Officially a story traveller.</h2>
                    <p>One book. Its very own passport. The identity is filled in; the adventure is yours to remember.</p>
                    <div className="bbp-id">
                      <BookCover book={entry} className="bbp-book-photo" />
                      <div className="bbp-id-facts">
                        <div className="bbp-fact"><span className="bbp-label">Book title</span><strong>{entry.title}</strong></div>
                        <div className="bbp-fact"><span className="bbp-label">Written by</span><strong>{entry.author}</strong></div>
                        <div className="bbp-fact"><span className="bbp-label">Genre · {entry.workId?.startsWith('googlebooks:') ? 'Edition published' : 'First published'}</span><strong>{entry.genre || 'Not available'} · {entry.year || 'Not available'}</strong></div>
                      </div>
                    </div>
                    <div className="bbp-fact bbp-reference"><span className="bbp-label">Catalog identity</span><strong>{entry.workId}</strong></div>
                    <div className="bbp-id-bottom"><span className="bbp-script">Valid wherever a good story takes you.<br /><br />Baggage allowance: unlimited feelings.</span><span className="bbp-mini-visa">Reading<br />completed<br />✦</span></div>
                    <div className="bbp-bottom"><button type="button" className="bbp-action" onClick={() => go(1)}>Turn to destinations →</button></div>
                  </div>
                </section>
              </>}

              {page === 1 && <>
                <section className="bbp-page" aria-label="Select reading destination">
                  <div className="bbp-page-inner">
                    <PageHead label="Page two · Portals" index="02 / 04" />
                    <h2>Where to, traveller?</h2>
                    <p>Where did this story take you? Pick a portal, or give your destination its own name.</p>
                    <div className="bbp-portals" role="group" aria-label="Genre destinations">
                      {DESTINATIONS.map((place) => <button type="button" key={place.id} className="bbp-portal" data-destination={place.id} aria-pressed={fields.destination === place.name} disabled={locked} onClick={() => change('destination', place.name)}><span className="bbp-portal-glyph" aria-hidden="true">{place.glyph}</span><span><strong>{place.name}</strong><small>{place.caption}</small></span><span className="bbp-portal-arrow" aria-hidden="true">↗</span></button>)}
                    </div>
                    <label className="bbp-label" htmlFor="bbp-destination">Or write your own destination</label>
                    <input id="bbp-destination" ref={(node) => { fieldRefs.current.destination = node }} className="bbp-input" maxLength={120} value={fields.destination} disabled={locked} onChange={(e) => change('destination', e.target.value)} placeholder="e.g., Middle-earth, Paris, or Fantasy Realm" />
                    <div className="bbp-bottom"><span className="bbp-dept-sticker">No dragons to declare? Suspicious.</span></div>
                  </div>
                </section>
                <section className="bbp-page" aria-label="Issue a boarding pass">
                  <div className="bbp-page-inner">
                    <PageHead label="The departure lounge" />
                    <h2>Issue a boarding pass.</h2>
                    <div className="bbp-ticket">
                      <div className="bbp-ticket-top"><span>Book Buddy Airways</span><span>One way · infinite pages</span></div>
                      <div className="bbp-route"><strong>Here</strong><span aria-hidden="true">➜</span><strong>{fields.destination.trim() || 'Your destination'}</strong></div>
                      <div className="bbp-ticket-line" />
                      <div className="bbp-ticket-foot">Passenger <strong>{readerName || 'Book lover'}</strong> · Class: Daydreamer</div>
                    </div>
                    <span className="bbp-kicker">Your completed adventure</span>
                    <div className="bbp-book-picks"><div className="bbp-pick"><span className="bbp-spine" aria-hidden="true">✦</span><span><strong>{entry.title}</strong><small>{entry.author}</small></span></div></div>
                    <p className="bbp-note">This pass belongs to this book. Your destination is your own choice.</p>
                    <div className="bbp-bottom"><button type="button" className="bbp-action bbp-action-coral" disabled={locked || !fields.destination.trim()} onClick={() => save('board')}>{saving ? 'Issuing your pass…' : 'Issue boarding pass →'}</button></div>
                  </div>
                </section>
              </>}

              {page === 2 && <>
                <section className="bbp-page" aria-label="Current boarding pass">
                  <div className="bbp-page-inner">
                    <PageHead label="Page three · Immigration" index="03 / 04" />
                    <h2>Arrival is a finished book.</h2>
                    <p>You reached the final page. Your story has cleared immigration. Souvenir collection encouraged.</p>
                    <div className="bbp-boarding-pass">
                      <span className="bbp-label">Boarding pass · reader’s copy</span>
                      <div className="bbp-boarding-book">{entry.title}</div>
                      <div className="bbp-boarding-meta">by {entry.author}</div>
                      <div className="bbp-ticket-line" />
                      <div className="bbp-boarding-meta">DESTINATION <strong>{destination}</strong><br />PASSENGER <strong>{readerName || 'Book lover'}</strong><br />BOOK REFERENCE <strong>{entry.workId}</strong></div>
                      <Barcode />
                    </div>
                    <button type="button" className="bbp-link" onClick={() => go(1)}>← Change your destination</button>
                    <div className="bbp-bottom"><span className="bbp-script">One finished book = one visa stamp.<br />No passport queues. No excess baggage fees.</span></div>
                  </div>
                </section>
                <section className="bbp-page" aria-label="Immigration stamping desk">
                  <div className="bbp-page-inner">
                    <PageHead label="Immigration desk · entry stamp" />
                    <h2>Declare your last page.</h2>
                    <div className="bbp-stamp-stage"><div key={ink} className={`bbp-stamp-mark${ink ? ' is-fresh' : ''}`}><span><b>ENTRY GRANTED</b><strong>{stampName}</strong><small>{dateLabel(entry.finishDate)}</small></span></div></div>
                    <label className="bbp-label" htmlFor="bbp-date">Date you finished the book</label>
                    <input id="bbp-date" type="date" className="bbp-input" max={maxDate} value={finishDate} disabled={locked} onChange={(e) => { setFinishDate(e.target.value); setMessage(''); setDiscardAction(null) }} />
                    <span className="bbp-confirm">Already marked Read on your shelf. Add the date if you remember it, or leave it blank. Stamping saves your date and private notes.</span>
                    <button type="button" className="bbp-action bbp-action-coral" disabled={locked} onClick={() => save('stamp')}>{saving ? 'Inking your stamp…' : 'Stamp my passport ✦'}</button>
                    <div className="bbp-bottom"><button type="button" className="bbp-link" onClick={() => go(3)}>Open your memory ticket →</button></div>
                  </div>
                </section>
              </>}

              {page === 3 && <>
                <section className="bbp-page" aria-label="Book visa and keepsakes">
                  <div className="bbp-page-inner">
                    <PageHead label="Page four · Visas" index="04 / 04" />
                    <h2>Somewhere you’ve been.</h2>
                    <p>Your stamp marks this book as finished. The other boxes are optional, private notes. Tap a box to add or edit it.</p>
                    <div className="bbp-visa-grid">
                      <div className="bbp-visa bbp-visa-filled"><span><b>{stampName} ✦</b><small>{dateLabel(entry.finishDate)}<br />{entry.title}</small></span></div>
                      {[
                        { name: 'destination', label: 'Story destination', action: 'destination' },
                        { name: 'mood', label: 'Mood after reading', action: 'mood' },
                        { name: 'favoriteCharacter', label: 'Favorite character', action: 'character' },
                        { name: 'quote', label: 'Memorable quote', action: 'quote' },
                        { name: 'memory', label: 'My memory', action: 'memory' },
                      ].map((item) => <button type="button" key={item.name} className="bbp-visa bbp-keepsake" disabled={locked} onClick={() => editKeepsake(item.name)} aria-label={`${fields[item.name].trim() ? 'Edit' : 'Add'} ${item.label.toLowerCase()}`}><span className="bbp-keepsake-label">{item.label}</span><span className={`bbp-keepsake-value${fields[item.name].trim() ? '' : ' is-empty'}`}>{fields[item.name].trim() || 'Not added yet'}</span><small>{fields[item.name].trim() ? 'Edit' : 'Add'} {item.action} →</small></button>)}
                    </div>
                    <div className="bbp-bottom"><span className="bbp-script">One finished book. One visa.<br />And all the little things it left behind.</span></div>
                  </div>
                </section>
                <section className="bbp-page" aria-label="Reading memory souvenir">
                  <div className="bbp-page-inner">
                    <PageHead label="The souvenir drawer" />
                    <h2>A memory to keep.</h2>
                    <div className="bbp-souvenir"><span className="bbp-label">Keepsake · reader’s copy</span><h3>{entry.title}</h3><p>{destination}<br />{dateLabel(entry.finishDate)}</p></div>
                    <p className="bbp-script">“What will you remember about this place?”</p>
                    <label className="bbp-label" htmlFor="bbp-memory">Your note from the journey</label>
                    <textarea id="bbp-memory" ref={(node) => { fieldRefs.current.memory = node }} className="bbp-input bbp-textarea bbp-memory" rows={6} maxLength={2000} value={fields.memory} disabled={locked} onChange={(e) => change('memory', e.target.value)} placeholder="What did you love, learn, or want to remember about this book?" />
                    <small className="bbp-field-count">{fields.memory.length}/2000</small>
                    <details className="bbp-extra-keepsakes" open={extraOpen} onToggle={(event) => setExtraOpen(event.currentTarget.open)}><summary>Add or edit your mood, character & quote</summary><div className="bbp-keepsake-fields">
                      <label htmlFor="bbp-mood"><span className="bbp-label">Mood after reading</span><input id="bbp-mood" ref={(node) => { fieldRefs.current.mood = node }} className="bbp-input" maxLength={80} value={fields.mood} disabled={locked} onChange={(e) => change('mood', e.target.value)} placeholder="e.g., Comforted, shocked, inspired…" /></label>
                      <label htmlFor="bbp-character"><span className="bbp-label">Favorite character</span><input id="bbp-character" ref={(node) => { fieldRefs.current.favoriteCharacter = node }} className="bbp-input" maxLength={120} value={fields.favoriteCharacter} disabled={locked} onChange={(e) => change('favoriteCharacter', e.target.value)} placeholder="e.g., Bilbo Baggins" /></label>
                      <label htmlFor="bbp-quote"><span className="bbp-label">Memorable quote · optional</span><textarea id="bbp-quote" ref={(node) => { fieldRefs.current.quote = node }} className="bbp-input bbp-textarea" rows={3} maxLength={600} value={fields.quote} disabled={locked} onChange={(e) => change('quote', e.target.value)} placeholder="Write a short quote you’d like to keep…" /><small className="bbp-field-count">{fields.quote.length}/600</small></label>
                    </div></details>
                    <div className="bbp-bottom"><button type="button" className="bbp-action" disabled={locked || !dirty} onClick={() => save()}>{saving ? 'Saving your souvenir…' : 'Save souvenir ✦'}</button><span className="bbp-dept-sticker">Some stories follow you home.</span></div>
                  </div>
                </section>
              </>}
            </div>
          </div>
          <nav className="bbp-tabs" aria-label="Passport pages">{PAGES.map((name, index) => <button type="button" key={name} className="bbp-tab" aria-current={page === index ? 'page' : undefined} disabled={locked} onClick={() => go(index)}><span>0{index + 1}</span>{name}</button>)}</nav>
          <div className="bbp-save-area">
            {error && <p className="inline-error" role="alert">{error} Your edits are still here.</p>}
            {discardAction && <div ref={discardRef} className="bbp-discard-confirm" role="alert"><p>Leave these unsaved changes behind?</p><div><button className="bbp-link" type="button" onClick={() => setDiscardAction(null)}>Keep writing</button><button className="bbp-action bbp-action-coral" type="button" disabled={locked} onClick={() => leave(discardAction)}>{discardAction === 'details' ? 'Discard & open details' : 'Discard changes & close'}</button></div></div>}
            <div className="bbp-save-controls"><button className="bbp-link" type="button" disabled={locked} onClick={() => requestExit('details')}>Book details ↗</button><span className="bbp-save-status" role="status">{saving ? 'Saving your pages…' : message || (dirty ? 'Unsaved changes' : 'Your pages are up to date')}</span>{page !== 3 && <button type="button" className="bbp-action" disabled={locked || !dirty} onClick={() => save()}>Save private notes <Icon name="check" size={14} /></button>}</div>
          </div>
          <p className="bbp-foot">One book. Its own little world. · Private to your account. Your notes are never posted as community reviews.</p>
        </div>
      </div>
    </Modal>
  )
}
