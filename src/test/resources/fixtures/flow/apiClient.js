export function apiFetch(query) {
  return {
    items: [{ title: 'result for ' + query, link: 'https://example.com' }],
  };
}

export function apiHealth() {
  return 'ok';
}
