import { Icon } from './Icon.jsx';
import { paginationPages } from '../lib/display.js';

export function Pagination({ page, totalPages, loading, onChange }) {
  if (totalPages < 2) return null;
  return <nav className="pagination" aria-label="향수 목록 페이지">
    <button className="page-arrow" aria-label="이전 페이지" disabled={page === 0 || loading} onClick={() => onChange(page - 1)}><Icon name="left" /></button>
    {paginationPages(page, totalPages)[0] > 0 && <><button className="page-jump" disabled={loading} onClick={() => onChange(0)} aria-label="1페이지">1</button>{paginationPages(page, totalPages)[0] > 1 && <span className="page-ellipsis">…</span>}</>}
    {paginationPages(page, totalPages).map((number) => <button key={number} className={page === number ? 'current' : ''} aria-label={`${number + 1}페이지`}
      aria-current={page === number ? 'page' : undefined} disabled={loading} onClick={() => onChange(number)}>{number + 1}</button>)}
    {paginationPages(page, totalPages).at(-1) < totalPages - 1 && <>{paginationPages(page, totalPages).at(-1) < totalPages - 2 && <span className="page-ellipsis">…</span>}<button className="page-jump" disabled={loading} onClick={() => onChange(totalPages - 1)} aria-label={`${totalPages}페이지`}>{totalPages}</button></>}
    <button className="page-arrow" aria-label="다음 페이지" disabled={page >= totalPages - 1 || loading} onClick={() => onChange(page + 1)}><Icon name="right" /></button>
  </nav>;
}
