import { useEffect } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { usePerfume } from '../api/queries.js';
import { Icon } from '../components/Icon.jsx';
import { PerfumeImage } from '../components/PerfumeImage.jsx';
import { DetailSkeleton, ErrorState, NotFound } from '../components/States.jsx';
import { AccordsSection, AppreciationSection, NotesSection, OpinionsSection, PerformanceSection, PerfumersSection, SimilaritiesSection, WearSection } from '../components/DetailSections.jsx';
import { categoryLabel, findMetric, formatNumber, formatScore, normalizeId, numeric, text } from '../lib/display.js';

export default function PerfumeDetailPage() {
  const { id: routeId } = useParams();
  const id = normalizeId(routeId);
  const detail = usePerfume(id);
  const location = useLocation();
  const from = typeof location.state?.from === 'string' && (location.state.from === '/' || location.state.from.startsWith('/?')) ? location.state.from : '/';
  useEffect(() => { document.title = detail.data?.name ? `${detail.data.name} · 향의 기록` : '향수 상세정보 · 향의 기록'; }, [detail.data?.name]);
  if (!id) return <div className="container page-space"><NotFound perfume /></div>;
  if (detail.isPending) return <DetailSkeleton />;
  if (detail.isError) return <div className="container page-space"><Link to={from} className="back-link"><Icon name="left" size={17} />향수 목록으로</Link>
    {detail.error.status === 404 ? <NotFound perfume /> : <ErrorState error={detail.error} onRetry={() => detail.refetch()} />}</div>;
  const perfume = detail.data;
  const rating = findMetric(perfume.metrics, 'identity.rating');
  const gender = findMetric(perfume.metrics, 'identity.gender');
  return <div className="container detail-page">
    <Link to={from} className="back-link"><Icon name="left" size={17} />향수 목록으로</Link>
    <div className="detail-hero"><div className="detail-cover"><PerfumeImage src={perfume.imageUrl} name={perfume.name} eager /></div>
      <div className="detail-intro"><span className="eyebrow">FRAGRANCE PROFILE</span>
        {text(perfume.brand?.brandSlug) ? <Link className="detail-brand" to={`/?brandSlug=${encodeURIComponent(perfume.brand.brandSlug)}`}>{text(perfume.brand?.name) || '브랜드 정보 없음'}<Icon name="arrow" size={15} /></Link>
          : <span className="detail-brand">{text(perfume.brand?.name) || '브랜드 정보 없음'}</span>}
        <h1>{text(perfume.name) || '이름 정보 없음'}</h1>
        <div className="detail-facts"><span>출시연도 <strong>{numeric(perfume.releaseYear) ? `${perfume.releaseYear}년` : '정보 없음'}</strong></span>
          <span>성별 <strong>{categoryLabel(gender?.category) || (numeric(gender?.score) ? `점수 ${formatScore(gender.score)}` : '정보 없음')}</strong></span></div>
        <div className="detail-rating"><div><span className="rating-icon"><Icon name="spark" size={24} /></span><div><span className="rating-label">평점</span>
          <strong>{numeric(rating?.score) ? formatScore(rating.score) : categoryLabel(rating?.category) || '정보 없음'}</strong></div></div>
          <div className="review-total"><span>리뷰 수</span><strong>{numeric(perfume.reviewsCount) ? `${formatNumber(perfume.reviewsCount)}개` : '정보 없음'}</strong></div>
          {numeric(rating?.nRecords) && <span className="rating-records">평가 {formatNumber(rating.nRecords)}건</span>}</div>
        <div className="detail-description"><h2>향수 이야기</h2><p>{text(perfume.description) || '등록된 설명이 없습니다.'}</p></div>
      </div></div>
    <nav className="detail-nav" aria-label="상세정보 섹션">{[['notes', '노트'], ['accords', '어코드'], ['perfumers', '조향사'], ['performance', '지속력 · 발향'], ['wear', '계절 · 착용'], ['appreciation', '평가'], ['opinions', '장단점'], ['similarities', '유사 향수']]
      .map(([section, label]) => <a href={`#${section}`} key={section}>{label}</a>)}</nav>
    <div className="detail-columns"><NotesSection notes={perfume.notes} /><AccordsSection accords={perfume.accords} /></div>
    <PerfumersSection perfumers={perfume.perfumers} /><PerformanceSection metrics={perfume.metrics} />
    <WearSection metrics={perfume.metrics} /><AppreciationSection metrics={perfume.metrics} />
    <OpinionsSection pros={perfume.pros} cons={perfume.cons} /><SimilaritiesSection fragrances={perfume.similarFragrances} />
    <div className="detail-bottom"><Link to={from} className="button button-outline"><Icon name="left" size={17} />향수 목록으로</Link></div>
  </div>;
}
