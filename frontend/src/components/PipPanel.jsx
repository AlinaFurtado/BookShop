import { useState } from 'react'
import Icon from './Icon'

const MOODS = [
  { label: 'A little magic', genre: 'Fantasy', icon: 'spark' },
  { label: 'A clever mystery', genre: 'Mystery', icon: 'search' },
  { label: 'To the stars', genre: 'Science Fiction', icon: 'star' },
  { label: 'Big heart energy', genre: 'Romance', icon: 'coffee' },
]

const TIPS = [
  'The best reading goal? A book you actually want to pick up. Your shelf, your rules.',
  'Keep a short Want to Read list. Future you deserves fewer decisions and more good books.',
  'A book can be excellent and still not be your book. It’s okay to try another story.',
  'Ten quiet pages count. So does a whole afternoon. There’s no wrong pace for a good story.',
  'Stuck in a reading slump? Try a different genre. A tiny literary plot twist might help.',
]

// Free-text requests go to our backend only after an explicit submit. Genre
// buttons remain ordinary catalog browsing, with no language-model request.
export default function PipPanel({ busy, onMood, aiStatus, aiStatusLoading, aiBusy, sessionLoading, signedIn, filtersLabel, onRecommend, onCancelRecommend }) {
  const [tip, setTip] = useState(0)
  const [prompt, setPrompt] = useState('')
  const [includeOlder, setIncludeOlder] = useState(false)
  const promptLength = prompt.trim().length
  const available = aiStatus?.available === true
  const disabled = aiStatusLoading || !available || sessionLoading
  return (
    <aside className="panel pip-panel" aria-labelledby="pip-title">
      <div className="pip-header">
        <div className="pip-character" aria-hidden="true">
          <span className="pip-ear ear-left" />
          <span className="pip-ear ear-right" />
          <span className="pip-face">
            <span className="pip-eyes"><i /><i /></span>
            <span className="pip-cheek cheek-left" />
            <span className="pip-cheek cheek-right" />
            <span className="pip-mouth" />
          </span>
          <span className="pip-spark">✦</span>
        </div>
        <div>
          <span className="eyebrow">YOUR LITERARY SIDEKICK</span>
          <h2 id="pip-title">Pip, the book goblin<span aria-hidden="true">✦</span></h2>
          <p>Excellent taste. Questionable hat.</p>
        </div>
      </div>
      <div className="pip-speech" aria-live="polite">
        <span className="speech-mark" aria-hidden="true">“</span>
        {TIPS[tip]}
      </div>
      <button className="tip-button" onClick={() => setTip((current) => (current + 1) % TIPS.length)}>
        One more goblin thought <Icon name="arrow" size={15} />
      </button>
      <form className="pip-ai-form" onSubmit={(event) => { event.preventDefault(); if (!disabled && !aiBusy && promptLength >= 10 && promptLength <= 600) onRecommend({ prompt: prompt.trim(), includeOlder }) }}>
        <div className="pip-ai-heading"><span className="eyebrow">A LITTLE AI, A LOT OF BOOKISHNESS</span><span className="pip-ai-sticker" aria-hidden="true">plot twist!</span></div>
        <label htmlFor="pip-prompt">Tell Pip your oddly specific book mood.</label>
        <textarea id="pip-prompt" value={prompt} onChange={(event) => setPrompt(event.target.value)} minLength={10} maxLength={600} rows={4} required disabled={disabled || aiBusy} placeholder="Cosy magic, unlikely friends, and a little mystery. No horror, please." aria-describedby="pip-prompt-help pip-ai-status" />
        <div className="pip-prompt-help" id="pip-prompt-help"><button type="button" className="text-button" disabled={disabled || aiBusy} onClick={() => setPrompt('A cosy fantasy with found family, gentle magic, and a clever mystery. No horror, please.')}>Try a cosy little example</button><span>{promptLength}/600</span></div>
        <p className="pip-filter-summary">Your catalog filters: <strong>{filtersLabel}</strong></p>
        <label className="pip-older-option"><input type="checkbox" checked={includeOlder} onChange={(event) => setIncludeOlder(event.target.checked)} disabled={disabled || aiBusy} /><span>Include older books, too<small>Otherwise, explore the last five years.</small></span></label>
        <p className="pip-ai-status" id="pip-ai-status" role="status">{aiStatusLoading ? 'Checking whether Pip’s recommendation desk is open…' : !available ? aiStatus?.message || 'AI recommendations are unavailable right now. The genre shelves below are open.' : `Up to four real books, with a little explanation for each.${signedIn ? '' : ' Sign in to ask Pip.'}`}</p>
        <div className="pip-ai-actions"><button className="button button-purple" type="submit" disabled={disabled || aiBusy || promptLength < 10 || promptLength > 600}>{aiBusy ? <><span className="spinner" />Pip is hunting for your plot…</> : <><Icon name="spark" size={16} />{signedIn ? 'Find my next chapter' : 'Sign in to ask Pip'}</>}</button>{aiBusy && <button className="text-button" type="button" onClick={onCancelRecommend}>Cancel</button>}</div>
        <p className="pip-ai-privacy">Only your description and chosen filters go to the AI service. Your shelf, reviews, and private passport notes stay out of the request. AI matches can miss the mark; check the book details.</p>
      </form>
      <div className="pip-divider"><span>OR FOLLOW A GENRE</span></div>
      <div className="mood-buttons">
        {MOODS.map((mood) => (
          <button key={mood.genre} disabled={busy} onClick={() => onMood(mood.genre)}>
            <span className="mood-icon"><Icon name={mood.icon} size={18} /></span>
            <span><strong>{mood.label}</strong><small>{mood.genre}</small></span>
            <Icon name="arrow" size={16} />
          </button>
        ))}
      </div>
      <p className="pip-note">A mood is just a starting point. These buttons browse books by genre.</p>
      <div className="pip-signature"><span aria-hidden="true">✧</span><span>A little curiosity looks good on you.</span><span aria-hidden="true">✧</span></div>
    </aside>
  )
}
