import { Link } from 'react-router-dom';
import { Icon } from './Icon.jsx';

export function ErrorState({ error, onRetry, title = '잠시 향의 기록을 불러오지 못했어요' }) {
  return <div className="state-panel error-panel" role="alert"><span className="state-icon"><Icon name="alert" size={28} /></span>
    <h2>{title}</h2><p>{error?.message || '잠시 후 다시 시도해 주세요.'}</p>
    {onRetry && <button className="button button-dark" onClick={onRetry}><Icon name="refresh" size={17} />다시 시도</button>}</div>;
}
export function EmptyState({ filtered, onReset }) {
  return <div className="state-panel"><span className="state-icon"><Icon name="search" size={30} /></span>
    <h2>{filtered ? '검색 결과가 없어요' : '아직 등록된 향수가 없어요'}</h2>
    <p>{filtered ? '다른 이름으로 검색하거나 브랜드 필터를 바꿔 보세요.' : '향수가 등록되면 이곳에서 만나볼 수 있어요.'}</p>
    {onReset && <button className="button button-outline" onClick={onReset}>전체 향수 보기<Icon name="arrow" size={17} /></button>}</div>;
}
export function NotFound({ perfume = false }) {
  return <div className="state-panel"><span className="state-icon"><Icon name="bottle" size={30} /></span>
    <h1>{perfume ? '이 향수를 찾을 수 없어요' : '페이지를 찾을 수 없어요'}</h1>
    <p>{perfume ? '향수 목록에서 다시 찾아보세요.' : '주소를 확인하거나 향수 목록으로 돌아가 주세요.'}</p>
    <Link className="button button-dark" to="/">향수 둘러보기<Icon name="arrow" size={17} /></Link></div>;
}
export function CatalogSkeleton() {
  return <div role="status" aria-label="향수 목록 불러오는 중"><span className="sr-only">향수 목록을 불러오고 있어요.</span>
    <div className="perfume-grid">{Array.from({ length: 8 }, (_, i) => <div className="skeleton-card" key={i} aria-hidden="true">
      <div className="skeleton skeleton-image" /><div className="skeleton skeleton-line short" /><div className="skeleton skeleton-line" /></div>)}</div></div>;
}
export function DetailSkeleton() {
  return <div className="container page-space" role="status" aria-label="향수 상세정보 불러오는 중"><span className="sr-only">향수 상세정보를 불러오고 있어요.</span>
    <div className="detail-hero" aria-hidden="true"><div className="skeleton detail-cover" /><div className="detail-intro">
      <div className="skeleton skeleton-line short" /><div className="skeleton skeleton-title" /><div className="skeleton skeleton-line" />
      <div className="skeleton skeleton-line" /><div className="skeleton skeleton-line short" /></div></div></div>;
}
