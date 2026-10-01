import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { WebhookPreview } from '../types'

interface Props {
  label: string
  nodeId: string
  value: string
  onChange: (v: string) => void
}

const REFRESH_MS = 3000

/**
 * 웹훅 '전달 내용 구성' 입력 — 템플릿 + 사용 가능한 키 칩(클릭 시 커서 위치에 삽입) +
 * 마지막 수신 요청 기준 실시간 미리보기. 렌더링은 서버(WebhookModule.compose)와 동일한 규칙.
 */
export function WebhookTemplateField({ label, nodeId, value, onChange }: Props) {
  const [preview, setPreview] = useState<WebhookPreview | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // 템플릿이 바뀌면 300ms 뒤 미리보기, 새 요청이 들어올 수 있으니 주기적으로도 갱신
  useEffect(() => {
    let stopped = false
    const load = () =>
      api.previewWebhook(nodeId, value)
        .then((p) => { if (!stopped) setPreview(p) })
        .catch(() => { /* 다음 주기에 재시도 */ })
    const first = window.setTimeout(load, 300)
    const timer = window.setInterval(load, REFRESH_MS)
    return () => {
      stopped = true
      window.clearTimeout(first)
      window.clearInterval(timer)
    }
  }, [nodeId, value])

  // 칩 삽입 후 커서를 삽입 지점 뒤로 — 값이 반영된 직후(그리기 전)에 복원해야 바로 이어 타이핑해도 위치가 맞음
  const pendingCursor = useRef<number | null>(null)
  useLayoutEffect(() => {
    const el = textareaRef.current
    if (el && pendingCursor.current !== null) {
      el.setSelectionRange(pendingCursor.current, pendingCursor.current)
      pendingCursor.current = null
    }
  }, [value])

  const insertKey = (key: string) => {
    const token = `\${${key}}`
    const el = textareaRef.current
    if (!el) {
      onChange(value + token)
      return
    }
    const start = el.selectionStart ?? value.length
    const end = el.selectionEnd ?? value.length
    pendingCursor.current = start + token.length
    el.focus()
    onChange(value.slice(0, start) + token + value.slice(end))
  }

  return (
    <div className="cfg-group">
      <label className="cfg-label">{label}</label>
      <textarea
        ref={textareaRef}
        className="cfg-textarea wh-tpl-input"
        value={value}
        placeholder={'${a}==${b}==${c}'}
        spellCheck={false}
        onChange={(e) => onChange(e.target.value)}
      />

      {preview?.hasSample && preview.keys && preview.keys.length > 0 && (
        <div className="wh-tpl-keys">
          <span className="wh-tpl-keys-title">키 (클릭하여 삽입)</span>
          {preview.keys.map((k) => (
            <button
              key={k}
              type="button"
              className="wh-tpl-chip"
              onMouseDown={(e) => e.preventDefault()} // 입력창 포커스 유지
              onClick={() => insertKey(k)}
            >
              {k}
            </button>
          ))}
        </div>
      )}

      <div className="wh-tpl-preview">
        <div className="wh-tpl-preview-title">
          미리보기
          {preview?.hasSample && preview.sampleAt && (
            <span className="wh-tpl-preview-sub"> · 마지막 수신 {preview.sampleAt.replace('T', ' ').slice(5, 19)} 기준</span>
          )}
        </div>
        {!preview ? (
          <div className="wh-tpl-preview-empty">불러오는 중...</div>
        ) : !preview.hasSample ? (
          <div className="wh-tpl-preview-empty">
            아직 받은 요청이 없어 미리볼 수 없습니다. 테스트 요청을 한 번 보내면 키 목록과 결과가 표시됩니다.
          </div>
        ) : !value.trim() ? (
          <div className="wh-tpl-preview-empty">비어 있음 → 요청 전체(JSON)가 다음 노드로 전달됩니다.</div>
        ) : (
          <pre className="wh-tpl-preview-out">{preview.result}</pre>
        )}
      </div>
      <div className="wh-url-hint">
        <code>{'${a}'}</code> = 바디의 a, <code>{'${body.items[0].id}'}</code> 처럼 경로 지정,
        <code>{'${query.x}'}</code> <code>{'${headers.user-agent}'}</code> <code>{'${method}'}</code>도 사용 가능.
        없는 키는 <code>{'${...}'}</code> 그대로 남습니다. 구성하면 요청 전체는 <code>{'${출력변수.request.body...}'}</code>로 참조합니다.
      </div>
    </div>
  )
}
