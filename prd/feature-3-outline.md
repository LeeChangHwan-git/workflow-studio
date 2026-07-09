# 기능3 — Outline Wiki 연동

## 구현 상태: ✅ 완료 (2026-07-10)

## 한 줄 요약

사내 Outline Wiki의 문서를 파이프라인 노드로 조회해 AI 질의에 활용하는 기능.  
`OUTLINE` 노드가 컬렉션/문서 목록·검색·본문 내보내기를 수행하고 결과를 Markdown 변수로 넘긴다.

---

## 배경

- 회사 전사 게시판으로 커스터마이즈된 Outline Wiki 사용 중
- 매일 새 문서가 올라오는 컬렉션에서 프레임워크 관련 글만 골라 요약하는 작업을 자동화하고 싶어서 구현

---

## Outline API 개요

Outline의 REST API는 **모든 엔드포인트가 POST** 이며, 인증 헤더에 Bearer 토큰을 사용한다.

```
Authorization: Bearer ol_api_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Content-Type: application/json
```

사용하는 엔드포인트:

| 엔드포인트 | 요청 바디 | 반환 |
|-----------|----------|------|
| `POST /api/collections.list` | `{"limit": N}` | `{ data: [{ id, name, ... }] }` |
| `POST /api/documents.list` | `{"collectionId": "...", "limit": N}` | `{ data: [{ id, title, collectionId, ... }] }` |
| `POST /api/documents.search` | `{"query": "...", "limit": N}` | `{ data: [{ document: { id, title }, context: "..." }] }` |
| `POST /api/documents.export` | `{"id": "doc-uuid"}` | `{ data: "# 마크다운 본문..." }` |

> Outline `auth.info` 엔드포인트는 특정 환경에서 500 오류를 낼 수 있으나, 데이터 API는 정상 동작한다.

---

## API 토큰 형식 규칙

Outline API 토큰은 **정확히 이 형식**이어야 한다:

```
ol_api_ + 38자 영숫자(_포함)
```

- `ol_api_` 접두사 (7자) + 38자 = 총 45자
- Outline 서버가 `ApiKey.match()` 메서드로 형식을 검증하며, 38자 조건이 맞지 않으면 "Unable to decode token" 오류
- SHA-256 해시가 DB에 저장됨 (원문 토큰은 최초 발급 시에만 확인 가능)

```bash
# 38자 hex 생성 (19바이트 = 38 hex자)
openssl rand -hex 19
# 예: e62aaa4d5558ad148ac7d8166c58a00f09b321
# → ol_api_e62aaa4d5558ad148ac7d8166c58a00f09b321
```

---

## 로컬 Docker Outline 환경 설정

다른 PC에서 동일한 환경을 재현하려면 아래 단계를 따른다.

### 1. Docker 컴포즈 실행

```bash
cd outline-local
docker compose up -d
```

컴포즈 파일(`outline-local/docker-compose.yml`)이 아래 서비스를 구성한다:

| 서비스 | 이미지 | 포트 | 용도 |
|--------|--------|------|------|
| postgres | postgres:15-alpine | — | Outline DB |
| redis | redis:7-alpine | — | 세션/캐시 |
| minio | minio/minio:latest | 9000, 9001 | 파일 스토리지 (S3 호환) |
| mailhog | mailhog/mailhog:latest | 1025 (SMTP), 8025 (웹) | 로컬 메일 수신 |
| outline | outlinewiki/outline:latest | 3000 | Outline 앱 |

**중요 환경변수** (docker-compose.yml에 하드코딩됨):
```
PGSSLMODE=disable          # SSL 연결 시도 방지 (없으면 DB 연결 오류)
FORCE_HTTPS=false          # HTTP 리다이렉트 방지 (없으면 localhost가 https로 튕김)
URL=http://localhost:3000
SECRET_KEY=71acde7b93828054abe2275d8a82db8cc6e73068a6e4915f1a85288e4ff0f196
UTILS_SECRET=d9b67c9686f4d405a84116b9295557f779ee38bee57be1eb85207fdb66cc479b
```

### 2. Outline 초기 계정 생성

Outline이 뜨면(`http://localhost:3000`) **이메일 로그인**으로 계정 생성:

1. `http://localhost:3000` 접속
2. 이메일 입력 (임의 주소 가능, 예: `admin@outline.local`)
3. MailHog 웹 UI(`http://localhost:8025`)에서 발송된 매직링크 이메일 확인
4. 링크 클릭 → 로그인 완료

### 3. API 토큰 발급

1. Outline 우측 상단 프로필 → **Settings** → **API Tokens**
2. **New token** → 이름 입력 → **Create**
3. 발급된 토큰 전체 복사 (페이지를 벗어나면 다시 볼 수 없음)
4. 토큰 형식 확인: `ol_api_` + 38자여야 함

