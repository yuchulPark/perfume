# ScentRev MCP 출력 스키마와 요청 제한 오류

## 이번 오류에서 확인한 사실과 한계

발생 도구는 `search_fragrances_filtered`이고, 보고된 서버 메시지는 다음과 같습니다.

~~~text
Output validation error: Additional properties are not allowed ('quota', 'retry_after_ms' were unexpected)
~~~

이 문구는 출력 객체의 필드와 검증 스키마가 불일치한다는 뜻입니다. **이 문구만으로 API 할당량 초과나 실제 대기 시간을 확정할 수 없습니다.** `quota`는 할당량 관련 메타데이터, `retry_after_ms`는 대기 시간 힌트일 가능성이 있지만, 정상 응답에 붙은 메타데이터일 가능성도 배제할 수 없습니다. 현재 확인된 것은 두 필드의 이름이고 원래 오류 코드·필드 값·JSON 경로는 제공되지 않았습니다.

현재 프로젝트의 요청 인수는 `filter_brand_slug`, `min_rating_votes`, `result_limit`, `result_offset`입니다. `quota`와 `retry_after_ms`는 이 요청에 넣는 필드가 아닙니다.

조사한 세 경계는 다음과 같습니다.

| 경계 | 확인 결과 |
| --- | --- |
| Java MCP SDK 2.0.1 | `createSdkClient`가 이미 `enableCallToolSchemaCaching(false)`를 사용합니다. 설치된 SDK 소스의 `McpAsyncClient.validateToolResult`는 이 설정이 false이거나 `isError=true`이면 출력 스키마 검증을 건너뜁니다. |
| 애플리케이션 검색 DTO | `ScentRevFilteredSearchResponse`는 이미 `@JsonIgnoreProperties(ignoreUnknown = true)`입니다. DTO에 두 필드를 추가해도 서버의 검증 실패를 해결할 수 없습니다. |
| 연결된 ScentRev 도구의 등록 응답 정의 | 최상위에 `results`, `unresolved`, 페이지 정보, `error` 등이 있으며, `quota`와 최상위 `retry_after_ms`는 선언되어 있지 않습니다. `error.retry_after_ms`는 선택적인 number 필드로 선언되어 있습니다. |

등록 정의에서 가장 유력한 불일치 위치는 **`structuredContent` 최상위에 제한 메타데이터를 붙이는 응답 경로**입니다. `structuredContent.error.retry_after_ms`와 최상위 `structuredContent.retry_after_ms`는 서로 다른 위치입니다. 다만 보고된 오류에는 JSON 경로가 없어 검증 대상이 최상위 객체인지 중첩 객체인지까지 확정할 수 없습니다. 인증된 원시 `tools/list.outputSchema`와 실패 직전 서버 응답을 대조해야 합니다.

실제 서버의 `initialize`/`tools/list` 메타데이터 조회를 시도했지만 로컬에 ScentRev 인증 정보가 없어 HTTP 401에서 종료했습니다. `tools/call`, Import 및 DB 작업은 수행하지 않았습니다. 연결된 도구의 등록 정보는 조사했지만 인증된 서버의 현재 원시 스키마와 계정 할당량은 재조회하지 못했습니다.

