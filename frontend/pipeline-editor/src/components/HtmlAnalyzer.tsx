import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from '../api'
import { HeadersField } from './HeadersField'

/**
 * 🔍 HTML 분석기 — 페이지를 보면서 원하는 요소를 클릭하면 "같은 종류 전부"를 고르는 CSS 선택자를 만들어 준다.
 *
 * - 서버가 페이지를 가져와 스크립트를 뺀 HTML 을 돌려주면, 스크립트를 막은 iframe(sandbox) 에 그대로 그린다.
 * - 첫 클릭: 클릭한 요소를 감싼 "반복되는 줄"(게시글 한 줄 = tr, li …)을 찾아 행 선택자로, 그 안의 경로를 항목 선택자로.
 * - 다음 클릭: 행 안에서의 경로만 계산 → 작성자·날짜 같은 항목을 차례로 추가.
 * - 미리보기는 브라우저에서 즉시, "서버로 확인"은 실제 실행과 같은 jsoup 기준.
 */

export interface PickerField {
  name: string
  selector: string
  attr: string
}

export interface AnalyzerValue {
  url: string
  headers: string
  rowSelector: string
  fields: PickerField[]
}

interface Props {
  initial: AnalyzerValue
  onApply: (v: AnalyzerValue) => void
  onClose: () => void
}

const FIELD_COLORS = ['#4E5BF2', '#16A34A', '#DB2777', '#0891B2', '#CA8A04', '#7C3AED', '#DC2626', '#0D9488']
const DEFAULT_NAMES = ['제목', '작성자', '날짜', '조회수', '분류']
const ATTRS: [string, string][] = [
  ['text', '글자'],
  ['href', '링크 주소(href)'],
  ['src', '이미지 주소(src)'],
  ['html', 'HTML'],
  ['title', 'title 속성'],
]

// ── "이름: 선택자 @속성" 줄 ↔ 항목 ──
export function parseFieldLines(text: string): PickerField[] {
  const out: PickerField[] = []
  for (const raw of (text || '').split(/\r?\n/)) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const m = line.match(/^\s*([^:]+?)\s*:\s*(.*?)\s*(?:@([\w:.-]+))?\s*$/)
    if (m) out.push({ name: m[1].trim(), selector: m[2].trim(), attr: m[3] || 'text' })
  }
  return out
}
export function fieldLines(fields: PickerField[]): string {
  return fields
    .filter((f) => f.name.trim())
    .map((f) => `${f.name.trim()}: ${f.selector}${f.attr && f.attr !== 'text' ? ` @${f.attr}` : ''}`.replace(/:\s+@/, ': @'))
    .join('\n')
}

// ── 선택자 만들기 ──
const BAD_CLASS = /^(on|off|active|selected|select|hover|focus|odd|even|first|last|current|over|show|hide|open|new|ng-.*|is-.*|has-.*)$/i

