/**
 * 요청 헤더 칸에 붙여넣은 내용을 "이름: 값" 줄로 바꾼다.
 * 브라우저 개발자 도구에서 복사하는 여러 형태를 알아본다:
 *   - cURL 로 복사 (bash)   : curl '…' -H 'Cookie: a=1; b=2' …   /  -b 'a=1; b=2'
 *   - cURL 로 복사 (cmd)    : curl ^"…^" -H ^"Cookie: a=1^" …   (윈도우 ^ 이스케이프)
 *   - 요청 헤더 복사          : 줄마다 "Name: value"
 *   - 쿠키 값만              : a=1; b=2
 * 로그인에 필요한 Cookie · Authorization 만 남기고 나머지(User-Agent 등)는 버린다.
 */

export interface PasteResult {
  /** 헤더 칸에 넣을 줄들 */
  lines: string[]
  /** 어떤 형태로 알아봤는지 (화면 안내용) */
  kind: 'curl' | 'headers' | 'cookie'
  /** 쿠키 개수 (이름=값 쌍) */
  cookieCount: number
}

const KEEP = ['cookie', 'authorization']

/** 윈도우 cmd 형식(^" ^% 등)을 bash 형식 비슷하게 */
function unCmd(s: string) {
  return s.replace(/\^\s*\r?\n/g, ' ').replace(/\^(.)/g, '$1')
}

/** curl 명령에서 -H/--header, -b/--cookie 인자 꺼내기 */
function parseCurl(text: string): { name: string; value: string }[] {
  const src = /\^"/.test(text) ? unCmd(text) : text.replace(/\\\r?\n/g, ' ')
  const out: { name: string; value: string }[] = []
  // 인자: '...', $'...', "...", 또는 공백 없는 단어
  const re = /(-H|--header|-b|--cookie)\s+(\$?'((?:[^'\\]|\\.)*)'|"((?:[^"\\]|\\.)*)"|(\S+))/g
  let m: RegExpExecArray | null
  while ((m = re.exec(src))) {
    const raw = (m[3] ?? m[4] ?? m[5] ?? '').replace(/\\'/g, "'").replace(/\\"/g, '"')
    if (m[1] === '-b' || m[1] === '--cookie') out.push({ name: 'Cookie', value: raw.trim() })
    else {
      const i = raw.indexOf(':')
      if (i > 0) out.push({ name: raw.slice(0, i).trim(), value: raw.slice(i + 1).trim() })
    }
  }
  return out
}

function countCookies(v: string) {
  return v.split(';').filter((p) => p.includes('=')).length
}

/** 붙여넣은 글을 알아보면 결과, 평범한 입력이면 null (그대로 붙여넣기) */
export function convertPasted(text: string): PasteResult | null {
  const t = text.trim()
  if (!t) return null

  let pairs: { name: string; value: string }[] = []
  let kind: PasteResult['kind']
  if (/^curl\s/i.test(t)) {
    pairs = parseCurl(t)
    kind = 'curl'
  } else if (/^[A-Za-z][\w-]*:\s?\S/m.test(t) && t.split(/\r?\n/).length > 1) {
    // 여러 줄 "Name: value" (요청 헤더 복사). ":authority:" 같은 HTTP/2 의사 헤더는 무시
    for (const line of t.split(/\r?\n/)) {
      const m = line.match(/^\s*([A-Za-z][\w-]*)\s*:\s*(.*)$/)
      if (m) pairs.push({ name: m[1], value: m[2].trim() })
    }
    kind = 'headers'
  } else if (/^cookie\s*:/i.test(t)) {
    return null // 이미 올바른 형식
  } else if (/^[^\s=;:]+=[^;]*(;\s*[^\s=;:]+=[^;]*)*;?$/.test(t.replace(/\r?\n/g, ''))) {
    pairs = [{ name: 'Cookie', value: t.replace(/\r?\n/g, '') }]
    kind = 'cookie'
  } else {
    return null
  }

  const kept = pairs.filter((p) => KEEP.includes(p.name.toLowerCase()) && p.value)
  if (!kept.length) return { lines: [], kind, cookieCount: 0 }
  // 쿠키가 여러 번 나오면 합침
  const cookies = kept.filter((p) => p.name.toLowerCase() === 'cookie').map((p) => p.value.replace(/;\s*$/, ''))
  const lines: string[] = []
  if (cookies.length) lines.push('Cookie: ' + cookies.join('; '))
  for (const p of kept) if (p.name.toLowerCase() === 'authorization') lines.push('Authorization: ' + p.value)
  return { lines, kind, cookieCount: cookies.reduce((n, c) => n + countCookies(c), 0) }
}

/** 기존 헤더 줄에 합치기 — 같은 이름은 새 값으로 교체 */
export function mergeHeaders(existing: string, lines: string[]) {
  const names = new Set(lines.map((l) => l.split(':')[0].trim().toLowerCase()))
  const keep = existing
    .split(/\r?\n/)
    .filter((l) => l.trim() && !names.has(l.split(':')[0].trim().toLowerCase()))
  return [...keep, ...lines].join('\n')
}

/** 화면 표시용 요약 — 값은 보여 주지 않음 */
export function headerSummary(text: string) {
  const lines = text.split(/\r?\n/).filter((l) => l.trim())
  const cookie = lines.find((l) => /^\s*cookie\s*:/i.test(l))
  const n = cookie ? countCookies(cookie.slice(cookie.indexOf(':') + 1)) : 0
  if (!lines.length) return '비어 있음'
  return (cookie ? `🍪 쿠키 ${n}개` : '쿠키 없음') + (lines.length > (cookie ? 1 : 0) ? ` · 그 외 헤더 ${lines.length - (cookie ? 1 : 0)}줄` : '')
}

export function openCookieGuide() {
  const w = 1040
  const h = Math.min(window.screen.availHeight - 40, 980)
  const left = Math.max(0, window.screen.availWidth - w - 20)
  window.open('/guide/cookie', 'workflow-cookie-guide', `width=${w},height=${h},left=${left},top=20,resizable=yes,scrollbars=yes`)
}