동일한 오류 문구를 생성하는 [공식 Python MCP 서버 SDK 구현](https://github.com/modelcontextprotocol/python-sdk/blob/v1.26.0/src/mcp/server/lowlevel/server.py)은 도구 실행 결과를 `outputSchema`로 검증하고, 실패하면 원래 결과 대신 `isError=true`와 검증 오류 텍스트만 반환합니다. ScentRev가 동일 경로를 사용한다면 원래 `quota` 값과 `retry_after_ms` 값은 클라이언트까지 도달하지 않습니다. 이 구현과 프로젝트의 검증 비활성화 설정을 근거로 **이번 오류는 서버 측 출력 검증에서 발생했을 가능성이 높다고 판단**합니다. ScentRev의 서버 구현·버전 자체를 확인한 것은 아닙니다.

## 클라이언트 변경

정상 DTO, 요청 인수, SDK 설정 및 Import 흐름을 유지하고 다음 오류 구분을 추가했습니다.

| 실패 유형 | 판단 근거 | 처리 |
| --- | --- | --- |
| `RATE_LIMIT` | 명시적인 `rate_limit`, `rate_limited`, `rate_limit_exceeded`, `too_many_requests`, `quota_exceeded`, `quota_exhausted` 코드 또는 코드 `429` | 안전한 제한 예외로 중단. 정상 검색 페이지로 변환하지 않습니다. |
| `RATE_LIMIT`, HTTP 429 | SDK 2.0.1의 `McpTransportException` 메시지가 정확히 `Invalid request. Status code: 429`인 경우 | 확인된 HTTP 상태만 보존합니다. SDK가 버린 응답 헤더나 대기 시간은 추정하지 않습니다. |
| `SERVER_OUTPUT_SCHEMA` | 서버 오류 응답/JSON-RPC 오류 메시지가 `Output validation error:`로 시작 | 제공자 측 응답·스키마 수정이 필요하다는 안전한 예외로 중단합니다. 필드 이름만으로 요청 제한이나 대기 시간을 추정하지 않습니다. |
| 기존 `TOOL_CALL` 등 | 위 근거가 없는 오류 | 기존 분류와 실패 메시지를 유지합니다. |

구조화된 오류, 텍스트 JSON 오류 및 SDK의 중첩 JSON-RPC 오류를 검사합니다. `isError=false`여도 명시적 제한 코드가 있으면 필터 검색 결과를 반환하지 않습니다. 반대로 정상 페이지에 `quota`/대기 메타데이터만 있다는 이유로 제한 오류라고 단정하지 않습니다. 인증 실패·도구 없음·일반 전송 실패의 기존 분류도 유지합니다.

`ScentRevMcpClientException.getRetryAfterMs()`는 제공자의 **진단용 힌트**를 nullable `Long`으로 제공합니다. `error.retry_after_ms`가 있으면 이를 우선하고, 없으면 최상위 `retry_after_ms`를 확인합니다. 값은 0 이상이며 Java `long` 범위에 들어가는 JSON 정수만 허용합니다. 문자열, 소수, 음수, Boolean, null 및 범위 초과 값은 무시합니다. 잘못된 중첩 값이 있으면 상충하는 최상위 값으로 대신하지 않습니다. 검증 오류 텍스트에 필드 이름만 있으면 값은 null입니다.

대기 시간을 사용한 sleep, 재호출, 지수 backoff, 할당량 우회는 추가하지 않았습니다. 단일 브랜드 Import는 계속 첫 실패에서 중단하고 기존에 커밋된 페이지를 유지합니다. 실패한 페이지의 Profile/DB 쓰기와 다음 요청을 진행하지 않습니다. 페이지 트랜잭션과 중복 처리 코드는 변경하지 않았습니다.

WARN 로그는 실패 유형, 도구명, 정제한 서버 코드·메시지, 중첩 예외 원인과 검증된 `retryAfterMs`를 기록합니다. 기존 API 키·인증 헤더·토큰·비밀번호 마스킹 및 길이 제한을 유지하며, 전체 `quota` 객체·응답 본문·JSON-RPC data·원본 Throwable는 출력하지 않습니다.

## 서버 측에서 필요한 확인

[MCP 출력 스키마 계약](https://modelcontextprotocol.io/specification/2025-06-18/server/tools#output-schema)에 따르면 서버가 반환하는 구조화된 결과는 선언된 `outputSchema`에 맞아야 합니다. ScentRev 제공자는 실패 직전의 원래 응답과 해당 도구의 스키마를 대조하여 다음을 확인해야 합니다.

- 제한/할당량 응답과 정상 검색 응답 각각의 필드 위치 및 `additionalProperties` 설정.
- `quota`가 정상 메타데이터인지 제한 전용 데이터인지, 실패 시의 실제 제한 코드.
- `retry_after_ms`를 최상위에 제공할지 `error.retry_after_ms`에 제공할지에 대한 일관된 계약.
- 오류 응답을 스키마에 맞게 선언하거나 유효한 MCP `isError=true` 결과로 반환하는 경로.

이번 클라이언트 변경은 서버가 보내는 오류를 구분하고 안전하게 중단하도록 합니다. **이미 서버에서 검증 실패로 대체한 원래 응답을 복원하거나 제공자의 outputSchema를 수정할 수는 없습니다.** 요청 필드를 임의로 삭제하거나 DTO의 검증을 더 완화하는 방식으로 이 오류를 해결하지 않습니다.

## 검증

SDK·외부 요청과 저장소는 모의 객체 또는 메모리 트랜잭션 경계로 검증합니다. 실제 DB나 할당량을 사용하지 않습니다.

~~~bash
./mvnw -o -Dtest=ScentRevMcpClientLimitsTests,ScentRevMcpClientLoggingTests,ScentRevMcpClientTests,ScentRevMcpSearchTests,ScentRevMcpBrandListTests,ScentRevTargetedBrandClientTests,ScentRevSingleBrandFullProfileImportTests,ScentRevBrandImportTransactionTests test
~~~

제한 응답의 검색 변환 차단, 스키마 오류와 제한 오류 구분, 잘못된 대기 값과 overflow 거부, 민감정보 마스킹, 호출 1회 유지 및 DISCOVERY/PROFILE 실패 시 후속 호출·페이지 쓰기 중단을 확인합니다. 기존 정상 검색, Profile, 중복 처리, 요청 간격 및 트랜잭션 테스트도 함께 실행합니다.

이번 변경의 검증 결과는 **199개 통과, 실패·오류·건너뜀 0개**입니다. 실제 ScentRev 검색/Import, DB 연결·변경 및 서버 배포는 수행하지 않았습니다.
