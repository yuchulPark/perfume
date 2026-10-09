import { useCallback, useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useBrands, usePerfumes } from '../api/queries.js';
import { BottleIllustration } from '../components/PerfumeImage.jsx';
import { Icon } from '../components/Icon.jsx';
import { SearchControls } from '../components/SearchControls.jsx';
import { PerfumeCard } from '../components/PerfumeCard.jsx';
import { Pagination } from '../components/Pagination.jsx';
import { CatalogSkeleton, EmptyState, ErrorState } from '../components/States.jsx';
import { formatNumber, items, readFilters } from '../lib/display.js';

export default function CatalogPage() {
  const [params, setParams] = useSearchParams();
  const filters = readFilters(params);
  const brands = useBrands();
  const perfumes = usePerfumes(filters);
  useEffect(() => { document.title = '향수 둘러보기 · 향의 기록'; }, []);
  const updateFilters = useCallback((values) => {
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      Object.entries(values).forEach(([key, value]) => value === '' || value === 0 ? next.delete(key) : next.set(key, String(value)));
      if (Object.hasOwn(values, 'q') || Object.hasOwn(values, 'brandSlug')) next.delete('page');
      return next;
    }, { replace: Object.hasOwn(values, 'q') });
  }, [setParams]);
  const onSearch = useCallback((q) => updateFilters({ q }), [updateFilters]);
  const onBrand = useCallback((brandSlug) => updateFilters({ brandSlug }), [updateFilters]);
  const reset = () => setParams({});
  const filtered = !!(filters.q || filters.brandSlug);
  const data = perfumes.data;
  return <>
    <section className="catalog-hero"><div className="container hero-inner">
      <div className="hero-copy"><span className="eyebrow"><span className="tiny-dot" />나를 표현하는 향의 발견</span>
        <h1>당신의 취향에,<br /><em>향을 더하다.</em></h1><p>노트와 분위기, 계절과 착용감까지.<br />한 병에 담긴 이야기를 천천히 만나보세요.</p></div>
      <div className="hero-art" aria-hidden="true"><div className="art-orbit" /><div className="art-leaf leaf-one" /><div className="art-leaf leaf-two" />
        <div className="art-bottle"><BottleIllustration decorative /></div><span className="art-note">A NOTE OF YOUR OWN</span><span className="art-caption">취향은 작은 발견에서 시작됩니다.</span></div>
    </div></section>
    <section className="container catalog-section" aria-labelledby="catalog-title">
      <div className="section-heading"><div><span className="eyebrow">FRAGRANCE COLLECTION</span><h2 id="catalog-title">향수 컬렉션</h2></div>
        <span className="collection-mark"><Icon name="leaf" size={17} />나에게 어울리는 향 찾기</span></div>
      <SearchControls query={filters.q} brandSlug={filters.brandSlug} brands={items(brands.data)} brandsLoading={brands.isPending}
        brandsError={brands.error} onSearch={onSearch} onBrand={onBrand} onBrandsRetry={() => brands.refetch()} />
      <div className="results-bar"><div aria-live="polite">{perfumes.isPending ? '향수를 찾고 있어요…' : data ? <>총 <strong>{formatNumber(data.totalElements)}</strong>개의 향수</> : '향수 목록'}
        {perfumes.isFetching && !perfumes.isPending && <span className="updating-label">불러오는 중…</span>}</div>
        {filtered ? <button className="text-button" onClick={reset}><Icon name="close" size={15} />검색 초기화</button> : <span className="results-hint">이름순 · 페이지당 20개</span>}</div>
      {filtered && <div className="filter-chips">{filters.q && <span>검색: {filters.q}</span>}{filters.brandSlug && <span>브랜드: {brands.data?.find((b) => b.brandSlug === filters.brandSlug)?.name || filters.brandSlug}</span>}</div>}
      <div aria-busy={perfumes.isFetching}>
        {perfumes.isPending ? <CatalogSkeleton /> : perfumes.isError ? <ErrorState error={perfumes.error} onRetry={() => perfumes.refetch()} />
          : !data?.content.length ? <EmptyState filtered={filtered} onReset={filtered || filters.page ? reset : undefined} />
            : <div className={`perfume-grid ${perfumes.isPlaceholderData ? 'pending-grid' : ''}`}>{data.content.map((perfume) => <PerfumeCard key={perfume.id} perfume={perfume} />)}</div>}
      </div>
      {data && !perfumes.isError && <Pagination page={filters.page} totalPages={data.totalPages} loading={perfumes.isFetching} onChange={(page) => {
        updateFilters({ page });
        document.getElementById('catalog-title')?.scrollIntoView({ block: 'start' });
      }} />}
    </section>
  </>;
}