> **수동 생성이 필요한 경우**: DB에 직접 INSERT 방법  
> ```sql
> -- Outline DB에 접속
> docker exec -it outline-local-postgres-1 psql -U outline -d outline
>
> -- 토큰 해시 계산 (Python)
> python3 -c "import hashlib; print(hashlib.sha256(b'ol_api_YOUR38CHARS').hexdigest())"
>
> -- 삽입 (userId는 users 테이블에서 확인)
> INSERT INTO api_keys (id, name, secret, "userId", "createdAt", "updatedAt")
> VALUES (gen_random_uuid(), 'local-test', 'ol_api_YOUR38CHARS',
>         (SELECT id FROM users LIMIT 1), NOW(), NOW());
> ```

### 4. 앱 설정

앱 실행 후 **설정** 화면에서:

```
Outline URL:   http://localhost:3000
Outline Token: ol_api_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

---

## 테스트 데이터 (로컬 Outline)

로컬 Outline에 생성한 컬렉션과 문서 목록 (다른 PC에서 재현 가능, 내용은 아래 참조).

### 컬렉션

| 이름 | 용도 |
|------|------|
| 🛠 개발팀 | 개발팀 기술 문서 |
| 📢 전사 공지 | 전사 공지사항 |

### 문서 목록

| 컬렉션 | 제목 | 내용 요약 |
|--------|------|----------|
| 🛠 개발팀 | Spring Boot 3.x → 4.x 마이그레이션 가이드 | Java 21 필수, Security 7.x 변경사항, 마이그레이션 단계 |
| 🛠 개발팀 | React 18 → 19 업그레이드 체크리스트 | Breaking changes, createRoot 필수, 의존성 호환성 |
| 🛠 개발팀 | 사내 표준 프레임워크 선정 기준 및 결정 사항 (2026 H2) | Spring Boot 3.x + React 18/19 채택, 선정 기준 가중치 |
| 📢 전사 공지 | 7월 팀 회식 안내 및 장소 투표 | 2026-07-25 회식, 투표 마감 2026-07-18 |
| 📢 전사 공지 | 2026년 하계 휴가 신청 방법 변경 안내 | HR 시스템으로 변경, 신청 기간 2026-07-14~21 |

문서 생성 스크립트: `outline-local/create_docs.py` (Python 3, 추가 패키지 불필요)

```bash
# 로컬 Outline이 떠 있는 상태에서 실행
echo "ol_api_YOUR_TOKEN_HERE" > /tmp/outline_token.txt
python3 outline-local/create_docs.py
```

---

## 예시 파이프라인: 🔍 프레임워크 공지 모니터링

개발팀 컬렉션에서 "프레임워크" 키워드 문서를 검색하고,  
개발팀 전체 문서 목록도 가져와 AI가 필요한 것만 요약하도록 구성.

```json
{
  "name": "🔍 프레임워크 공지 모니터링",
  "nodes": [
    {
      "id": "n1",
      "type": "OUTLINE",
      "config": {
        "operation": "문서 검색",
        "query": "프레임워크",
        "limit": "50",
        "outputVar": "frameworkSearch"
      }
    },
    {
      "id": "n2",
      "type": "OUTLINE",
      "config": {
        "operation": "문서 목록",
        "collectionId": "{🛠 개발팀 컬렉션 ID}",
        "limit": "50",
        "outputVar": "devTeamDocs"
      }
    }
  ],
  "connections": []
}
```

> n1, n2는 독립 노드로 연결 없음 (둘 다 결과를 변수에 저장, 이후 AI 노드에서 `${frameworkSearch}`, `${devTeamDocs}` 참조).

---

## 목 컨트롤러 (테스트용)

Outline 서버 없이 노드 동작을 확인할 때는 앱 설정의 URL을 Mock으로 바꾼다.

```
Outline URL:  http://localhost:{앱포트}/outline-mock
Outline Token: (아무 값이나 ol_api_ 형식으로)
```

Mock 구현: `controller/OutlineMockController.java` — `/outline-mock/api/*` 경로에 하드코딩된 응답 반환.

---

## 알려진 한계

| 항목 | 현재 | 비고 |
|------|------|------|
| 인증 방식 | Bearer 토큰만 지원 | OAuth/SSO 미지원 |
| `auth.info` 엔드포인트 | 일부 환경에서 500 오류 | 데이터 API는 정상 동작, 실사용에 영향 없음 |
| 문서 쓰기 | 미지원 (읽기/검색/내보내기만) | 향후 `documents.create` 추가 가능 |
| 페이지네이션 | limit 파라미터만 지원 | offset 기반 다음 페이지 미지원 |
