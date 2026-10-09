import { useEffect, useState } from 'react';
import { safeImageUrl, text } from '../lib/display.js';

export function BottleIllustration({ decorative = false }) {
  return <svg className="bottle-illustration" viewBox="0 0 160 210" fill="none" aria-hidden="true">
    <ellipse cx="80" cy="194" rx="48" ry="7" fill="currentColor" opacity=".06" />
    <rect x="61" y="22" width="38" height="34" rx="3" fill="currentColor" opacity=".72" />
    <path d="M66 57h28v10H66z" fill="currentColor" opacity=".23" />
    <rect x="33" y="68" width="94" height="117" rx="12" stroke="currentColor" strokeWidth="1.5" opacity=".35" />
    <rect x="39" y="75" width="82" height="103" rx="8" fill="currentColor" opacity={decorative ? '.15' : '.035'} />
    <path d="M46 80v90" stroke="white" strokeWidth="3" opacity=".55" />
    <rect x="50" y="109" width="60" height="39" rx="1" fill="#f5f3ed" stroke="currentColor" strokeWidth=".6" opacity=".85" />
    <path d="M67 121h26M73 128h14M76 136h8" stroke="currentColor" strokeWidth="1.2" opacity=".5" />
  </svg>;
}

export function PerfumeImage({ src, name, className = '', eager = false }) {
  const source = safeImageUrl(src);
  const [failed, setFailed] = useState(false);
  useEffect(() => { setFailed(false); }, [source]);
  return <div className={`perfume-image ${className}`}>
    {source && !failed ? <img src={source} alt={`${text(name) || '향수'} 이미지`} loading={eager ? 'eager' : 'lazy'}
      decoding="async" referrerPolicy="no-referrer" onError={() => setFailed(true)} />
      : <div className="image-placeholder" role="img" aria-label={`${text(name) || '향수'} 이미지 없음`}>
        <BottleIllustration /><span>이미지 없음</span></div>}
  </div>;
}
