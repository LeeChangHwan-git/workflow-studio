#!/usr/bin/env python3
"""
로컬 Outline에 테스트 문서를 생성하는 스크립트.

사용법:
  echo "ol_api_YOUR_TOKEN" > /tmp/outline_token.txt
  python3 outline-local/create_docs.py

  또는 토큰을 직접 지정:
  OUTLINE_TOKEN=ol_api_... python3 outline-local/create_docs.py
"""

import urllib.request, json, os, sys

OUTLINE_URL = os.environ.get("OUTLINE_URL", "http://localhost:3000")

token_env = os.environ.get("OUTLINE_TOKEN", "")
if token_env:
    TOKEN = token_env
elif os.path.exists("/tmp/outline_token.txt"):
    TOKEN = open("/tmp/outline_token.txt").read().strip()
else:
    print("Error: 토큰이 없습니다. OUTLINE_TOKEN 환경변수 또는 /tmp/outline_token.txt 파일을 설정하세요.")
    sys.exit(1)

def api(path, body):
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        f"{OUTLINE_URL}/api/{path}",
        data=data,
        headers={
            "Authorization": f"Bearer {TOKEN}",
            "Content-Type": "application/json",
        }
    )
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read())

# ── 컬렉션 생성 또는 조회 ──────────────────────────────────────
print("=== 컬렉션 확인/생성 ===")
cols_resp = api("collections.list", {"limit": 100})
existing = {c["name"]: c["id"] for c in cols_resp["data"]}

def ensure_collection(name, color):
    if name in existing:
        print(f"  기존: {name} → {existing[name]}")
        return existing[name]
    r = api("collections.create", {"name": name, "color": color, "sharing": True})
    cid = r["data"]["id"]
    print(f"  생성: {name} → {cid}")
    return cid

COL1 = ensure_collection("📢 전사 공지", "#F59E0B")
COL2 = ensure_collection("🛠 개발팀",    "#0EA5E9")

