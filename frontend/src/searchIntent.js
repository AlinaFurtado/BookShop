// Prefer a title search when intent is ambiguous. A request needs both a
// request-shaped beginning and reading context; titles alone stay literal.
const REQUEST_START = /^(?:(?:please\s+)?(?:recommend(?:ations)?|suggest(?:ions)?|find\s+me|give\s+me|help\s+me\s+(?:find|choose|pick))\b|(?:can|could|would|will)\s+you\s+(?:please\s+)?(?:recommend|suggest|find|help\s+me\s+(?:find|choose|pick))\b|i\s+(?:need|want|would\s+like|am\s+(?:looking\s+for|after|in\s+the\s+mood\s+for)|feel\s+like)\b|i'(?:m\s+(?:looking\s+for|after|in\s+the\s+mood\s+for)|d\s+like)\b|looking\s+for\b|what\s+(?:should\s+i\s+read|(?:books?|novels?|stories)\s+(?:should|could)\s+i\s+read)\b|any\s+(?:book|novel)\s+recommendations\b)/i

const READING_CONTEXT = /\b(?:books?|novels?|stories|story|read(?:ing)?|fantasy|myster(?:y|ies)|romance|romantasy|thrillers?|horror|non[ -]?fiction|memoirs?|biograph(?:y|ies)|(?:historical|literary|science)\s+fiction|sci[ -]?fi|fiction)\b/i

export function looksLikeReadingRequest(text) {
  if (typeof text !== 'string') return false
  const normalized = text.trim().replace(/[’‘]/g, "'").replace(/\s+/g, ' ')
  return REQUEST_START.test(normalized) && READING_CONTEXT.test(normalized)
}
