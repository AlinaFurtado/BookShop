import assert from 'node:assert/strict'
import test from 'node:test'
import { looksLikeReadingRequest, resolveSearchMode } from '../src/searchIntent.js'

test('recognizes requests for book recommendations from the main search', () => {
  for (const text of [
    'i need a fantasy book with dragons',
    'I want a novel with dragons and found family',
    'Please recommend me a cosy mystery book',
    'Recommend a fantasy novel without horror',
    'Recommendations for science fiction books',
    'Suggest some books about friendship',
    'Suggestions for a gentle fantasy novel',
    'Can you recommend a book with a clever detective?',
    'Could you please suggest a romance novel?',
    'Find me a book with dragons',
    'Please find me a fantasy novel',
    'Give me a thriller book',
    'Help me choose a book to read on holiday',
    'I am looking for historical fiction',
    'I’m looking for a fantasy book',
    'I would like a mystery novel',
    'I’d like a cosy fantasy',
    'I feel like reading a funny story',
    'Looking for a mystery novel',
    'What should I read next?',
    'What book should I read after The Hobbit?',
    'Any book recommendations with dragons?',
    '  CAN YOU\nrecommend   a fantasy book?  ',
  ]) {
    assert.equal(looksLikeReadingRequest(text), true, text)
  }
})

test('keeps titles, authors, and bare genre keywords as catalog searches', () => {
  for (const text of [
    'The Hobbit',
    'Sarah J. Maas',
    'I Need a New Butt!',
    'I Want My Hat Back',
    'A Book of Dragons',
    'I Am Legend',
    'The Long Way to a Small Angry Planet',
    'Looking for Alaska',
    'A Novel About Reading',
    'Fantasy',
    'Mystery',
    'dragons found family',
    'The Book I Want to Read',
    '"I need a fantasy book with dragons"',
  ]) {
    assert.equal(looksLikeReadingRequest(text), false, text)
  }
})

test('recognizes similarity requests without treating the reference title itself as AI input', () => {
  for (const text of [
    'a book like fourth wing',
    'books similar to Fourth Wing',
    'more like Fourth Wing',
    'a novel like The Night Circus',
    'another book like The Hobbit',
    'some stories similar to The Hobbit',
    'something like Fourth Wing but without romance',
    'similar books to Piranesi',
    'books along the lines of The Secret History',
  ]) assert.equal(looksLikeReadingRequest(text), true, text)

  for (const text of ['Fourth Wing', 'The Book of Elsewhere', 'More Than This', 'Books Like', 'A Book Like No Other', 'Something Like Summer']) {
    // The last title is deliberately ambiguous; a current-draft override is
    // the escape hatch for a literal title that also reads like a request.
    if (['A Book Like No Other', 'Something Like Summer'].includes(text)) {
      assert.equal(resolveSearchMode(text, { draft: text, mode: 'catalog' }), 'catalog')
    } else assert.equal(looksLikeReadingRequest(text), false, text)
  }
})

test('Auto routing resumes for a new draft after a manual title or AI override', () => {
  const title = 'A Book Like No Other'
  const override = { draft: title, mode: 'catalog' }
  assert.equal(resolveSearchMode(title, override), 'catalog')
  assert.equal(resolveSearchMode('a book like fourth wing', override), 'ai')
  assert.equal(resolveSearchMode('The Hobbit', { draft: 'a book like fourth wing', mode: 'ai' }), 'catalog')
  assert.equal(resolveSearchMode('cosy dragons and found family', { draft: 'cosy dragons and found family', mode: 'ai' }), 'ai')
  assert.equal(resolveSearchMode('Sarah J. Maas'), 'catalog')
})

test('does not infer a book request from a request prefix without reading context', () => {
  for (const text of [
    'I need help',
    'I want a holiday',
    'Can you recommend something?',
    'Find me a dragon',
    'Suggest a name',
    'recommendations',
  ]) {
    assert.equal(looksLikeReadingRequest(text), false, text)
  }
})

test('handles empty or non-text input without throwing', () => {
  for (const text of ['', '   ', null, undefined, 42, {}]) {
    assert.equal(looksLikeReadingRequest(text), false)
  }
})