function esc(win: Window, s: string) {
  const css = (win as unknown as { CSS?: { escape: (v: string) => string } }).CSS ?? window.CSS
  return css.escape(s)
}
function classes(el: Element) {
  return [...el.classList]
    .filter((c) => !c.startsWith('__ws_') && /^[A-Za-z_][\w-]*$/.test(c) && !BAD_CLASS.test(c) && !/\d{4,}/.test(c))
    .slice(0, 2)
}
function goodId(el: Element) {
  return !!el.id && /^[A-Za-z][\w-]*$/.test(el.id) && !/\d{3,}/.test(el.id)
}
function part(win: Window, el: Element, withClass = true) {
  const tag = el.tagName.toLowerCase()
  return withClass ? tag + classes(el).map((c) => '.' + esc(win, c)).join('') : tag
}
/** 같은 부모 안에 같은 모양 형제가 여럿이면 :nth-of-type 으로 위치 지정 */
function partNth(win: Window, el: Element) {
  const p = part(win, el)
  const parent = el.parentElement
  if (!parent) return p
  const same = [...parent.children].filter((c) => c.matches(p))
  if (same.length <= 1) return p
  const idx = [...parent.children].filter((c) => c.tagName === el.tagName).indexOf(el) + 1
  return `${p}:nth-of-type(${idx})`
}
/** row(제외) 아래에서 el 까지의 경로 */
function relPath(win: Window, row: Element, el: Element) {
  const parts: string[] = []
  for (let a: Element | null = el; a && a !== row; a = a.parentElement) parts.unshift(partNth(win, a))
  return parts.join(' > ')
}
/** id 가 있는 조상(또는 body)까지의 경로 */
function absPath(win: Window, el: Element) {
  const parts: string[] = []
  for (let a: Element | null = el; a && a.tagName !== 'HTML'; a = a.parentElement) {
    if (goodId(a)) {
      parts.unshift(`${a.tagName.toLowerCase()}#${esc(win, a.id)}`)
      break
    }
    if (a.tagName === 'BODY') {
      parts.unshift('body')
      break
    }
    parts.unshift(partNth(win, a))
  }
  return parts.join(' > ')
}
/** 태그만으로 된 경로 (반복 판단용) */
function tagPath(row: Element, el: Element) {
  const parts: string[] = []
  for (let a: Element | null = el; a && a !== row; a = a.parentElement) parts.unshift(a.tagName.toLowerCase())
  return parts.join(' > ')
}

/** 클릭한 요소를 감싼 "반복되는 줄" — 같은 모양(같은 태그 + 같은 안쪽 경로)의 형제가 가장 많은 조상 */
function findRow(el: Element): { row: Element; similar: number } | null {
  let best: Element | null = null
  let bestScore = 1
  let depth = 0
  for (let a: Element | null = el; a && a.parentElement && a.tagName !== 'BODY' && depth < 14; a = a.parentElement, depth++) {
    const rel = tagPath(a, el)
    const sibs = [...a.parentElement.children].filter((c) => c.tagName === a!.tagName)
    let score = 0
    for (const s of sibs) {
      try {
        if (!rel || s.querySelector(':scope > ' + rel)) score++
      } catch {
        /* 무시 */
      }
    }
    if (score > bestScore) {
      best = a
      bestScore = score
    }
  }
  return best ? { row: best, similar: bestScore } : null
}

function rowSelectorFor(win: Window, row: Element, similar: number) {
  const parentPath = row.parentElement ? absPath(win, row.parentElement) : 'body'
  const withClass = `${parentPath} > ${part(win, row)}`
  const tagOnly = `${parentPath} > ${part(win, row, false)}`
  const doc = row.ownerDocument
  const n = (s: string) => {
    try {
      return doc.querySelectorAll(s).length
    } catch {
      return 0
    }
  }
  // 클래스까지 넣으면 공지 줄 등 일부만 잡히는 경우 → 태그만으로
  return n(withClass) >= similar ? withClass : tagOnly
}

function valueOf(el: Element, attr: string) {
  switch (attr) {
    case 'text':
      return (el.textContent || '').replace(/\s+/g, ' ').trim()
    case 'html':
      return el.innerHTML.trim()
    case 'href':
      return (el as HTMLAnchorElement).href || el.getAttribute('href') || ''
    case 'src':
      return (el as HTMLImageElement).src || el.getAttribute('src') || ''
    default:
      return (el.getAttribute(attr) || '').trim()
  }
}

/** 글자가 없는 요소에 알맞은 "가져올 내용" — 그림이면 주소, 설명 속성이 있으면 그 값, 링크면 주소, 아니면 HTML */
function autoAttr(el: Element): { attr: string; label: string } {
  if (el.getAttribute('src')) return { attr: 'src', label: '이미지 주소(src)' }
  for (const a of ['alt', 'aria-label', 'title']) if (el.getAttribute(a)?.trim()) return { attr: a, label: `${a} 속성` }
  if (el.getAttribute('href')) return { attr: 'href', label: '링크 주소(href)' }
  return { attr: 'html', label: 'HTML' }
}

