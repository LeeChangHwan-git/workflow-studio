# 파일 가져오기 (`FILE_IMPORT`)

## 상태: ✅ 완료 (2026-10-07) · 폴더 합치기 + Word→마크다운 (2026-10-08)

## 목적
지정한 파일을 읽어서 **다음 노드(주로 AI 에이전트)로 넘긴다**. 예: 보고서 파일을 읽어 AI가 요약·정리하게 하고 결과를 파일로 저장.
읽기만 하므로 `FILE_WRITE`와 달리 OS 제약이 없다 — 쓰기 노드는 시스템 폴더 보호·Windows 파일명 규칙이 필요해서 Windows 전용이지만,
읽기는 그런 안전장치가 필요 없어서 Mac/Linux에서도 그대로 동작한다.

## 파라미터

| name | label | type | 기본값 | 설명 |
|------|-------|------|--------|------|
| `name` | 노드 이름 | text | `""` | 캔버스·패널·로그 표시명 |
| `mode` | 가져오기 | select | 파일 하나 | 파일 하나 · 폴더의 여러 파일 (합치기) |
| `filePath` | 가져올 파일 | file | `""` | 전체 경로. `%ENV%`, `~` 사용 가능, `${...}` 가능. **📁 찾아보기** 창에서 기본 필터가 txt/md라 다른 확장자는 "모든 파일"로 바꿔 선택 |
| `folder` | 폴더 | folder | `""` | (폴더 모드) `%ENV%`·`~`·`${...}` 가능. 하위 폴더는 보지 않음 |
| `pattern` | 파일 이름 패턴 | text | `*.docx;*.doc` | (폴더 모드) glob, `;` 로 여러 개, 대소문자 무시 (예: `회의록_*.docx`) |
| `sort` | 합치는 순서 | select | 이름순 | 이름순(한글 정렬) · 수정 시각 오래된 것부터 · 최신 것부터 |
| `recentDays` | 최근 N일 | number | 0 | (폴더 모드) N일 안에 수정된 파일만, 0 = 전부 |
| `maxFiles` | 최대 파일 수 | number | 50 | (폴더 모드) 1~500, 정렬 후 앞에서부터 |
| `join` | 합치는 방식 | select | ## 파일 이름 제목 | 파일마다 `## 파일이름` + 문서 안 제목을 두 단계 내림(`#`→`###`) · 내용만 `---` 구분선으로 |
| `encoding` | 텍스트 인코딩 | select | UTF-8 | UTF-8 · EUC-KR (MS949). pdf/docx/xlsx/pptx/html 파일에는 적용 안 됨 (각 포맷 자체 인코딩 처리) |
| `maxChars` | AI에 넘길 최대 글자수 | number | `20000` | 넘으면 앞부분만 자르고 `truncated:true`. `0`이면 무제한 (AI 컨텍스트 한도 보호용) |
| `outputVar` | 출력 변수명 | text | `file` | |

## 동작
1. 경로 펼침: `%ENV%` 치환 → `~`/`~/`/`~\` → 홈 디렉터리 → `Paths.get(...).normalize()` (현재 OS 구분자 그대로, Windows `C:\...`와 Unix `/...` 둘 다)
2. 존재·파일 여부 확인 (폴더면 오류), 크기 확인 (25MB 초과면 오류 — 메모리 보호)
3. 확장자별 텍스트 추출 (`FileTextExtractor`):

   | 확장자 | 방법 |
   |--------|------|
   | `pdf` | Apache PDFBox `PDFTextStripper` (암호 걸린 pdf는 오류) |
   | `docx` | **마크다운으로 변환** (`DocxMarkdown`) — 제목 스타일(Title/heading 1~6, 한글 Word 의 id "1"·"2"도) → `#`, 글머리·번호 목록 → `-`/`1.` (수준만큼 들여쓰기), **표 → 마크다운 표**, 굵게 → `**`, 하이퍼링크 → `[글자](주소)` |
   | `doc` | 옛날 Word(97~2003) — POI HWPF `WordExtractor`, 서식 없이 글자만 |
   | `xlsx`, `xlsm` | Apache POI — 시트별 `## 시트명` + 셀을 탭으로 구분한 줄 |
   | `pptx` | Apache POI — 슬라이드별 `## 슬라이드 N` + 텍스트 상자 내용 |
   | `html`, `htm` | jsoup `.text()` — 태그·스크립트 제거한 본문만 |
   | 그 외 전부 | 그대로 텍스트 — UTF-8 BOM 자동 제거, `encoding` 파라미터로 EUC-KR도 가능. 앞 8000바이트에 NUL 바이트가 있으면(git과 같은 바이너리 판별법) "텍스트로 읽을 수 없는 파일 형식" 오류 |
4. `maxChars` 넘으면 앞부분만 잘라서 `content`에 담고 `truncated:true`

## 폴더 모드 (여러 파일 합치기)
- 폴더에서 패턴에 맞는 파일만 (Word 임시 파일 `~$…` 제외) → 정렬 → 최근 N일 → 최대 개수 → 하나씩 추출해 합침
- 합친 글 기준으로 `maxChars` 적용 (0 = 무제한), 파일 하나라도 못 읽으면 그 파일 이름과 이유로 오류
- 출력: `{folder, pattern, count, files:[{fileName, path, modified, charCount}], items:[{fileName, title, content}], charCount, truncated, content}`
  → 합친 글 `${docs.content}`, 파일 수 `${docs.count}`, 파일별 `${docs.items[0].content}`

