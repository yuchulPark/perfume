import { useEffect } from 'react';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { Icon } from './components/Icon.jsx';
import { NotFound } from './components/States.jsx';
import CatalogPage from './pages/CatalogPage.jsx';
import PerfumeDetailPage from './pages/PerfumeDetailPage.jsx';
import RecommendPage from './pages/RecommendPage.jsx';
import LoginPage from './pages/LoginPage.jsx';
import { AuthProvider, useAuth } from './auth/AuthContext.jsx';
import { SupportProvider, useSupportEnabled } from './support/SupportContext.jsx';
import SupportChatWidget from './support/SupportChatWidget.jsx';
import AdminSupportPage from './support/AdminSupportPage.jsx';
import './support/support.css';

export default function App() {
  return <AuthProvider><SupportProvider><SiteApp /></SupportProvider></AuthProvider>;
}
function SiteApp() {
  const auth = useAuth();
  const supportEnabled = useSupportEnabled();
  const { pathname, search, hash } = useLocation();
  useEffect(() => { window.scrollTo(0, 0); }, [pathname]);
  return <>
    <a className="skip-link" href="#main-content">본문으로 바로가기</a>
    <header className="site-header"><div className="container header-inner">
      <Link to="/" className="brand-mark" aria-label="향의 기록 홈"><span className="brand-symbol"><Icon name="bottle" size={25} /></span>
        <span><strong>향의 기록</strong><span className="brand-caption">SCENT ARCHIVE</span></span></Link>
      <nav aria-label="메인 메뉴"><Link to="/" aria-current={pathname === '/' ? 'page' : undefined} className={pathname === '/' ? 'nav-link active' : 'nav-link'}>향수 둘러보기<Icon name="arrow" size={16} /></Link>
        <Link to="/recommend" aria-current={pathname === '/recommend' ? 'page' : undefined} className={pathname === '/recommend' ? 'nav-link active' : 'nav-link'}><Icon name="spark" size={16} />AI 향수 추천</Link>
        {supportEnabled && auth.user?.role === 'ADMIN' && <Link className="nav-link" to="/admin/support">고객센터 관리</Link>}
        <div className="header-auth">{auth.phase === 'loading' ? <span role="status">로그인 확인 중</span> : auth.user
          ? <><span className="auth-username">{auth.user.username}</span><button type="button" className="nav-link auth-logout" onClick={auth.signOut}>로그아웃</button></>
          : <Link className="nav-link" to="/login" state={{ from: `${pathname}${search}${hash}` }}>로그인</Link>}</div></nav>
    </div></header>
    <main id="main-content">{auth.phase === 'error' && <div className="container auth-status" role="alert">{auth.error} <button type="button" onClick={auth.reload}>로그인 상태 다시 확인</button></div>}<Routes>
      <Route path="/" element={<CatalogPage />} />
      <Route path="/perfumes/:id" element={<PerfumeDetailPage />} />
      <Route path="/recommend" element={<RecommendPage />} />
      <Route path="/login" element={<LoginPage />} />
      <Route path="/admin/support" element={<AdminSupportPage />} />
      <Route path="*" element={<div className="container page-space"><NotFound /></div>} />
    </Routes></main>
    <footer className="site-footer"><div className="container footer-inner"><span className="footer-name">향의 기록 <span>SCENT ARCHIVE</span></span>
      <p>취향을 발견하고, 향을 더 깊이 이해하는 곳.</p><span>하나의 향, 나만의 이야기.</span></div></footer>
    {pathname !== '/admin/support' && <SupportChatWidget />}
  </>;
}
