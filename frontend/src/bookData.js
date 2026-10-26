export const GENRES = ['Fantasy', 'Mystery', 'Science Fiction', 'Romance', 'Classics', 'Literary Fiction', 'Nonfiction', 'Kids']

export const STATUSES = [
  { value: 'WANT_TO_READ', label: 'Want to Read', icon: 'star', note: 'For the someday-soon pile' },
  { value: 'READING', label: 'Reading', icon: 'book', note: 'Currently living in these pages' },
  { value: 'READ', label: 'Read', icon: 'check', note: 'Been there, loved that' },
]

const palettes = [
  ['#60509a', '#e4d0f9'], ['#477568', '#e5eab9'], ['#ab5848', '#ffe1b2'],
  ['#326d86', '#c6e8de'], ['#98623a', '#f6dda5'], ['#745b83', '#f6ced5'],
  ['#52643e', '#e5e1b3'], ['#9d485c', '#ffded6'],
]

export function bookStyle(book) {
  const hash = [...(book.workId || book.id || book.title || '').toString()].reduce((sum, c) => sum + c.charCodeAt(0), 0)
  const [color, accent] = palettes[hash % palettes.length]
  return { '--book-color': color, '--book-accent': accent, '--book-height': `${127 + hash % 36}px`, '--book-tilt': `${(hash % 5) - 2}deg` }
}

