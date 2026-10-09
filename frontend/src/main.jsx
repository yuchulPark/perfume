import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { queryClient } from './api/queries.js';
import App from './App.jsx';
import './styles.css';

// Query caching/cancellation handles navigation; a single root avoids development-only double effects.
createRoot(document.getElementById('root')).render(
  <QueryClientProvider client={queryClient}><BrowserRouter><App /></BrowserRouter></QueryClientProvider>,
);