function safeAll(root: ParentNode, sel: string): Element[] {
  try {
    return [...root.querySelectorAll(sel)]
  } catch {
    return []
  }
}
function safeOne(root: Element, sel: string): Element | null {
  try {
    return root.querySelector(sel)
  } catch {
    return null
  }
}

/** 브라우저에서 바로 추출 (서버 jsoup 과 같은 규칙) */
function extract(doc: Document, rowSelector: string, fields: PickerField[], limit = 30) {
  const rows: Record<string, string>[] = []
  if (!fields.length) return { rows, total: 0 }
  if (rowSelector.trim()) {
    let total = 0
    for (const row of safeAll(doc, rowSelector)) {
      const r: Record<string, string> = {}
      let any = false
      for (const f of fields) {
        const el = f.selector ? safeOne(row, f.selector) : row
        const v = el ? valueOf(el, f.attr) : ''
        any ||= !!v
        r[f.name] = v
      }
      if (any) {
        total++
        if (rows.length < limit) rows.push(r)
      }
    }
    return { rows, total }
  }
  const found = fields.map((f) => (f.selector ? safeAll(doc, f.selector) : []))
  const max = Math.max(0, ...found.map((l) => l.length))
  for (let i = 0; i < max && rows.length < limit; i++) {
    const r: Record<string, string> = {}
    fields.forEach((f, j) => (r[f.name] = found[j][i] ? valueOf(found[j][i], f.attr) : ''))
    rows.push(r)
  }
  return { rows, total: max }
}

type PageState = { html: string; finalUrl: string; title: string; charset: string; frames: string[]; bytes: number }

