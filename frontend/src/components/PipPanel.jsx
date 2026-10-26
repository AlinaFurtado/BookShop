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

// Pip is a playful reading companion. These buttons apply explicit catalog
// genre filters; there is no AI or language-model request in this version.
export default function PipPanel({ busy, onMood }) {
  const [tip, setTip] = useState(0)
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
      <div className="pip-divider"><span>WHAT’S YOUR NEXT CHAPTER?</span></div>
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
