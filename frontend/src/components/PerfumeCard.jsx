import { Link, useLocation } from 'react-router-dom';
import { PerfumeImage } from './PerfumeImage.jsx';
import { Icon } from './Icon.jsx';
import { numeric, text } from '../lib/display.js';

export function PerfumeCard({ perfume }) {
  const location = useLocation();
  return <Link className="perfume-card" to={`/perfumes/${perfume.id}`} state={{ from: location.pathname + location.search }}>
    <PerfumeImage src={perfume.imageUrl} name={perfume.name} />
    <div className="card-body"><span className="card-brand">{text(perfume.brand?.name) || '브랜드 정보 없음'}</span>
      <h3>{text(perfume.name) || '이름 정보 없음'}</h3><div className="card-bottom">
        <span>{numeric(perfume.releaseYear) ? `${perfume.releaseYear}년 출시` : '출시연도 미등록'}</span>
        <span className="card-arrow"><Icon name="arrow" size={18} /></span></div></div>
  </Link>;
}