export function HtmlAnalyzer({ initial, onApply, onClose }: Props) {
  const [url, setUrl] = useState(initial.url && initial.url !== 'https://' ? initial.url : '')
  const [headers, setHeaders] = useState(initial.headers)
  const [showHeaders, setShowHeaders] = useState(!!initial.headers.trim())
  const [page, setPage] = useState<PageState | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [rowSelector, setRowSelector] = useState(initial.rowSelector)
  const [fields, setFields] = useState<PickerField[]>(initial.fields.length ? initial.fields : [{ name: '제목', selector: '', attr: 'text' }])
  const [active, setActive] = useState<number | null>(initial.fields.length && initial.fields[0].selector ? null : 0)
  const [message, setMessage] = useState<string | null>(null)
  const [server, setServer] = useState<{ ok: boolean; text: string } | null>(null)
  const [frameReady, setFrameReady] = useState(0)
  const iframeRef = useRef<HTMLIFrameElement>(null)
  const stateRef = useRef({ active, rowSelector, fields })
  stateRef.current = { active, rowSelector, fields }

  const load = useCallback(async (target?: string) => {
    const u = (target ?? url).trim()
    if (!u) return
    if (target) setUrl(target)
    setLoading(true)
    setError(null)
    setServer(null)
    try {
      const res = await api.htmlFetch(u, headers)
      if (!res.ok) {
        setError(res.message || '가져올 수 없습니다')
        setPage(null)
      } else {
        setPage({ html: res.html!, finalUrl: res.finalUrl!, title: res.title || '', charset: res.charset || '', frames: res.frames || [], bytes: res.bytes || 0 })
      }
    } catch {
      setError('서버에 요청할 수 없습니다')
    } finally {
      setLoading(false)
    }
  }, [url, headers])

  // 처음 열 때 주소가 있으면 바로 불러오기
  useEffect(() => {
    if (url) load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const doc = (): Document | null => iframeRef.current?.contentDocument ?? null

  /** 요소 클릭 → 선택자 계산 */
  const pick = useCallback((target: Element) => {
    const d = target.ownerDocument
    const win = d.defaultView ?? window
    const { active: idx, rowSelector: curRow, fields: cur } = stateRef.current
    if (idx === null) {
      setMessage('오른쪽에서 채울 항목의 "🎯 고르기"를 먼저 누르세요')
      return
    }
    // 링크 안의 글자를 눌렀으면 링크(a) 자체를 고름 — 제목·링크를 같은 요소에서 뽑기 좋게
    const el: Element = target.closest('a') ?? target
    if (el.tagName === 'BODY' || el.tagName === 'HTML') return

    let row = curRow.trim()
    let selector: string
    let note: string
    if (row) {
      let rowEl: Element | null = null
      try {
        rowEl = el.closest(row)
      } catch {
        rowEl = null
      }
      if (!rowEl) {
        setMessage('⚠️ 고른 요소가 반복 행 바깥에 있습니다 — 같은 줄 안의 요소를 누르거나, 행 선택자를 지우고 다시 고르세요')
        return
      }
      selector = relPath(win, rowEl, el)
      note = '같은 줄 안의 위치로 지정했습니다'
    } else {
      const found = findRow(el)
      if (found) {
        row = rowSelectorFor(win, found.row, found.similar)
        selector = relPath(win, found.row, el)
        setRowSelector(row)
        note = `반복되는 줄 ${found.similar}개를 찾았습니다 → 같은 종류 전부 가져옵니다`
      } else {
        selector = absPath(win, el)
        note = '반복되는 구조가 없어 이 요소 하나만 가져옵니다'
      }
    }
    // 글자가 없는 요소(로고 그림·아이콘 등)를 "글자"로 가져오면 값이 비어 버림 → 알맞은 가져올 내용으로
    let attr = cur[idx]?.attr ?? 'text'
    let attrNote = ''
    if (attr === 'text' && !valueOf(el, 'text')) {
      const auto = autoAttr(el)
      attr = auto.attr
      attrNote = ` · 글자가 없는 요소라 "${auto.label}"(으)로 가져옵니다`
    }
    setFields((fs) => fs.map((f, i) => (i === idx ? { ...f, selector, attr } : f)))
    setMessage('✓ ' + (cur[idx]?.name || '항목') + ': ' + note + attrNote)
    // 다음 빈 항목으로 자동 이동, 없으면 고르기 끝
    const next = cur.findIndex((f, i) => i > idx && !f.selector)
    setActive(next >= 0 ? next : null)
  }, [])

  // iframe 준비: 강조 스타일 넣고 마우스 이벤트 연결 (iframe 안 스크립트는 막혀 있고, 이 화면의 스크립트가 직접 붙임)
  useEffect(() => {
    const d = doc()
    if (!d || !page) return
    const style = d.createElement('style')
    style.textContent = `
      .__ws_hover{outline:2px solid #4FC1FF!important;outline-offset:1px;cursor:crosshair!important}
      .__ws_row{outline:1px dashed rgba(217,119,6,.9)!important;outline-offset:-1px}
      ${FIELD_COLORS.map((c, i) => `.__ws_f${i}{background:${c}33!important;box-shadow:inset 0 0 0 2px ${c}!important}`).join('\n')}
      a,button,input,select,label{cursor:crosshair!important}`
    d.head?.appendChild(style)
    let hovered: Element | null = null
    const over = (e: Event) => {
      const t = e.target as Element
      if (hovered) hovered.classList.remove('__ws_hover')
      hovered = t && t.classList ? (t.closest('a') ?? t) : null
      hovered?.classList.add('__ws_hover')
    }
    const click = (e: Event) => {
      e.preventDefault()
      e.stopPropagation()
      const t = e.target as Element | null
      if (t && t.tagName) pick(t)
    }
    const block = (e: Event) => e.preventDefault()
    d.addEventListener('mouseover', over, true)
    d.addEventListener('click', click, true)
    d.addEventListener('submit', block, true)
    return () => {
      d.removeEventListener('mouseover', over, true)
      d.removeEventListener('click', click, true)
      d.removeEventListener('submit', block, true)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [frameReady, pick])

  // 미리보기 + 페이지 위 강조
  const preview = useMemo(() => {
    const d = doc()
    if (!d || !page) return { rows: [], total: 0, rowCount: 0, stats: [] as { found: number; filled: number }[] }
    d.querySelectorAll('.__ws_row').forEach((e) => e.classList.remove('__ws_row'))
    FIELD_COLORS.forEach((_, i) => d.querySelectorAll('.__ws_f' + i).forEach((e) => e.classList.remove('__ws_f' + i)))
    const rows = rowSelector.trim() ? safeAll(d, rowSelector) : []
    rows.forEach((r) => r.classList.add('__ws_row'))
    fields.forEach((f, i) => {
      if (!f.selector && !rowSelector.trim()) return
      const cls = '__ws_f' + (i % FIELD_COLORS.length)
      if (rowSelector.trim()) rows.forEach((r) => (f.selector ? safeOne(r, f.selector) : r)?.classList.add(cls))
      else safeAll(d, f.selector).forEach((e) => e.classList.add(cls))
    })
    const r = extract(d, rowSelector, fields.filter((f) => f.name.trim()))
    // 항목별: 찾은 요소 수 / 그중 값이 있는 수 (요소는 찾았는데 값이 비면 경고)
    const stats = fields.map((f) => {
      const els = rowSelector.trim()
        ? rows.map((row) => (f.selector ? safeOne(row, f.selector) : row)).filter((e): e is Element => !!e)
        : f.selector ? safeAll(d, f.selector) : []
      return { found: els.length, filled: els.filter((e) => valueOf(e, f.attr)).length }
    })
    return { ...r, rowCount: rows.length, stats }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, frameReady, rowSelector, fields])

  const bodyTextLen = useMemo(() => (doc()?.body?.textContent || '').trim().length, [frameReady]) // eslint-disable-line react-hooks/exhaustive-deps

  const addField = () => {
    const used = new Set(fields.map((f) => f.name))
    const name = DEFAULT_NAMES.find((n) => !used.has(n)) ?? `항목${fields.length + 1}`
    setFields((fs) => [...fs, { name, selector: '', attr: 'text' }])
    setActive(fields.length)
    setMessage(`"${name}"에 넣을 요소를 페이지에서 클릭하세요`)
  }
  const addLinkOf = (i: number) => {
    const f = fields[i]
    const name = fields.some((x) => x.name === '링크') ? `${f.name} 링크` : '링크'
    setFields((fs) => [...fs.slice(0, i + 1), { name, selector: f.selector, attr: 'href' }, ...fs.slice(i + 1)])
  }
  const update = (i: number, patch: Partial<PickerField>) => setFields((fs) => fs.map((f, j) => (j === i ? { ...f, ...patch } : f)))

  const checkServer = async () => {
    setServer({ ok: true, text: '⏳ 서버에서 확인 중…' })
    const res = await api.htmlTest(url, headers, rowSelector, fieldLines(fields)).catch(() => null)
    if (!res) setServer({ ok: false, text: '서버에 요청할 수 없습니다' })
    else if (!res.ok) setServer({ ok: false, text: '❌ ' + res.message })
    else setServer({ ok: true, text: `✅ 실제 실행 기준 ${res.count}개 — 첫 줄: ${Object.values(res.items?.[0] ?? {}).join(' · ').slice(0, 120)}` })
  }

  const valid = fields.some((f) => f.name.trim() && (f.selector || rowSelector.trim()))
  const cols = fields.filter((f) => f.name.trim())

  return (
    <div className="hz-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="hz">
        <div className="hz-head">
          <span className="hz-title">🔍 HTML 분석기</span>
          <input
            className="hz-url"
            value={url}
            placeholder="게시판 목록 페이지 주소 (예: http://intranet/board/list.jsp?bbs=notice)"
            autoComplete="off"
            spellCheck={false}
            onChange={(e) => setUrl(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && load()}
          />
          <button type="button" className="hz-btn primary" onClick={() => load()} disabled={loading || !url.trim()}>
            {loading ? '불러오는 중…' : '불러오기'}
          </button>
          <button type="button" className={`hz-btn${showHeaders ? ' on' : ''}`} onClick={() => setShowHeaders((v) => !v)} title="로그인 쿠키 등">
            🍪 헤더{headers.trim() ? ' ●' : ''}
          </button>
          <button type="button" className="hz-btn ghost" onClick={onClose}>✕</button>
        </div>
        {showHeaders && (
          <div className="hz-headers">
            <HeadersField value={headers} onChange={setHeaders} rows={3} />
          </div>
        )}

        <div className="hz-main">
          <div className="hz-page">
            {error && <div className="hz-error">❌ {error}</div>}
            {page && page.frames.length > 0 && (
              <div className="hz-note">
                🧩 이 페이지는 <b>프레임</b>으로 되어 있습니다. 목록이 있는 프레임을 여세요:
                {page.frames.map((f) => (
                  <button key={f} type="button" className="hz-link" onClick={() => load(f)}>{f}</button>
                ))}
              </div>
            )}
            {page && page.frames.length === 0 && bodyTextLen < 80 && (
              <div className="hz-note">
                ⚠️ 내용이 거의 없습니다 — 로그인이 필요하거나(🍪 헤더에 쿠키), 자바스크립트로 그리는 페이지일 수 있습니다.
                <button type="button" className="hz-link" onClick={() => setShowHeaders(true)}>🍪 쿠키 넣기</button>
              </div>
            )}
            {page ? (
              <iframe
                ref={iframeRef}
                className="hz-frame"
                title="분석할 페이지"
                sandbox="allow-same-origin"
                srcDoc={page.html}
                onLoad={() => setFrameReady((n) => n + 1)}
              />
            ) : (
              !error && (
                <div className="hz-empty">
                  <div className="hz-empty-icon">🔍</div>
                  위에 게시판 <b>목록 페이지</b> 주소를 넣고 불러오세요.
                  <br />
                  페이지가 보이면 가져올 글 제목을 <b>클릭</b>만 하면 됩니다.
                </div>
              )
            )}
          </div>

          <aside className="hz-side">
            {page && (
              <div className="hz-meta" title={page.finalUrl}>
                {page.title || '(제목 없음)'} · {page.charset} · {Math.round(page.bytes / 1024)}KB
              </div>
            )}
            <div className="hz-sec">
              <div className="hz-sec-title">반복 항목(행) <span className="hz-count">{rowSelector.trim() ? `${preview.rowCount}줄` : '없음'}</span></div>
              <div className="hz-row-input">
                <input
                  value={rowSelector}
                  placeholder="첫 항목을 클릭하면 자동으로 채워짐"
                  spellCheck={false}
                  onChange={(e) => setRowSelector(e.target.value)}
                />
                {rowSelector && (
                  <button type="button" className="hz-x" title="행 선택자 지우기 (다시 고르기)" onClick={() => setRowSelector('')}>✕</button>
                )}
              </div>
            </div>

            <div className="hz-sec">
              <div className="hz-sec-title">가져올 항목</div>
              {fields.map((f, i) => (
                <div key={i} className={`hz-field${active === i ? ' picking' : ''}`} style={{ borderLeftColor: FIELD_COLORS[i % FIELD_COLORS.length] }}>
                  <div className="hz-field-top">
                    <input className="hz-name" value={f.name} onChange={(e) => update(i, { name: e.target.value.replace(/:/g, '') })} />
                    <select value={ATTRS.some(([v]) => v === f.attr) ? f.attr : f.attr} onChange={(e) => update(i, { attr: e.target.value })}>
                      {ATTRS.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
                      {!ATTRS.some(([v]) => v === f.attr) && <option value={f.attr}>{f.attr}</option>}
                    </select>
                    <button
                      type="button"
                      className={`hz-pick${active === i ? ' on' : ''}`}
                      onClick={() => {
                        setActive(active === i ? null : i)
                        setMessage(active === i ? null : `"${f.name}"에 넣을 요소를 페이지에서 클릭하세요`)
                      }}
                    >
                      🎯 {active === i ? '고르는 중' : '고르기'}
                    </button>
                    <button type="button" className="hz-x" title="삭제" onClick={() => {
                      setFields((fs) => fs.filter((_, j) => j !== i))
                      setActive(null)
                    }}>✕</button>
                  </div>
                  <input
                    className="hz-sel"
                    value={f.selector}
                    placeholder={rowSelector ? '(비우면 행 자신)' : 'CSS 선택자'}
                    spellCheck={false}
                    onChange={(e) => update(i, { selector: e.target.value })}
                  />
                  {preview.stats[i] && (f.selector || rowSelector.trim()) && (
                    preview.stats[i].found === 0 ? (
                      <div className="hz-stat bad">⚠️ 이 선택자에 맞는 요소가 없습니다</div>
                    ) : preview.stats[i].filled === 0 ? (
                      <div className="hz-stat bad">
                        ⚠️ 요소 {preview.stats[i].found}개를 찾았지만 <b>값이 비어 있습니다</b> (글자가 없는 그림·아이콘 등) —
                        위의 가져올 내용을 바꾸세요
                        <button type="button" className="hz-mini" onClick={() => {
                          const el = rowSelector.trim()
                            ? (() => { const r0 = safeAll(doc()!, rowSelector)[0]; return r0 && (f.selector ? safeOne(r0, f.selector) : r0) })()
                            : safeAll(doc()!, f.selector)[0]
                          if (el) update(i, { attr: autoAttr(el).attr })
                        }}>자동으로 고치기</button>
                      </div>
                    ) : (
                      <div className="hz-stat">요소 {preview.stats[i].found}개{preview.stats[i].filled < preview.stats[i].found ? ` · 값 있는 것 ${preview.stats[i].filled}개` : ''}</div>
                    )
                  )}
                  {f.attr === 'text' && /(^|\s|>)a(\.|:|$)/.test(f.selector.split(' > ').pop() || '') && !fields.some((x) => x.selector === f.selector && x.attr === 'href') && (
                    <button type="button" className="hz-mini" onClick={() => addLinkOf(i)}>+ 이 글의 링크 주소도 가져오기</button>
                  )}
                </div>
              ))}
              <button type="button" className="hz-add" onClick={addField}>+ 항목 추가 (작성자, 날짜 …)</button>
            </div>

            {message && <div className="hz-msg">{message}</div>}

            <div className="hz-actions">
              <button type="button" className="hz-btn" onClick={checkServer} disabled={!url.trim() || !valid}>서버로 확인</button>
              <span style={{ flex: 1 }} />
              <button type="button" className="hz-btn ghost" onClick={onClose}>취소</button>
              <button
                type="button"
                className="hz-btn primary"
                disabled={!valid}
                onClick={() => onApply({ url, headers, rowSelector, fields: fields.filter((f) => f.name.trim()) })}
              >
                노드에 적용
              </button>
            </div>
            {server && <div className={`hz-server ${server.ok ? 'ok' : 'fail'}`}>{server.text}</div>}
          </aside>
        </div>

        <div className="hz-preview">
          <div className="hz-preview-head">
            미리보기 <b>{preview.total}</b>개{preview.total > preview.rows.length ? ` (앞 ${preview.rows.length}개 표시)` : ''}
          </div>
          {preview.rows.length > 0 ? (
            <div className="hz-table-wrap">
              <table className="hz-table">
                <thead>
                  <tr>
                    <th>#</th>
                    {cols.map((c, i) => <th key={i} style={{ color: FIELD_COLORS[fields.indexOf(c) % FIELD_COLORS.length] }}>{c.name}</th>)}
                  </tr>
                </thead>
                <tbody>
                  {preview.rows.map((r, i) => (
                    <tr key={i}>
                      <td>{i + 1}</td>
                      {cols.map((c, j) => <td key={j} title={r[c.name]}>{r[c.name]}</td>)}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="hz-preview-empty">페이지에서 항목을 클릭하면 여기에 뽑힌 값이 보입니다.</div>
          )}
        </div>
      </div>
    </div>
  )
}
