export interface UserProfile {
  id: number;
  name: string;
  email?: string;
}

export type UserFeed = { items: string[] };
