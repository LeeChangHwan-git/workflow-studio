import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { NodeParam, OutlineVerify } from '../types'

/**
 * 아웃라인 노드 전용 설정 필드.
 * 연결 확인 결과(컬렉션 목록 포함)를 주소+키별로 공유해서, 연결 필드와 컬렉션 필드가 한 번만 조회한다.
 */

type Entry = { state: 'checking' } | { state: 'done'; result: OutlineVerify }

const cache = new Map<string, Entry>()
const listeners = new Set<() => void>()
const keyOf = (baseUrl: string, apiKey: string) => `${baseUrl.trim()}\n${apiKey.trim()}`

function check(baseUrl: string, apiKey: string, force = false) {
  const k = keyOf(baseUrl, apiKey)
  const cur = cache.get(k)
  if (!force && cur) return
  cache.set(k, { state: 'checking' })
  listeners.forEach((l) => l())
  api
    .verifyOutline(baseUrl, apiKey)
    .catch((): OutlineVerify => ({ ok: false, message: '확인 요청 실패 (서버 연결 확인)', keySource: 'none' }))
    .then((result) => {
      cache.set(k, { state: 'done', result })
      listeners.forEach((l) => l())
    })
}

/** 주소·키가 바뀌면 0.8초 뒤 자동 확인 */
function useOutlineStatus(baseUrl: string, apiKey: string): Entry | undefined {
  const [, force] = useState(0)
  useEffect(() => {
    const l = () => force((n) => n + 1)
    listeners.add(l)
    return () => {
      listeners.delete(l)
    }
  }, [])
  useEffect(() => {
    const t = window.setTimeout(() => check(baseUrl, apiKey), 800)
    return () => window.clearTimeout(t)
  }, [baseUrl, apiKey])
  return cache.get(keyOf(baseUrl, apiKey))
}

/** 연결 확인: 상태 + 다시 확인 버튼 */
export function OutlineConnectionField({
  label,
  baseUrl,
  apiKey,
}: {
  label: string
  baseUrl: string
  apiKey: string
}) {
  const status = useOutlineStatus(baseUrl, apiKey)
  const checking = status?.state === 'checking'
  const r = status?.state === 'done' ? status.result : null
  const source = r ? (r.keySource === 'node' ? '노드에 입력한 키' : r.keySource === 'settings' ? '⚙ 설정의 공용 키' : r.keySource === 'env' ? 'OUTLINE_API_KEY 환경 변수' : '키 없음') : null

  return (
    <div className="cfg-group">
      <label className="cfg-label">{label}</label>
      <button
        type="button"
        className="cfg-folder-btn outline-verify-btn"
        disabled={checking}
        onClick={() => check(baseUrl, apiKey, true)}
      >
        {checking ? '확인 중…' : '🔑 연결 확인'}
      </button>
      {(checking || r) && (
        <div className={`verify-box ${checking ? 'checking' : r?.ok ? 'ok' : 'fail'}`}>
          {checking ? (
            <span>⏳ 아웃라인에 접속하는 중…</span>
          ) : (
            r && (
              <>
                <div>{r.ok ? '✅' : '❌'} {r.message}</div>
                <div className="verify-sub">
                  {source}
                  {r.ok && ` · 컬렉션 ${r.collections?.length ?? 0}개`}
                </div>
              </>
            )
          )}
        </div>
      )}
    </div>
  )
}

/** 컬렉션: 이름 입력 + 연결 확인으로 받은 목록에서 고르기 (${...}도 허용) */
export function OutlineCollectionField({
  param,
  value,
  baseUrl,
  apiKey,
  onChange,
}: {
  param: NodeParam
  value: string
  baseUrl: string
  apiKey: string
  onChange: (v: string) => void
}) {
  const status = useOutlineStatus(baseUrl, apiKey)
  const collections = status?.state === 'done' && status.result.ok ? status.result.collections ?? [] : []
  const listId = useRef(`outline-cols-${Math.random().toString(36).slice(2)}`).current
  const known = !value.trim() || value.includes('${') || collections.some((c) => c.name === value || c.id === value)

  return (
    <div className="cfg-group">
      <label className="cfg-label">{param.label}</label>
      <input
        className="cfg-input"
        type="text"
        autoComplete="off"
        spellCheck={false}
        list={listId}
        placeholder={collections.length ? '입력칸을 눌러 고르기' : '컬렉션 이름'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      <datalist id={listId}>
        {collections.map((c) => (
          <option key={c.id} value={c.name} />
        ))}
      </datalist>
      {collections.length > 0 && !known && (
        <div className="wh-url-hint">⚠️ 이 이름의 컬렉션이 없습니다 — 있는 컬렉션: {collections.map((c) => c.name).join(', ')}</div>
      )}
    </div>
  )
}
