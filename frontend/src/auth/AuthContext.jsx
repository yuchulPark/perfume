import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { getSession, login, logout, refreshCsrf } from '../api/auth.js';

const AuthContext = createContext(null);
export function AuthProvider({ children }) {
  const [session, setSession] = useState({ phase: 'loading', enabled: true, user: null, error: '' });
  const draft = useRef({ query: '', topK: 5 });
  const generation = useRef(0);
  const reload = useCallback(async () => {
    const version = ++generation.current;
    setSession((old) => ({ ...old, phase: 'loading', error: '' }));
    try {
      const current = await getSession();
      if (current.enabled) await refreshCsrf();
      if (version === generation.current) setSession({ ...current, phase: 'ready', error: '' });
    } catch (error) {
      if (version === generation.current) setSession({ phase: 'error', enabled: true, user: null, error: error.message });
    }
  }, []);
  useEffect(() => { reload(); return () => { generation.current++; }; }, [reload]);
  async function signIn(username, password) {
    ++generation.current;
    try {
      const current = await login(username, password);
      setSession({ ...current, phase: 'ready', error: '' });
    } catch (error) {
      // Login may have succeeded even when token refresh failed; prevent stale assumptions.
      setSession({ phase: [401, 403].includes(error.status) ? 'ready' : 'error', enabled: true, user: null, error: [401, 403].includes(error.status) ? '' : error.message });
      throw error;
    }
  }
  async function signOut() {
    ++generation.current;
    // Block further recommendation mutations while logout is in flight.
    setSession((old) => ({ ...old, phase: 'loading' }));
    try {
      const current = await logout();
      setSession({ ...current, phase: 'ready', error: '' });
    } catch (error) {
      setSession({ phase: 'error', enabled: true, user: null, error: error.message });
    }
  }
  function expire() { ++generation.current; setSession({ phase: 'ready', enabled: true, user: null, error: '' }); }
  return <AuthContext.Provider value={{ ...session, draft, reload, signIn, signOut, expire }}>{children}</AuthContext.Provider>;
}
export function useAuth() { return useContext(AuthContext); }