# ── 문서 생성 ─────────────────────────────────────────────────
DOCS = [
    {
        "title": "Spring Boot 3.x → 4.x 마이그레이션 가이드",
        "collectionId": COL2,
        "publish": True,
        "text": """# Spring Boot 3.x → 4.x 마이그레이션 가이드

## 개요
사내 백엔드 서비스를 Spring Boot 3.x에서 4.x로 업그레이드할 때 필요한
체크리스트와 주의사항을 정리한 문서입니다.

## 주요 변경사항

### 1. Java 버전 요구사항
- Spring Boot 4.x는 **Java 21 이상** 필수
- 기존 Java 17 프로젝트는 LTS 업그레이드 선행 필요

### 2. 의존성 변경
- `spring-boot-starter-web` → Reactive 기반 기본 전환 검토
- Hibernate 6.x 기본 적용 → `@Entity` 어노테이션 변경 사항 확인

### 3. 보안 설정
- Spring Security 7.x 적용 → `WebSecurityConfigurerAdapter` 완전 제거
- `SecurityFilterChain` Bean 방식으로 전환 필수

## 마이그레이션 단계
1. `build.gradle` / `pom.xml` 버전 변경
2. deprecated API 제거 및 대체
3. 테스트 전 실행 → 통합 테스트 통과 확인
4. 스테이징 배포 후 2주 모니터링

## 담당자
프레임워크팀 검토 필수""",
    },
    {
        "title": "React 18 → 19 업그레이드 체크리스트",
        "collectionId": COL2,
        "publish": True,
        "text": """# React 18 → 19 업그레이드 체크리스트

## 개요
프론트엔드 프레임워크 React 18에서 19로 업그레이드 시
반드시 확인해야 할 항목을 정리합니다.

## Breaking Changes

### 1. 렌더링 동작 변경
- `ReactDOM.render()` **완전 제거** → `createRoot().render()` 사용
- Strict Mode에서 이중 렌더링 동작 변경

### 2. Concurrent Features 기본 활성화
- `useTransition`, `useDeferredValue` 기본 동작 변경
- Suspense 경계 처리 방식 업데이트

### 3. 의존성 패키지 호환성
- `@types/react` 19 버전으로 업그레이드 필요
- 일부 서드파티 UI 라이브러리 미호환 → 사전 확인 필수

## 체크리스트
- [ ] `package.json` react/react-dom 버전 업
- [ ] `ReactDOM.render` 사용처 전수 검색 및 교체
- [ ] 컴포넌트 단위 테스트 통과 확인
- [ ] E2E 테스트 실행

## 참고
프레임워크팀 주관 마이그레이션 워크숍 예정 (일정 공지 예정)""",
    },
    {
        "title": "사내 표준 프레임워크 선정 기준 및 결정 사항 (2026 H2)",
        "collectionId": COL2,
        "publish": True,
        "text": """# 사내 표준 프레임워크 선정 기준 및 결정 사항 (2026 H2)

## 목적
2026년 하반기 신규 프로젝트에 적용할 사내 표준 프레임워크를 확정합니다.

## 선정 기준
| 기준 | 가중치 |
|------|--------|
| 커뮤니티 활성도 / 장기 지원 여부 | 30% |
| 현재 팀 기술 스택 호환성 | 25% |
| 학습 비용 | 20% |
| 성능 벤치마크 | 15% |
| 라이선스 | 10% |

## 결정 사항

### 백엔드
- **채택**: Spring Boot 3.x (Java 21)
- **근거**: 팀 역량, 장기 LTS 지원, 풍부한 에코시스템

### 프론트엔드
- **채택**: React 18 (신규 프로젝트는 19 적용)
- **근거**: 팀 숙련도, 생태계 성숙도

### API 게이트웨이
- **채택**: Kong Gateway (OSS)
- **근거**: 플러그인 생태계, K8s 통합 지원

## 다음 단계
1. 프레임워크팀 → 스타터 프로젝트 템플릿 제공 (2026-08-01)
2. 전파 교육 세션 (2026-08-15)

## 문의
프레임워크 담당자에게 문의""",
    },
    {
        "title": "7월 팀 회식 안내 및 장소 투표",
        "collectionId": COL1,
        "publish": True,
        "text": """# 7월 팀 회식 안내 및 장소 투표

## 일정
- 날짜: 2026년 7월 25일 (금) 오후 6시
- 참석 대상: 전 직원

## 후보 장소
1. 강남 고기집 A (삼겹살/목살)
2. 마포 해산물 B (회/조개구이)
3. 홍대 한식 뷔페 C

## 투표 방법
아래 링크에서 희망 장소를 선택해 주세요.
투표 마감: **2026-07-18 (금)**

> 참석 불가 시 팀장에게 미리 알려주시기 바랍니다.""",
    },
    {
        "title": "2026년 하계 휴가 신청 방법 변경 안내",
        "collectionId": COL1,
        "publish": True,
        "text": """# 2026년 하계 휴가 신청 방법 변경 안내

## 변경 사항
올해부터 하계 휴가 신청은 기존 이메일 방식에서 **HR 시스템(사내 포털)**으로 변경됩니다.

## 신청 방법
1. 사내 포털 접속 → 인사관리 → 휴가신청
2. 휴가 유형 선택: `하계 휴가`
3. 날짜 선택 및 대리 결재자 지정
4. 팀장 승인 후 자동 처리

## 신청 기간
- 1차 신청: 2026-07-14 ~ 2026-07-21
- 사용 가능 기간: 2026-07-28 ~ 2026-08-29

## 유의사항
- 동일 팀 내 동시 휴가 인원은 최대 2명
- 1주일 이상 연속 휴가 시 2주 전 사전 승인 필요

## 문의
인사팀 내선 1234""",
    },
]

print("\n=== 문서 생성 ===")
for doc in DOCS:
    r = api("documents.create", doc)
    d = r["data"]
    print(f"  ✓ [{d['collectionId'][:8]}...] {d['title']}")

print("\n완료!")
