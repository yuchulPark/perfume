import { Link } from 'react-router-dom';
import { Icon } from './Icon.jsx';
import { categoryLabel, findMetric, formatNumber, formatScore, hasMetric, items, metricFamily, numeric, text } from '../lib/display.js';

export function DetailSection({ id, title, subtitle, children, className = '', aside }) {
  return <section id={id} className={`detail-section ${className}`} aria-labelledby={`${id}-title`}>
    <div className="detail-section-heading"><div><h2 id={`${id}-title`}>{title}</h2>{subtitle && <p>{subtitle}</p>}</div>{aside}</div>{children}
  </section>;
}

export function MetricCard({ label, metric, icon = 'spark', compact = false }) {
  return <div className={`metric-card ${compact ? 'compact-metric' : ''}`}>
    <div className="metric-label"><Icon name={icon} size={18} /><span>{label}</span></div>
    {hasMetric(metric) ? <><div className="metric-value">{numeric(metric.score) ? <strong title={String(metric.score)}>{formatScore(metric.score)}</strong>
      : <strong className="category-value">{categoryLabel(metric.category) || '정보 없음'}</strong>}</div>
      {text(metric.category) && numeric(metric.score) && <span className="metric-category">{categoryLabel(metric.category)}</span>}
      <div className="metric-meta">{numeric(metric.nRecords) && <span>평가 {formatNumber(metric.nRecords)}건</span>}
        {text(metric.reliability) && <span>신뢰도 {categoryLabel(metric.reliability)}</span>}</div></>
      : <div className="missing-metric">정보 없음</div>}
  </div>;
}

export function NotesSection({ notes }) {
  const layers = [['top', 'TOP', '첫인상', '처음 만나는 향'], ['middle', 'MIDDLE', '중심', '향의 중심을 이루는 향'], ['base', 'BASE', '잔향', '마지막까지 남는 향']];
  return <DetailSection id="notes" title="노트 피라미드" subtitle="시간의 흐름에 따라 달라지는 향의 구성">
    <div className="note-pyramid">{layers.map(([key, english, korean, description], index) => <div className={`note-layer layer-${key}`} key={key}>
      <div className="layer-heading"><span className="layer-number">0{index + 1}</span><div><h3>{english} <span>{korean}</span></h3><p>{description}</p></div></div>
      <div className="note-tags">{items(notes?.[key]).length ? items(notes[key]).map((note, i) => <span className="note-tag" key={`${note.id ?? note.name}-${i}`}>{text(note.name) || '이름 정보 없음'}</span>)
        : <span className="muted">등록된 노트가 없습니다.</span>}</div></div>)}</div>
    {!!items(notes?.unlayered).length && <div className="unlayered-notes"><h3>기타 노트 <span>레이어 구분 없음</span></h3><div className="note-tags">
      {items(notes.unlayered).map((note, i) => <span className="note-tag" key={`${note.id ?? note.name}-${i}`}>{text(note.name) || '이름 정보 없음'}</span>)}</div></div>}
  </DetailSection>;
}

export function AccordsSection({ accords }) {
  const rows = items(accords);
  return <DetailSection id="accords" title="주요 어코드" subtitle="향의 전체적인 인상을 이루는 요소">
    {rows.length ? <div className="accord-list">{rows.map((accord, index) => <div className="accord-row" key={accord.id ?? index}>
      <div className="accord-label"><span>{text(accord.name) || '이름 정보 없음'}</span><span>{numeric(accord.percentage) ? `${formatNumber(accord.percentage)}%` : numeric(accord.score) ? `점수 ${formatScore(accord.score)}` : '정보 없음'}</span></div>
      <div className={`accord-track accord-tone-${index % 4}`}><span style={{ width: numeric(accord.percentage) ? `${Math.max(0, Math.min(100, accord.percentage))}%` : '0%' }} /></div>
    </div>)}</div> : <p className="empty-copy">등록된 어코드 정보가 없습니다.</p>}
  </DetailSection>;
}

export function PerfumersSection({ perfumers }) {
  const rows = items(perfumers);
  return <DetailSection id="perfumers" title="조향사" subtitle="이 향을 완성한 사람들">
    {rows.length ? <div className="perfumer-list">{rows.map((perfumer, index) => <div className="perfumer-item" key={perfumer.id ?? index}>
      <div className="perfumer-avatar" aria-hidden="true">{text(perfumer.name).split(/\s+/).map((part) => part[0]).slice(0, 2).join('') || 'P'}</div>
      <div><h3>{text(perfumer.name) || '이름 정보 없음'}</h3><p>{text(perfumer.company) || '소속 정보 없음'}
        {numeric(perfumer.perfumesCount) && <span className="perfumer-count">작품 {formatNumber(perfumer.perfumesCount)}개</span>}</p>
        {text(perfumer.biography) && <details className="biography"><summary>조향사 소개</summary><p>{perfumer.biography}</p></details>}</div>
    </div>)}</div> : <p className="empty-copy">등록된 조향사 정보가 없습니다.</p>}
  </DetailSection>;
}

