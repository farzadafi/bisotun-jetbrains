import { fetchProfile } from './profileService';
import type { UserProfile } from './userTypes';

export const UserProfileCard: React.FC = () => {
  const [user, setUser] = useState<UserProfile | null>(null);

  const loadUserData = async () => {
    const data = await fetchProfile();
    setUser(data);
  };

  return (<section>{user?.name}</section>);
};
