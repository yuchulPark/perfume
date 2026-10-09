import { QueryClient, useQuery } from '@tanstack/react-query';
import { getBrands, getPerfume, getPerfumes } from './client.js';

export const queryClient = new QueryClient({ defaultOptions: { queries: {
  staleTime: 60_000, gcTime: 10 * 60_000, retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false,
} } });

export const useBrands = () => useQuery({ queryKey: ['brands'], queryFn: ({ signal }) => getBrands({ signal }), staleTime: 10 * 60_000 });
export const usePerfume = (id) => useQuery({ queryKey: ['perfume', id], queryFn: ({ signal }) => getPerfume(id, { signal }), enabled: !!id });
export function usePerfumes(filters) {
  const normalized = { ...filters, q: filters.q.trim().toLowerCase() };
  return useQuery({ queryKey: ['perfumes', normalized], queryFn: ({ signal }) => getPerfumes(normalized, { signal }),
    placeholderData: (previous, previousQuery) => {
      const old = previousQuery?.queryKey[1];
      return old?.q === normalized.q && old?.brandSlug === normalized.brandSlug ? previous : undefined;
    } });
}