export function PerformanceSection({ metrics }) {
  return <DetailSection id="performance" title="지속력과 발향" subtitle="향이 머무르고 퍼지는 방식 · 제공된 점수 기준">
    <div className="metrics-grid three-columns">{[['longevity', '지속력', 'clock'], ['sillage', '확산력', 'waves'], ['projection', '발향력', 'spark']]
      .map(([key, label, icon]) => <MetricCard key={key} label={label} icon={icon} metric={findMetric(metrics, `performance.${key}`, `wear_summary.${key}`)} />)}</div>
  </DetailSection>;
}

export function WearSection({ metrics }) {
  const primaryName = categoryLabel(text(metrics?.['performance.season.primary']?.label)
    || text(metrics?.['wear_summary.season.primary']?.label));
  return <DetailSection id="wear" title="계절과 착용 시간" subtitle="어떤 계절, 어떤 순간에 어울릴까요?"
    aside={primaryName ? <span className="soft-badge">주요 계절 · {primaryName}</span> : null}>
    <div className="metrics-grid season-grid">{[['spring', '봄', 'leaf'], ['summer', '여름', 'sun'], ['fall', '가을', 'leaf'], ['winter', '겨울', 'spark']]
      .map(([key, label, icon]) => <MetricCard key={key} label={label} icon={icon} compact
        metric={findMetric(metrics, `performance.season.by_season.${key}`, `wear_summary.season.by_season.${key}`,
          ...(key === 'fall' ? ['performance.season.by_season.autumn', 'wear_summary.season.by_season.autumn'] : []))} />)}</div>
    <div className="time-of-day"><MetricCard label="착용 시간" icon="moon" compact metric={findMetric(metrics, 'performance.time_of_day', 'wear_summary.time_of_day')} />
      <p>점수와 분류는 제공된 평가를 그대로 표시합니다.<br />등록되지 않은 평가값은 추정하지 않습니다.</p></div>
  </DetailSection>;
}

export function AppreciationSection({ metrics }) {
  const appreciation = metricFamily(metrics, 'appreciation');
  const price = metricFamily(metrics, 'price_value');
  const card = ([key, metric], index, prefix, label) => <MetricCard key={key} label={text(metric.label) || (key === prefix ? label : `${label} ${index + 1}`)} metric={metric} icon="spark" />;
  return <DetailSection id="appreciation" title="호감도와 가치 평가" subtitle="가격 대비 가치는 판매 가격이 아닌 평가 정보입니다.">
    <div className="metrics-grid two-columns">{appreciation.length ? appreciation.map((entry, index) => card(entry, index, 'appreciation', '호감도')) : <MetricCard label="호감도" />}
      {price.length ? price.map((entry, index) => card(entry, index, 'price_value', '가격 대비 가치')) : <MetricCard label="가격 대비 가치" />}</div>
  </DetailSection>;
}

export function OpinionsSection({ pros, cons }) {
  return <DetailSection id="opinions" title="장점과 단점" subtitle="향수를 바라보는 다양한 시선">
    <div className="opinion-columns">{[[pros, '장점', 'plus', 'pros'], [cons, '단점', 'minus', 'cons']].map(([data, title, icon, kind]) => <div className={`opinion-panel ${kind}`} key={kind}>
      <h3><span><Icon name={icon} size={18} /></span>{title}</h3>{items(data).length ? <ul>{items(data).map((row, index) => <li key={index}>
        <p>{text(row.text) || '내용 정보 없음'}</p>{(numeric(row.nRecords) || numeric(row.likeRatio)) && <span className="opinion-meta">
          {numeric(row.nRecords) ? `평가 ${formatNumber(row.nRecords)}건` : ''}{numeric(row.nRecords) && numeric(row.likeRatio) ? ' · ' : ''}
          {numeric(row.likeRatio) ? `평가 비율 ${formatScore(row.likeRatio)}` : ''}</span>}</li>)}</ul> : <p className="muted">등록된 {title} 정보가 없습니다.</p>}</div>)}</div>
  </DetailSection>;
}

export function SimilaritiesSection({ fragrances }) {
  return <DetailSection id="similarities" title="유사 향수" subtitle="이 향을 떠올리게 하는 다른 향수들">
    {items(fragrances).length ? <div className="similarity-list">{items(fragrances).map((fragrance, index) => <div className="similarity-item" key={fragrance.publicId ?? fragrance.fragranceSlug ?? index}>
      <span className="similarity-icon"><Icon name="bottle" size={24} /></span><div><h3>{text(fragrance.name) || text(fragrance.fragranceSlug) || '이름 정보 없음'}</h3>
        {numeric(fragrance.likeRatio) && <p>비교 평가 {formatScore(fragrance.likeRatio)}</p>}
        {numeric(fragrance.nRecords) && <span className="muted">평가 {formatNumber(fragrance.nRecords)}건</span>}</div>
      {text(fragrance.name) && <Link to={`/?q=${encodeURIComponent(fragrance.name)}`} className="similarity-link" aria-label={`${fragrance.name} 이름으로 찾아보기`}>
        <span>찾아보기</span><Icon name="arrow" size={18} /></Link>}</div>)}</div> : <p className="empty-copy">등록된 유사 향수 정보가 없습니다.</p>}
  </DetailSection>;
}
