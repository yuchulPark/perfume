import { Link } from 'react-router-dom';
import { Icon } from './Icon.jsx';
import { formatScore } from '../lib/display.js';

function AnswerText({ text, sourceCount }) {
  return text.split(/(\[\d+\])/g).map((part, index) => {
    const match = /^\[(\d+)\]$/.exec(part);
    const number = match ? Number(match[1]) : 0;
    return number > 0 && number <= sourceCount
      ? <a key={index} className="answer-citation" href={`#recommend-source-${number}`} aria-label={`출처 ${number} 보기`}>{part}</a>
      : part;
  });
}

export function RecommendationStatus({ status, error }) {
  if (status === 'error') return <div className="recommend-notice recommend-error" role="alert">
    <Icon name="alert" size={23} /><div><h2>{error.title}</h2><p>{error.message}</p></div>
  </div>;
  return <div role="status" aria-live="polite" aria-atomic="true">
    {status === 'loading' && <div className="recommend-notice"><span className="recommend-spinner" aria-hidden="true" />
      <div><h2>추천 응답을 기다리고 있어요</h2><p>응답까지 몇 분이 걸릴 수 있어요. 기다리는 동안 요청을 취소할 수 있어요.</p></div></div>}
    {status === 'cancelled' && <div className="recommend-notice"><Icon name="close" size={23} />
      <div><h2>추천 요청 대기를 취소했어요</h2><p>서버 작업은 바로 멈추지 않을 수 있어요. 다시 요청하려면 잠시 기다려 주세요.</p></div></div>}
    {status === 'success' && <p className="sr-only">추천 결과를 받았어요.</p>}
  </div>;
}

export function RecommendationResults({ data, headingRef }) {
  return <section className="recommend-results" aria-labelledby="recommend-result-title">
    <div className="section-heading"><div><span className="eyebrow">YOUR SCENT NOTES</span>
      <h2 id="recommend-result-title" tabIndex={-1} ref={headingRef}>{data.returnedCount ? '취향을 위한 추천 기록' : '추천 결과가 없어요'}</h2></div>
      <span className="recommend-count">검색된 근거 {data.returnedCount}개</span></div>
    {!data.returnedCount && <p className="recommend-empty">검색 가능한 일부 향수에서 관련 근거를 찾지 못했어요. 원하는 노트나 분위기를 바꿔 질문해 보세요.</p>}
    <div className="recommend-result-grid">
      <article className="recommend-answer" aria-label="AI 추천 답변">
        <span className="recommend-answer-label"><Icon name="spark" size={18} />AI가 정리한 답변</span>
        {data.answer.split(/\r?\n/).filter((line) => line.trim()).map((line, index) => <p key={index}><AnswerText text={line} sourceCount={data.sources.length} /></p>)}
      </article>
      {!!data.sources.length && <aside className="recommend-sources" aria-labelledby="recommend-sources-title">
        <h3 id="recommend-sources-title">추천 근거</h3>
        <p className="recommend-source-hint">답변의 출처 번호 순서예요. 검색 유사도는 조건 충족 확률이나 향수의 평점이 아니에요.</p>
        <ol>{data.sources.map((source, index) => <li key={`${source.perfumeId}-${index}`} id={`recommend-source-${index + 1}`}>
          <Link className="recommend-source-card" to={`/perfumes/${source.perfumeId}`}>
            <span className="recommend-source-number">[{index + 1}]</span>
            <div><span className="card-brand">{source.brandName || '브랜드 정보 없음'}</span><h3>{source.name}</h3>
              <span className="recommend-similarity">검색 유사도 {formatScore(source.similarity)}</span>
              <span className="recommend-source-link">향수 상세 보기<Icon name="arrow" size={15} /></span></div>
          </Link></li>)}</ol>
      </aside>}
    </div>
  </section>;
}
