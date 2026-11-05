// Deep flow fixture: a chain that resolves through a module-level function
// and an imported method, exercising real cross-file resolution and depth limits.

import { apiFetch } from './apiClient';
import type { SearchResult } from './searchTypes';

export function search(query) {
  const result = apiFetch(query);
  return processResults(result);
}

function processResults(result) {
  if (result == null) {
    return [];
  }
  return normalize(result.items);
}

function normalize(items) {
  return items.map((item) => format(item));
}

function format(item) {
  return {
    title: item.title,
    href: item.link,
  };
}
