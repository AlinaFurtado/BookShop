import assert from 'node:assert/strict'
import test from 'node:test'
import { looksLikeReadingRequest } from '../src/searchIntent.js'

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
