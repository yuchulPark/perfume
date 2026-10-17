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

export function RecommendationStatus({ status, error, confirmed }) {
  if (status === 'error') return <div className="recommend-notice recommend-error" role="alert">
    <Icon name="alert" size={23} /><div><h2>{error.title}</h2><p>{error.message}</p></div>
  </div>;
  return <div role="status" aria-live="polite" aria-atomic="true">
    {status === 'loading' && <div className="recommend-notice"><span className="recommend-spinner" aria-hidden="true" />
      <div><h2>추천 응답을 기다리고 있어요</h2><p>응답까지 몇 분이 걸릴 수 있어요. 기다리는 동안 요청을 취소할 수 있어요.</p></div></div>}
    {status === 'cancelling' && <div className="recommend-notice"><span className="recommend-spinner" aria-hidden="true" />
      <div><h2>AI 추천을 취소하는 중입니다</h2><p>{confirmed ? '서버가 취소 요청을 접수했어요. 작업 종료와 정리를 확인할 때까지 새 추천을 기다려 주세요.' : '서버에 취소 요청을 전달하고 있어요. 아직 작업 종료가 확인되지 않았어요.'}</p></div></div>}
    {status === 'unknown' && <div className="recommend-notice recommend-error"><Icon name="alert" size={23} />
      <div><h2>작업 상태 확인 불가</h2><p>서버 작업이 종료됐는지 확인할 수 없어요. 연결을 확인한 뒤 ‘상태 다시 확인’을 눌러 주세요. 새 추천은 종료 확인까지 기다려 주세요.</p></div></div>}
    {status === 'lost' && <div className="recommend-notice"><Icon name="alert" size={23} />
      <div><h2>작업 정보를 찾을 수 없어요</h2><p>서버 재시작이나 보관 기간 만료로 이전 상태를 복구할 수 없어요. 새 요청의 실행 가능 여부는 서버가 다시 확인해요.</p></div></div>}
    {status === 'cancelled' && <div className="recommend-notice"><Icon name="close" size={23} />
      <div><h2>추천 작업 취소가 완료됐어요</h2><p>백엔드 작업 종료와 정리를 확인했어요. 새 추천을 요청할 수 있어요. 원격 모델의 계산 종료까지 확인하는 기능은 아니에요.</p></div></div>}
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
