import { useEffect, useState } from 'react';
import { Icon } from './Icon.jsx';

export function SearchControls({ query, brandSlug, brands, brandsLoading, brandsError, onSearch, onBrand, onBrandsRetry }) {
  const [draft, setDraft] = useState(query);
  useEffect(() => { setDraft(query); }, [query]);
  useEffect(() => {
    if (draft.trim() === query) return;
    const timer = setTimeout(() => onSearch(draft.trim()), 350);
    return () => clearTimeout(timer);
  }, [draft, query, onSearch]);
  return <div className="search-controls">
    <form className="search-form" role="search" onSubmit={(event) => { event.preventDefault(); onSearch(draft.trim()); }}>
      <Icon name="search" size={23} /><label htmlFor="perfume-search" className="sr-only">향수 이름 또는 브랜드 검색</label>
      <input id="perfume-search" type="search" value={draft} onChange={(event) => setDraft(event.target.value)} maxLength={255}
        placeholder="어떤 향수가 궁금하세요?" autoComplete="off" />
      <button className="search-submit" type="submit">검색<Icon name="arrow" size={17} /></button>
    </form>
    <div className="brand-filter"><label htmlFor="brand-filter">브랜드</label>
      <select id="brand-filter" value={brandSlug} disabled={brandsLoading || !!brandsError} onChange={(event) => onBrand(event.target.value)}>
        <option value="">{brandsLoading ? '불러오는 중…' : brandsError ? '브랜드 조회 실패' : '전체 브랜드'}</option>
        {brandSlug && !brands.some((brand) => brand.brandSlug === brandSlug) && <option value={brandSlug}>{brandSlug}</option>}
        {brands.map((brand) => <option value={brand.brandSlug} key={brand.id ?? brand.brandSlug}>{brand.name}</option>)}
      </select>
    </div>
    {brandsError && <div className="inline-error" role="alert">브랜드 목록을 불러오지 못했어요.
      <button onClick={onBrandsRetry}>다시 시도</button></div>}
  </div>;
}
