import { createContext, useContext, useEffect, useState } from 'react';
import { supportRequest } from './api.js';
const Context = createContext(false);
export function SupportProvider({ children }) {
  const [enabled, setEnabled] = useState(false);
  useEffect(() => {
    let alive = true;
    supportRequest('/api/support/config').then(data => { if (alive) setEnabled(data.enabled === true); }).catch(() => {});
    return () => { alive = false; };
  }, []);
  return <Context.Provider value={enabled}>{children}</Context.Provider>;
}
export function useSupportEnabled() { return useContext(Context); }
