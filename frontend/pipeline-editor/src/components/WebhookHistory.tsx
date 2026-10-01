import { useEffect, useState } from 'react'
import { api } from '../api'
import type { RunMode, WebhookHistoryEntry } from '../types'
import { OutputRenderer } from './OutputRenderer'

interface Props {
  nodeId: string
  outputVar: string
  onShowExecution: (execId: string, mode: RunMode) => void
}

const POLL_MS = 2000

/** 웹훅 노드의 수신 기록 (최신순, 탭이 열려 있는 동안 2초마다 갱신) */
export function WebhookHistory({ nodeId, outputVar, onShowExecution }: Props) {
  const [entries, setEntries] = useState<WebhookHistoryEntry[] | null>(null)
  const [openId, setOpenId] = useState<string | null>(null)

  useEffect(() => {
    let stopped = false
    const load = async () => {
      try {
        const list = await api.getWebhookHistory(nodeId)
        if (!stopped) setEntries(list)
      } catch {
        if (!stopped) setEntries((cur) => cur ?? [])
      }
    }
    load()
    const timer = window.setInterval(load, POLL_MS)
    return () => {
      stopped = true
      window.clearInterval(timer)
    }
  }, [nodeId])

  const handleClear = async () => {
    if (!window.confirm('이 웹훅의 수신 기록을 모두 삭제하시겠습니까?')) return
    await api.clearWebhookHistory(nodeId)
    setEntries([])
    setOpenId(null)
  }

  if (entries === null) {
    return <div className="config-result-body"><div className="wh-hist-empty">불러오는 중...</div></div>
  }

  return (
    <div className="config-result-body">
      <div className="wh-hist-head">
        <span>총 {entries.length}건 <span className="wh-hist-sub">(최근 200건 보관)</span></span>
        {entries.length > 0 && (
          <button type="button" className="wh-url-copy" onClick={handleClear}>기록 삭제</button>
        )}
      </div>

      {entries.length === 0 ? (
        <div className="wh-hist-empty">
          아직 받은 요청이 없습니다.<br />▶ 수신 대기를 켜고 테스트 URL로 요청을 보내보세요.
        </div>
      ) : (
        entries.map((e) => {
          const open = openId === e.id
          const state = !e.accepted ? 'rejected' : e.executionStatus ?? 'unknown'
          return (
            <div key={e.id} className={`wh-hist-item ${state}${open ? ' open' : ''}`}>
              <div className="wh-hist-row" onClick={() => setOpenId(open ? null : e.id)}>
                <span className="wh-hist-arrow">{open ? '▼' : '▶'}</span>
                <span className="wh-hist-time">{formatTime(e.receivedAt)}</span>
                <span className={`wh-url-badge ${e.mode}`}>{e.mode === 'test' ? '테스트' : '운영'}</span>
                <span className="wh-hist-method">{e.method}</span>
                <span className="wh-hist-status">{statusText(e)}</span>
              </div>
              {open && (
                <div className="wh-hist-detail">
                  <div className="wh-hist-msg">{e.message} · HTTP {e.httpStatus}</div>
                  {e.accepted && e.executionId && (
                    <button
                      type="button"
                      className="wh-url-copy"
                      onClick={() => onShowExecution(e.executionId!, e.mode)}
                      disabled={!e.executionStatus}
                      title={e.executionStatus ? '' : '서버 재시작으로 실행 기록이 사라졌습니다'}
                    >
                      실행 결과 보기
                    </button>
                  )}
                  <OutputRenderer text={JSON.stringify(e.payload, null, 2)} outputVar={outputVar} />
                </div>
              )}
            </div>
          )
        })
      )}
    </div>
  )
}

function statusText(e: WebhookHistoryEntry): string {
  if (!e.accepted) return `✕ 거부 ${e.httpStatus}`
  switch (e.executionStatus) {
    case 'running': return '● 실행 중'
    case 'done': return '✓ 완료'
    case 'error': return '✕ 실행 오류'
    default: return '✓ 실행됨'
  }
}

/** 2026-10-01T23:01:47.0662643 → 10-01 23:01:47 */
function formatTime(iso: string): string {
  const m = iso.match(/^\d{4}-(\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})/)
  return m ? `${m[1]} ${m[2]}` : iso
}
