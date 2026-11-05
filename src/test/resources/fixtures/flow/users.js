// Supporting module for sample.js - cross-file resolution
export function fetchUser(id) {
  return { id, name: 'Ada', user: { name: 'Ada Lovelace' } };
}

export function fetchStats() {
  return { views: 1 };
}
