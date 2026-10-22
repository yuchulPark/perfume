import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext.jsx';
import LoginNotice from '../components/LoginNotice.jsx';
import { DEFAULT_TOP_K, MAX_QUERY_LENGTH, MAX_TOP_K, validateRecommendationInput } from '../api/recommendations.js';
import { RecommendationJobSession } from '../lib/recommendationJobs.js';
import { Icon } from '../components/Icon.jsx';
import { RecommendationResults, RecommendationStatus } from '../components/RecommendationResults.jsx';

const examples = ['산뜻한 시트러스 노트가 있는 향수를 찾고 있어요.', '차분한 우디 향을 좋아해요. 어떤 향수가 있을까요?', '달콤한 향보다 가벼운 꽃향기가 좋아요.'];

export default function RecommendPage() {
  const auth = useAuth(), navigate = useNavigate();
  const [query, setQuery] = useState(auth.draft.current.query);
  const [topK, setTopK] = useState(auth.draft.current.topK ?? DEFAULT_TOP_K);
  const [loginNotice, setLoginNotice] = useState(false);
  const [validation, setValidation] = useState('');
  const [jobs] = useState(() => new RecommendationJobSession());
  const result = useSyncExternalStore(jobs.subscribe, jobs.getSnapshot, jobs.getSnapshot);
  const inputRef = useRef(null);
  const headingRef = useRef(null);
  const canRun = auth.phase === 'ready' && (!auth.enabled || !!auth.user);
  const pending = canRun && ['loading', 'cancelling', 'unknown'].includes(result.status);

  useEffect(() => {
    document.title = 'AI 향수 추천 · 향의 기록';
  }, []);
  useEffect(() => {
    if (canRun) jobs.mount();
    return () => jobs.unmount();
  }, [jobs, canRun]);
  useEffect(() => { auth.draft.current = { query, topK }; }, [auth.draft, query, topK]);
  useEffect(() => {
    if (result.error?.kind === 'auth') { auth.expire(); setLoginNotice(true); }
  }, [result.error]);
  useEffect(() => { if (result.status === 'success') headingRef.current?.focus(); }, [result]);

  function submit(event) {
    event.preventDefault();
    if (pending) return;
    if (auth.phase !== 'ready') { setValidation('로그인 상태를 확인한 후 다시 눌러 주세요.'); return; }
    if (auth.enabled && !auth.user) { setValidation(''); setLoginNotice(true); return; }
    const invalid = validateRecommendationInput(query, topK);
    setValidation(invalid);
    if (invalid) { inputRef.current?.focus(); return; }
    jobs.start(query, topK);
  }

  return <div className="container recommend-page">
    {loginNotice && <LoginNotice onCancel={() => setLoginNotice(false)} onConfirm={() => navigate('/login', { state: { from: '/recommend' } })} />}
    <header className="recommend-intro"><span className="eyebrow"><Icon name="spark" size={16} />A SCENT FOR YOUR WORDS</span>
      <h1>AI 향수 추천</h1><p>좋아하는 향과 원하는 분위기를 들려주세요.<br />저장된 향수 정보를 바탕으로 취향의 실마리를 찾아드려요.</p></header>
    <div className="recommend-compose">
      <form className="recommend-form" onSubmit={submit} noValidate aria-label="AI 향수 추천 요청" aria-busy={pending}>
        <label className="recommend-question-label" htmlFor="recommend-query">어떤 향을 찾고 계세요?</label>
        <p id="recommend-query-help" className="recommend-field-help">좋아하는 노트, 피하고 싶은 향, 원하는 분위기를 자유롭게 적어 주세요.</p>
        <textarea id="recommend-query" ref={inputRef} value={query} maxLength={MAX_QUERY_LENGTH} rows={5} disabled={pending}
          aria-describedby={`recommend-query-help recommend-query-count${validation ? ' recommend-validation' : ''}`} aria-invalid={!!validation}
          placeholder="예: 산뜻한 시트러스 향을 좋아해요. 저장된 노트를 바탕으로 추천해 주세요."
          onChange={(event) => { setQuery(event.target.value); setValidation(''); }} />
        <span id="recommend-query-count" className="recommend-query-count">{query.length} / {MAX_QUERY_LENGTH}자</span>
        <div className="recommend-examples"><span>이렇게 물어보세요</span>{examples.map((example) => <button type="button" key={example} disabled={pending}
          onClick={() => { setQuery(example); setValidation(''); inputRef.current?.focus(); }}>{example}</button>)}</div>
        <div className="recommend-form-footer"><div className="recommend-top-k"><label htmlFor="recommend-top-k">검색 개수</label>
          <select id="recommend-top-k" value={topK} disabled={pending} aria-describedby="recommend-top-k-help" onChange={(event) => setTopK(Number(event.target.value))}>
            {Array.from({ length: MAX_TOP_K }, (_, index) => <option key={index + 1} value={index + 1}>{index + 1}개</option>)}</select></div>
          <div className="recommend-actions"><button type="submit" className="button button-dark" disabled={pending}><Icon name="spark" size={17} />{pending ? '추천 작업 진행 중' : '추천받기'}</button>
            {pending && result.canCancel && result.status !== 'cancelling' && <button type="button" className="button button-outline" onClick={() => jobs.cancel()}>취소</button>}
            {result.status === 'cancelling' && <button type="button" className="button button-outline" disabled>취소 처리 중</button>}
            {result.status === 'unknown' && <button type="button" className="button button-outline" onClick={() => jobs.resume()}>상태 다시 확인</button>}</div></div>
        <p id="recommend-top-k-help" className="recommend-field-help">최대 몇 개의 향수를 검색할지 정해요. 실제 근거와 답변에 소개되는 향수는 더 적을 수 있어요.</p>
        <p className="recommend-field-help">화면을 떠나도 서버 작업은 계속될 수 있어요. 같은 탭으로 돌아오면 작업 상태를 다시 확인해요.</p>
        {validation && <p id="recommend-validation" className="recommend-validation" role="alert">{validation}</p>}
      </form>
      <aside className="recommend-guide" aria-labelledby="recommend-guide-title"><Icon name="leaf" size={27} /><h2 id="recommend-guide-title">저장된 향에서 찾는 실마리</h2>
        <p>전체 카탈로그가 아닌, 임베딩된 일부 향수의 저장된 정보를 기반으로 추천해요.</p>
        <p>정보는 임베딩 생성 당시의 기록으로 최신 정보와 다를 수 있어요. 검색 유사도만으로 계절 적합성이나 지속력, 질문의 모든 조건을 충족한다고 확정할 수는 없어요.</p>
        <Link to="/">향수 컬렉션 둘러보기<Icon name="arrow" size={16} /></Link></aside>
    </div>
    <RecommendationStatus status={result.status} error={result.error} confirmed={result.confirmed} />
    {result.status === 'success' && <RecommendationResults data={result.data} headingRef={headingRef} />}
  </div>;
}
