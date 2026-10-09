import { useEffect } from 'react';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { Icon } from './components/Icon.jsx';
import { NotFound } from './components/States.jsx';
import CatalogPage from './pages/CatalogPage.jsx';
import PerfumeDetailPage from './pages/PerfumeDetailPage.jsx';

export default function App() {
  const { pathname } = useLocation();
  useEffect(() => { window.scrollTo(0, 0); }, [pathname]);
  return <>
    <a className="skip-link" href="#main-content">본문으로 바로가기</a>
    <header className="site-header"><div className="container header-inner">
      <Link to="/" className="brand-mark" aria-label="향의 기록 홈"><span className="brand-symbol"><Icon name="bottle" size={25} /></span>
        <span><strong>향의 기록</strong><span className="brand-caption">SCENT ARCHIVE</span></span></Link>
      <nav aria-label="메인 메뉴"><Link to="/" className={pathname === '/' ? 'nav-link active' : 'nav-link'}>향수 둘러보기<Icon name="arrow" size={16} /></Link></nav>
    </div></header>
    <main id="main-content"><Routes>
      <Route path="/" element={<CatalogPage />} />
      <Route path="/perfumes/:id" element={<PerfumeDetailPage />} />
      <Route path="*" element={<div className="container page-space"><NotFound /></div>} />
    </Routes></main>
    <footer className="site-footer"><div className="container footer-inner"><span className="footer-name">향의 기록 <span>SCENT ARCHIVE</span></span>
      <p>취향을 발견하고, 향을 더 깊이 이해하는 곳.</p><span>하나의 향, 나만의 이야기.</span></div></footer>
  </>;
}