### 예시 파이프라인 "회의록 합쳐서 아웃라인 등록" (▶ 실행)
파일 가져오기(폴더 `바탕화면workflow-studio회의록`, `*.docx;*.doc`, 출력 변수 `docs`)
→ 파일 쓰기(`회의록모음_${date}.md`, `# 회의록 모음 (${date})` + `${docs.content}`)
→ 아웃라인 등록(사내 게시판, 제목 `회의록 모음 (${date})`, 내용 `${docs.content}`)
- 주의: 파일 쓰기의 기본 출력 변수도 `file` 이라 겹치지 않게 가져오기 쪽을 `docs` 로
- 요약이 필요하면 가져오기와 파일 쓰기 사이에 AI 에이전트(`${docs.content}`)를 넣으면 됨
- 실측: 샘플 회의록 3개(표·목록·굵게) → md 2KB 저장 → 아웃라인에 표·목록이 그대로 렌더링된 글 게시

### 예시 파이프라인 2 "이번 주 회의록 AI 요약 → 아웃라인" (▶ 실행)
파일 가져오기(최근 7일, maxChars 60000) → AI 에이전트(Gemini + 지시문: 핵심 결정·할 일 표(기한 순)·리스크, 지어내지 말 것)
→ 파일 쓰기(`주간회의요약_${date}.md`) → 아웃라인 등록(제목 비움 → AI가 쓴 `# …` 첫 줄)
- 실측: 회의록 5건 → 결정 5개·할 일 8개 표·리스크 2개 요약, md 저장 + 아웃라인 게시. 타이머(매주 금 17:00 등)를 앞에 붙이면 자동화

### 테스트용 샘플 (바탕화면 `workflow-studio\회의록`)
Word 회의록 7개(2026-09-22 ~ 오늘, 하위 글머리·표·링크·굵게 포함, 맑은 고딕) + 합치기에서 빠져야 하는 `회의 참고자료.txt`·`~$…docx`(Word 임시 파일)
→ 전부 7개 / 최근 7일 5개 / 최신 순 최대 2개 확인

## 출력
```json
{
  "path": "C:\\Users\\me\\Desktop\\report.docx",
  "fileName": "report.docx",
  "extension": "docx",
  "bytes": 18432,
  "charCount": 5210,
  "truncated": false,
  "content": "문서 전체 텍스트…"
}
```
다음 노드에서 `${file.content}`로 본문을, `${file.fileName}` 등으로 메타데이터를 참조. AI 에이전트 노드의 "질문 / 요청"에
`${file.content}`를 그대로 넣으면 된다 (`${input}`만 두면 메타데이터가 섞인 JSON 전체가 그대로 프롬프트로 들어가므로 비추천).

## 오류 처리
- 경로가 비었거나 `%ENV%`를 못 찾음, 상대 경로(전체 경로가 아님)
- 파일이 없음 / 폴더를 선택함 / 파일이 25MB 초과
- 암호 걸린 pdf
- 알 수 없는 확장자인데 내용이 바이너리로 보임(NUL 바이트)

## 의존성 / 설정
- `org.apache.pdfbox:pdfbox:2.0.31`, `org.apache.poi:poi-ooxml:5.2.5`, `org.apache.poi:poi-scratchpad:5.2.5`(.doc) (build.gradle)
- OS 제약 없음 (유일하게 OS 무관한 파일 모듈 — `FILE_WRITE`/`FILE_WRITE`가 쓰는 📁 찾아보기 창은 공용이라 그대로 사용)

## 구현 파일 (`node/modules/fileimport/`)
| 파일 | 역할 |
|------|------|
| `FileImportModule.java` | 메타데이터, 실행, 경로 펼침(`resolvePath`) — `FILE_WRITE`와 달리 OS 무관 |
| `FileTextExtractor.java` | 확장자별 텍스트 추출 (package-private) |
| `DocxMarkdown.java` | Word → 마크다운 변환, 합칠 때 제목 단계 내리기(`shiftHeadings`) |
| `src/test/.../DocxMarkdownTest.java` | 제목 스타일·번호 정의·표·굵게·링크를 넣은 실제 docx 로 변환 확인 (2개) |
| `src/test/.../FileTextExtractorTest.java` | 추출 로직 단위 테스트 (txt/BOM/EUC-KR/html/pdf/docx/xlsx/pptx/바이너리 거부, 9개 — 실제로 각 포맷 파일을 만들어서 검증, OS 무관) |
| `src/test/.../FileImportModuleTest.java` | 노드 실행 통합 테스트 (정상/자르기/에러/`~`·`%ENV%` 치환/docx 전체 경로 + 폴더 모드: Word 여러 개 합치기·임시 파일 제외·대문자 확장자·정렬·최근 N일·최대 개수·구분선·오류, 12개 — OS 무관) |

## 테스트 방법
예시 파이프라인 **"예시: 파일 가져오기 → AI 요약 → 저장"** (파일 가져오기 → AI 에이전트 → 파일 쓰기):
1. `문서 가져오기` 노드의 `filePath`를 실제 파일 경로로 변경
2. ⚙ 설정 → AI 도우미에 Gemini API 키 입력 (비어 있으면 "Gemini API 키가 없습니다" 오류)
3. ▶ 실행 → 결과 탭에서 `문서 가져오기`의 `content`, `문서 요약`의 답변, 바탕화면 `요약\` 폴더의 저장된 md 확인

또는 REST로 직접 확인: `POST /api/pipelines` 로 `FILE_IMPORT` 노드 하나짜리 파이프라인을 만들고 `POST /api/pipelines/{id}/run` →
`GET /api/executions/{execId}` 로 결과 확인 (트리거 없이도 수동 실행은 전체 노드를 순서대로 실행함).
