import { useState } from 'react'
import { convertPasted, headerSummary, mergeHeaders, openCookieGuide } from '../headerPaste'

/**
 * 요청 헤더 입력 — 개발자 도구에서 복사한 cURL·요청 헤더·쿠키 값을 붙여넣으면 "Cookie: …" 로 자동 변환.
 * ❓ 버튼은 쿠키 찾는 법 안내 창(/guide/cookie)을 따로 띄운다.
 */
export function HeadersField({
  value,
  onChange,
  className,
  placeholder,
  rows,
}: {
  value: string
  onChange: (v: string) => void
  className?: string
  placeholder?: string
  rows?: number
}) {
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null)
  const [show, setShow] = useState(false)

  const onPaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const text = e.clipboardData.getData('text')
    const r = convertPasted(text)
    if (!r) return // 평범한 입력 → 그대로 붙여넣기
    e.preventDefault()
    if (!r.lines.length) {
      setNote({ ok: false, text: '붙여넣은 내용에 Cookie 가 없습니다 — 로그인한 상태에서, 목록 페이지(문서) 요청을 복사했는지 확인하세요' })
      return
    }
    onChange(mergeHeaders(value, r.lines))
    const from = r.kind === 'curl' ? 'cURL' : r.kind === 'headers' ? '요청 헤더' : '쿠키 값'
    setNote({ ok: true, text: `✓ ${from}에서 쿠키 ${r.cookieCount}개를 찾아 "Cookie:" 줄로 넣었습니다${r.lines.length > 1 ? ' (Authorization 포함)' : ''}` })
  }

  return (
    <div className="hdr-field">
      <div className="hdr-top">
        <span className="hdr-summary">{headerSummary(value)}</span>
        <button type="button" className="hdr-toggle" onClick={() => setShow((v) => !v)}>
          {show ? '값 숨기기' : '값 보기'}
        </button>
        <button type="button" className="hdr-help" onClick={openCookieGuide}>
          ❓ 쿠키 찾는 법
        </button>
      </div>
      <textarea
        className={`${className ?? ''}${show ? '' : ' hdr-masked'}`}
        value={value}
        rows={rows}
        spellCheck={false}
        autoComplete="off"
        placeholder={placeholder ?? '개발자 도구에서 "cURL로 복사"한 내용을 그대로 붙여넣으세요 → 쿠키만 자동으로 뽑아 넣습니다'}
        onPaste={onPaste}
        onChange={(e) => {
          setNote(null)
          onChange(e.target.value)
        }}
      />
      {note && <div className={`hdr-note ${note.ok ? 'ok' : 'fail'}`}>{note.text}</div>}
    </div>
  )
}
