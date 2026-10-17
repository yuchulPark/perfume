# 향의 기록 — React + Vite frontend

기존 Spring Boot REST API와 PostgreSQL에 저장된 데이터만 표시하는 한국어 향수 정보 UI입니다. 기존 `frontend/`가 없어 새 디렉터리를 생성했습니다. 실제 데이터가 없는 경우 예시 향수를 대신 표시하지 않습니다.

## 실행

1. Eclipse에서 기존 `com.perfume.PerfumeApplication`을 API용으로 실행합니다. 기존 DB 환경변수를 유지하고 아래 VM arguments를 사용합니다.

   ```text
   -Dscentrev.brand-import=false
   -Dspring.jpa.hibernate.ddl-auto=validate
   -Dspring.sql.init.mode=never
   ```

   기존 테이블이 준비되어 있어야 합니다. 프론트엔드를 위한 DB 변경은 없습니다. API는 `http://localhost:8081`이며, UI 조회에는 `SCENTREV_API_KEY`가 필요하지 않습니다.

2. 프로젝트의 `frontend`에서 설치 및 실행합니다.

   ```powershell
   cd C:\dev\projects\perfume\frontend
   npm.cmd install
   npm.cmd run dev
   ```

   **http://localhost:5173**을 엽니다. PowerShell 실행 정책 때문에 `npm.ps1`이 차단될 수 있어 Windows 명령은 `npm.cmd`로 적었습니다. CMD 또는 다른 셸에서는 `npm install`, `npm run dev`를 사용할 수 있습니다.

   Vite 8의 Node 요구사항은 20.19+ 또는 22.12+입니다. 구현/검증 환경은 Node 24.21.0입니다. [Vite 공식 안내](https://vite.dev/guide/)

3. 빌드/미리보기:

   ```powershell
   npm.cmd run build
   npm.cmd run preview
   ```

   빌드 결과는 `dist/`에 생성됩니다. 미리보기는 `http://localhost:4173`입니다.

개발 서버와 미리보기 서버의 `/api` 요청은 `vite.config.js`에서 기존 `http://localhost:8081`로 프록시합니다. Spring Boot CORS 설정은 바꾸지 않습니다. 배포된 정적 파일은 Vite 개발 프록시를 사용하지 않으므로, 실제 배포 환경에서는 동일 출처 `/api` 라우팅과 SPA 경로의 `index.html` fallback을 제공해야 합니다. [Vite 프록시 문서](https://vite.dev/config/server-options.html#server-proxy)

## 페이지와 API

| 화면 | URL | REST 요청 |
| --- | --- | --- |
| 메인/향수 목록 | `/` | `/api/brands`, `/api/perfumes?page=0&size=20` |
| 검색/브랜드 필터 | `/?q=검색어&brandSlug=creed&page=1` | 키워드가 있으면 `/api/perfumes/search`, 없으면 `/api/perfumes`; `q`, `brandSlug`, `page`, `size=20` 전달 |
| 향수 상세 | `/perfumes/{id}` | `/api/perfumes/{id}` |
| AI 향수 추천 | `/recommend` | `POST /api/ai/perfumes/recommend` |

- 검색은 입력 후 350ms 지연 처리하며 Enter/검색 버튼으로 즉시 실행할 수도 있습니다. 브랜드 변경은 즉시 반영됩니다. 검색이나 브랜드가 바뀌면 첫 페이지로 돌아갑니다.
- 검색·브랜드·페이지는 URL에 보존됩니다. 카드에서 상세로 이동 후 `향수 목록으로`를 누르면 이전 조건으로 돌아옵니다.
- 목록은 항상 기본 20개이며, 전체 건수와 페이지 수는 응답 DTO의 실제 값을 사용합니다.
- 유사 향수 DTO에는 로컬 numeric ID가 없으므로 임의의 상세 URL을 만들지 않습니다. 제공된 이름이 있을 때만 그 이름으로 목록을 검색하는 링크를 제공합니다.
- 상세는 설명/연도/이미지, 성별/평점/리뷰, 레이어별 및 레이어 없는 노트, 어코드, 조향사, 지속력/확산력/발향력, 계절/시간, 호감도/가치 평가, 장단점, 유사 향수를 표시합니다.

## 실제 DTO와 빈 데이터 처리

API 계약은 기존 [REST 문서](../docs/rest-catalog-api.md) 및 Java DTO를 그대로 사용합니다. 응답 필드명은 `releaseYear`, `reviewsCount`, `notes.top/middle/base/unlayered`, `metrics`, `pros`, `cons`, `similarFragrances` 등입니다.

`metrics`는 필드 경로를 키로 사용하므로 `identity.rating`, `identity.gender`, `performance.longevity/sillage/projection`, `performance.season.by_season.*`, `performance.time_of_day`, `appreciation*`, `price_value*`에서 값을 읽습니다. 실제 값이 없는 성능/착용 평가는 저장된 `wear_summary.*` 경로를 확인합니다. 이미 데이터가 있는 기본 metric이 있으면 그 값을 우선합니다. 상세를 표시하기 위한 추가 API 요청은 없습니다.

- 평점은 임의의 5점 척도/별점으로 환산하지 않습니다. 지속력을 임의의 시간으로 변환하지 않습니다. 점수는 표시용으로 최대 소수 3자리까지 포맷하며, 원본 응답이나 DB 값은 변경하지 않습니다.
- 어코드 `%`는 실제 `percentage`가 제공된 경우에만 표시합니다. 없으면 실제 `score` 또는 `정보 없음`을 표시합니다.
- 시즌은 제공된 계절별 점수만 표시합니다. 최고 점수를 계산해 추천 계절을 만들어내지 않습니다. `primary.label`이 실제로 있으면 그 값을 표시합니다.
- NULL/빈 배열/누락된 선택 필드는 `정보 없음`, 자연스러운 안내, 빈 목록으로 처리합니다. 실제 `0` 점수·표본 수·리뷰 수는 0으로 표시합니다.
- 이미지 URL이 없거나 이미지 로드가 실패하면 재사용 가능한 SVG Placeholder를 표시합니다. 외부 폰트, 임의의 제품 이미지, 생성한 향수 정보는 사용하지 않습니다.
- 알려진 평가 분류를 한국어로 번역하며, 알 수 없는 분류는 제공된 문구를 유지합니다.
- 로딩 skeleton, 빈 검색 결과, API/연결 오류와 수동 재시도, 404/잘못된 상세 경로를 처리합니다.

## 요청 중복과 취소

TanStack Query가 동일한 query key의 진행 중 요청을 공유합니다. 키워드를 trim/소문자로 정규화해 API의 대소문자 무시 검색과 맞춥니다. 목록/상세는 60초, 브랜드는 10분 동안 fresh cache를 사용합니다. 브라우저 포커스·재연결 시 자동 조회와 실패 시 자동 재시도를 끄고, 필요한 경우 화면의 수동 재시도를 사용합니다. 필터가 바뀌면 이전 요청을 취소하고 늦게 도착한 응답이 최신 검색 결과를 덮어쓰지 않도록 합니다. 페이지 변경 중에는 같은 필터의 이전 목록을 로딩 상태로 유지합니다. 검색·브랜드가 달라지면 이전 결과를 새 결과처럼 보여주지 않습니다.

## RAG 4차 — AI 향수 추천

API 계약은 [RAG 추천 문서](../docs/perfume-rag-recommendation.md), `PerfumeRecommendationController`, `PerfumeSemanticSearchRequest`, `PerfumeRecommendationResponse`, `RecommendationFailure`를 기준으로 합니다.

- 상단 `AI 향수 추천` 메뉴와 `/recommend`에서 자연어 질문, 예시 질문, 검색 개수(topK)를 제공합니다. 예시를 누르면 입력만 채우며, 추천받기를 눌러야 요청합니다. 질문은 공백만 입력할 수 없고 최대 500 UTF-16 단위입니다. 검색 개수는 정수 1~20, 기본 5입니다.
- `api/recommendations.js`가 기존 엔드포인트에 `{query, topK}`만 POST합니다. 일반 GET용 `client.js`와 `queries.js`의 제한·캐시 정책은 유지합니다. AI 요청은 React 페이지 메모리에서만 관리하여 Query 캐시, URL, localStorage, sessionStorage, 콘솔에 질문·답변을 남기지 않습니다. 화면을 떠나거나 새로고침하면 입력과 결과가 사라집니다.
- 답변은 HTML 실행 없이 한국어 문단으로 표시합니다. `[1]`은 `sources[0]` 카드로 연결하고 서버 배열을 재정렬하지 않습니다. 근거 카드에는 향수명, 브랜드, 실제 검색 유사도와 `/perfumes/{perfumeId}` 링크를 표시합니다. 유사도는 확률이나 평점으로 변환하지 않습니다.
- 전체 카탈로그가 아닌 임베딩된 일부 향수의 저장된 정보를 사용하며 최신 정보·조건 충족을 보장하지 않는다는 안내를 표시합니다. 임베딩 개수나 브랜드를 하드코딩하지 않습니다. 검색 개수와 실제 근거/답변에 소개되는 향수 개수가 다를 수 있습니다.
- 대기 중 중복 제출을 막고 취소 버튼을 제공합니다. AbortController로 사용자 취소·화면 이탈·클라이언트 기한 초과를 전달하며, 이전 요청이 늦게 도착해도 새 결과를 덮어쓰지 않습니다. 가짜 진행률이나 처리 단계는 없습니다. 연결 취소만으로 백엔드/Ollama 작업이 즉시 중단되는 것은 보장되지 않습니다.
- 결과 없음(`200`, 빈 sources), 추천 비활성화(`503/RECOMMEND_DISABLED`), 동시 요청 초과(`429/RECOMMEND_BUSY`), 잘못된 요청(`400`), 근거 이용 불가(`503/EVIDENCE_UNAVAILABLE`), 서비스 장애(`503`), 생성/서버 오류(`502`), 기한 초과(`504` 또는 클라이언트 기한 초과)를 구분합니다. 고정 코드와 HTTP 상태가 일치할 때만 코드를 사용하며 알 수 없는 코드·HTML 프록시 오류는 상태별 안전한 안내로 처리합니다. 원시 서버 detail/title, 내부 오류를 화면에 노출하지 않으며 자동 재시도는 없습니다.

### AI 전용 제한 시간

| 구간 | 설정 | 근거 |
| --- | --- | --- |
| 백엔드 전체 요청 | 기본 420초 | `PerfumeRecommendationOptions.requestTimeout`, 이번 작업에서 변경 없음 |
| Nginx AI 프록시 읽기 | 450초 | 정확한 `/api/ai/perfumes/recommend` location에만 적용, 백엔드 오류 응답 여유 30초 |
| AI 클라이언트 | 480초 | 프록시 오류 응답 여유 30초, 응답 body 읽기까지 적용 |
| 일반 API | GET 클라이언트 15초, Nginx 읽기 30초 | 기존 값 유지 |

### 실제 API 연동에 남은 설정

프런트엔드 전용 API 키나 새 환경변수는 필요하지 않습니다. 로컬 Vite의 `/api`는 기존 `localhost:8081`로, Nginx는 기존 backend upstream으로 연결됩니다.

백엔드는 기본 비활성화 상태를 유지합니다. 실제 추천 검증은 제한된 로컬 환경에서 운영자가 기존 RAG 문서에 따라 검색·추천 활성화 플래그와 각각의 고정 모델 전체 digest 등 기존 필수 설정을 준비한 후 별도로 진행해야 합니다. **공개 서비스는 인증과 사용자별 사용량 제한이 마련되기 전까지 AI 플래그를 활성화하지 않습니다.** 실제 DB/Ollama 호출과 설정 변경은 이번 프런트엔드 검증에 포함되지 않습니다.

운영 연결 시 다른 프록시가 앞에 있다면 해당 경로의 제한도 확인해야 합니다. 백엔드 전체 제한을 기본 420초보다 길게 재설정하는 경우 Nginx와 클라이언트 제한도 함께 조정해야 합니다. 로컬에 Nginx 실행 파일이 없어 `nginx -t`는 실행하지 않았습니다. 기존 Dockerfile의 Nginx 구문 검증은 유지합니다.

## 파일 구조

```text
frontend/
  package.json / package-lock.json  의존성 및 실행 명령
  index.html / vite.config.js       진입점, /api 프록시
  public/favicon.svg               사이트 아이콘
  src/
    main.jsx / App.jsx             라우팅, 공통 헤더/푸터
    api/client.js / queries.js      REST 클라이언트, 캐시/취소
    api/recommendations.js          AI 전용 POST, 검증, 기한, 안전한 오류 처리
    lib/display.js                 NULL, 점수, metric 경로 처리
    pages/                         목록, 상세, AI 추천 페이지
    components/                    검색, 카드, 이미지, 페이지, 상세 섹션, 상태 UI
    styles.css                     PC/모바일 반응형 스타일
  tests/display.test.js             순수 로직/클라이언트 테스트
  tests/recommendations.test.js      AI 클라이언트/오류/기한/취소 Mock 테스트
  tests/recommendation-components.test.js  실제 JSX의 React 렌더링 단위 테스트
  tests/browser/catalog.spec.js     모의 REST 응답 기반 화면 테스트
  tests/browser/recommendation.spec.js  AI 입력/결과/취소/오류/개인정보 Mock 화면 테스트
  playwright.config.js             설치된 Chrome 사용
```

## 검증

```powershell
npm.cmd run test
npm.cmd run build
npm.cmd run test:browser
```

브라우저 테스트는 빌드된 `dist`를 preview 서버로 열고 설치된 Google Chrome을 headless로 실행합니다. Chrome이 설치되어 있어야 합니다. 모든 `/api` 요청은 Playwright에서 모의 응답으로 가로채므로 실제 Spring Boot·PostgreSQL·ScentRev·Ollama 호출이 발생하지 않습니다. 테스트 데이터는 `tests/`에만 있으며 production bundle이나 화면의 대체 데이터에 포함되지 않습니다. PC(1440px)·태블릿(820px)·모바일(390px, 최소 320px)에서 기존 검색 지연/중복 요청, 필터/페이지, 상세/뒤로 이동, NULL/0, 이미지 실패, 오류 재시도, 오래된 응답의 경합 및 AI 추천의 주요 흐름을 검증합니다. 클라이언트 480초 기한은 가상 시계로 검증하여 실제 모델 호출이나 긴 대기를 하지 않습니다. 스크린샷과 실패 추적은 git에서 제외된 `test-results/`에 생성됩니다.

React 단위 테스트는 기존 Vite로 실제 JSX 모듈을 변환하고 React 서버 렌더러로 상태·문단·출처·라우팅을 검증합니다. 사용자 상호작용과 DOM의 로딩·취소·오류·상세 이동은 별도 Playwright Mock 테스트로 검증합니다. 새 테스트 패키지는 추가하지 않습니다.

RAG 4차 검증 결과: `npm test` **35개 통과**, `npm run build` **성공**, `npm run test:browser` **69개 통과**(PC·태블릿·모바일), 실패 0개입니다. 기존 검색·브랜드 필터·페이지네이션·상세 회귀 테스트도 포함합니다. 제한된 Windows 실행 환경에서 Playwright의 미리보기 서버 종료가 지연되어, 검증에서 띄운 preview 프로세스만 종료해 정리했고 테스트 명령의 종료 코드 0을 확인했습니다. 변경 범위는 `frontend/`뿐이며 백엔드·DB·모델·임베딩·Checkpoint·Jenkins·systemd는 변경하지 않았습니다.
