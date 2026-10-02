# 파일 쓰기 (`FILE_WRITE`)

## 상태: ✅ 완료 (2026-10-02) — **Windows 전용**

## 목적
앞 노드에서 받은 내용(웹훅 요청, 구성된 문자열 등)을 **원하는 폴더에 원하는 확장자 파일로 저장**한다.
예: 웹훅으로 받은 `a,b,c`를 `${a}==${b}==${c}`로 조합 → `결과_20261002_213000.txt`로 저장.

## 파라미터

| name | label | type | 기본값 | 설명 |
|------|-------|------|--------|------|
| `name` | 노드 이름 | text | `""` | 캔버스·패널·로그 표시명 |
| `folder` | 저장 폴더 | folder | `%USERPROFILE%\Desktop` | 전체 경로. `%환경변수%`, `~` 사용 가능, `${...}` 가능. **📁 찾아보기** = Windows 폴더 선택 창 |
| `fileName` | 파일명 (확장자 제외) | text | `output_${datetime}` | `${date}` `${time}` `${datetime}` `${timestamp}`, `${input.xxx}` 등 사용 가능 |
| `extension` | 확장자 | select | `txt` | txt · md · json · csv · log · html · xml · yaml · **직접 입력** |
| `customExtension` | 확장자 직접 입력 | text | `""` | 위에서 "직접 입력"일 때 (예: `sql`, 앞의 `.`은 자동 제거, 영문·숫자·`_-` 20자 이내) |
| `content` | 내용 | textarea | `${input}` | 저장할 내용. 앞 노드 출력이 기본 |
| `ifExists` | 같은 이름 파일이 있으면 | select | 덮어쓰기 | 덮어쓰기 · 뒤에 이어쓰기 · 새 이름으로 저장 (`이름 (1).txt`) · 오류로 중단 |
| `encoding` | 인코딩 | select | UTF-8 | UTF-8 · UTF-8 (BOM 포함, 엑셀용 CSV) · EUC-KR (MS949) |
| `prettyJson` | json일 때 보기 좋게 정렬 | select | 예 | 확장자가 json이고 내용이 JSON이면 들여쓰기 정렬 (JSON이 아니면 그대로) |
| `outputVar` | 출력 변수명 | text | `file` | |

### 날짜/시간 변수 (폴더·파일명·내용에서 사용)
| 변수 | 예 |
|------|----|
| `${date}` | `2026-10-02` |
| `${time}` | `21-30-00` (파일명에 쓸 수 있게 `:` 대신 `-`) |
| `${datetime}` | `20261002_213000` |
| `${timestamp}` | `1790000000000` (ms) |

참조 순서: 날짜 변수 → `${input}` (앞 노드 출력) → 전역 변수(`${webhook...}` 등).

## 동작
1. Windows가 아니면 오류 ("파일 쓰기 모듈은 Windows 전용입니다")
2. 폴더: `%ENV%`·`~` 펼침 → 전체 경로인지 확인 → **보호 폴더 차단** (`C:\Windows`, `C:\Program Files`, `C:\Program Files (x86)`, `C:\ProgramData` 및 하위) → 없으면 생성
3. 파일명 정리: 금지 문자 `< > : " / \ | ? *` → `_`, 끝의 공백·점 제거, 예약어(`CON`, `PRN`, `AUX`, `NUL`, `COM1~9`, `LPT1~9`) 앞에 `_`, 200자 제한, 비면 `output`
4. 같은 이름 파일 처리 후 저장 (BOM 선택 시 `EF BB BF` 추가, 이어쓰기는 BOM 없이 덧붙임)

## 출력
```json
{
  "path": "C:\\Users\\me\\Desktop\\workflow-studio\\결과_20261002_213000.txt",
  "folder": "C:\\Users\\me\\Desktop\\workflow-studio",
  "fileName": "결과_20261002_213000.txt",
  "extension": "txt",
  "mode": "created",          // created | overwritten | appended | renamed
  "encoding": "UTF-8",
  "bytes": 23
}
```
다음 노드에서 `${file.path}`, `${input.fileName}` 등으로 참조.

## 오류 처리
- 폴더가 비었거나 상대 경로 / 없는 환경 변수 / 보호 폴더 / 같은 이름의 *파일*이 폴더 자리에 있음
- 확장자 "직접 입력"인데 비어 있음, 허용되지 않는 문자
- 같은 이름 파일이 있고 "오류로 중단" 선택
- 앞 노드 출력이 없는데 내용이 `${input}`이면 `${input}` 글자가 그대로 저장됨 (수동 실행 시 주의)

## 폴더 선택 창 (📁 찾아보기)
- `POST /api/dialog/folder {initial}` (공용 `FileDialogController`, 2026-10-02 AI 에이전트 작업 때 파일 선택과 함께 일반화)
  → 서버 PC(=사용자 PC)에 Swing `JFileChooser`(Windows 모양)를 띄움 → `{path}` / `{cancelled:true}` / `{error}`
- 브라우저 뒤에 숨지 않도록 항상 위에 있는 보이지 않는 부모 창 사용. 한 번에 하나만 열림(동기화)
- 입력칸의 현재 값(환경 변수 포함)을 시작 폴더로 사용, 해석 안 되면 홈 폴더
- ⚠️ 자동화 테스트로는 확인 못 함 (네이티브 창) — 직접 눌러서 확인 필요

## 구현 파일 (`node/modules/filewrite/`)
| 파일 | 역할 |
|------|------|
| `FileWriteModule.java` | 메타데이터, 실행, 경로·파일명·확장자 규칙 헬퍼 |
| `controller/FileDialogController.java` | 폴더/파일 선택 창 API (공용) |
| 프론트 `ConfigPanel.tsx` `FolderField` | `folder` 파라미터 타입 (입력 + 찾아보기) |
| `src/test/.../FileWriteModuleTest.java` | 파일명/확장자/폴더 규칙, 웹훅→파일 쓰기(json 정렬), 덮어쓰기·이어쓰기·새 이름·오류·BOM·MS949 (Windows에서만 실행) |

공통 변경: `NodeContext.resolve(text, extra)` — 모듈 전용 변수(여기선 날짜 변수)를 가장 먼저 찾는 오버로드 추가.

## 테스트 방법
데모 파이프라인 **"파일 쓰기 데모"** (웹훅 `filedemo` → 파일 쓰기, 바탕화면 `workflow-studio` 폴더, txt):
1. 에디터에서 **▶ 수신 대기**
2. 웹훅 노드 **🚀 HTTP로 테스트** → 바디 `{"a":"1","b":"2","c":"3"}` 보내기
3. 바탕화면 `workflow-studio\결과_날짜_시간.txt` 내용이 `1==2==3`인지 확인, 파일 쓰기 노드 결과 탭에서 경로 확인
