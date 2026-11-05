// Simple entry
function greet(name) {
  return `Hello, ${name}!`;
}

// Cross-file import resolution
import { fetchUser } from './users';
import { UserProfile } from './types';

// A component-like function that chains
export function buildGreeting(userId) {
  const profile = fetchUser(userId);
  if (!profile) {
    return 'unknown user';
  }
  const user: UserProfile = profile.user;
  return greet(user.name);
}

// Nested function - depth guard targets
export function outer() {
  return inner();
}

function inner() {
  return greet('nested');
}
