import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { RunMode, ScheduleStatus } from '../types'

interface Props {
  label: string
  pipelineId: string | null
  nodeId: string
  config: Record<string, unknown>
  dirty: boolean
  onShowExecution: (execId: string, mode: RunMode) => void
}

const POLL_MS = 4000

/**
 * 타이머 상태 — 화면의 현재 설정으로 계산한 다음 실행 시각(미리보기),
 * 서버에 실제로 등록됐는지, 지금 한 번 실행, 최근 실행 기록.
 */
export function ScheduleInfoField({ label, pipelineId, nodeId, config, dirty, onShowExecution }: Props) {
  const [status, setStatus] = useState<ScheduleStatus | null>(null)
  const [running, setRunning] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const configRef = useRef(config)
  configRef.current = config

  useEffect(() => {
    let stopped = false
    const load = () =>
      api.scheduleStatus(pipelineId, nodeId, configRef.current)
        .then((s) => { if (!stopped) setStatus(s) })
        .catch(() => { /* 다음 주기에 재시도 */ })
    const first = window.setTimeout(load, 250)
    const timer = window.setInterval(load, POLL_MS)
    return () => {
      stopped = true
      window.clearTimeout(first)
      window.clearInterval(timer)
    }
  }, [pipelineId, nodeId, JSON.stringify(config)]) // eslint-disable-line react-hooks/exhaustive-deps

  const runNow = async () => {
    if (!pipelineId) return
    setRunning(true)
    setNotice(null)
    try {
      const res = await api.scheduleRunNow(pipelineId, nodeId)
      if (res.executionId) onShowExecution(res.executionId, 'schedule')
      else setNotice(res.message ?? '실행하지 않았습니다')
    } finally {
      setRunning(false)
    }
  }

  const active = config.active === '켜짐'
  // 저장된 그대로 동작 중이면 실제 등록된 다음 실행 시각, 아니면 화면 값 기준 미리보기
  const preview = status?.registered && !dirty && status.description
    ? { description: status.description, next: status.next, error: undefined }
    : status?.preview

  return (
    <div className="cfg-group">
      <label className="cfg-label">{label}</label>

      {/* 설정 미리보기 (저장 전 값 기준) */}
      {preview?.error ? (
        <div className="verify-box fail">❌ {preview.error}</div>
      ) : preview?.description ? (
        <div className="sched-box">
          <div className="sched-desc">⏰ {preview.description}</div>
          {preview.next && preview.next.length > 0 ? (
            <div className="sched-next">다음 실행: {preview.next.map(fmt).join(' · ')}</div>
          ) : (
            <div className="sched-next">예정된 실행이 없습니다 (이미 지난 시각)</div>
          )}
        </div>
      ) : null}

      {/* 등록 상태 */}
      <div className={`sched-state ${status?.registered ? 'on' : 'off'}`}>
        {!pipelineId
          ? '💾 파이프라인을 저장하면 타이머가 등록됩니다'
          : status?.registered
            ? `● 동작 중${dirty ? ' — 화면에서 바꾼 설정은 저장해야 반영됩니다' : ''}`
            : active
              ? (dirty ? '💾 저장하면 타이머가 시작됩니다' : status?.error ? `❌ 등록 실패: ${status.error}` : '등록 확인 중…')
              : '○ 꺼짐 — 위의 "타이머"를 켜짐으로 바꾸고 저장하세요'}
      </div>

      <button type="button" className="wh-url-copy sched-run" onClick={runNow} disabled={!pipelineId || running}>
        {running ? '실행 중…' : '▶ 지금 한 번 실행'}
      </button>
      {notice && <div className="wh-url-warn">{notice}</div>}

      {/* 실행 기록 */}
      {status && status.history.length > 0 && (
        <div className="sched-hist">
          <div className="sched-hist-title">최근 실행 (최근 100건 보관)</div>
          {status.history.slice(0, 8).map((h, i) => (
            <div key={i} className={`sched-hist-row ${h.result}`}>
              <span className="sched-hist-time">{fmt(h.at)}</span>
              <span className="sched-hist-trigger">{h.trigger === 'now' ? '수동' : '타이머'}</span>
              <span className="sched-hist-result">
                {h.result === 'skipped' ? '건너뜀' : h.result === 'error' ? '오류'
                  : h.executionStatus === 'error' ? '✕ 실행 오류'
                  : h.executionStatus === 'running' ? '● 실행 중' : '✓ 실행'}
              </span>
              {h.executionId && h.executionStatus && (
                <button type="button" className="sched-hist-open" onClick={() => onShowExecution(h.executionId!, 'schedule')}>
                  보기
                </button>
              )}
              {h.message && <span className="sched-hist-msg" title={h.message}>{h.message}</span>}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** 2026-10-03T09:00 → 10-03 09:00 */
function fmt(iso: string): string {
  const m = iso.match(/^\d{4}-(\d{2}-\d{2})T(\d{2}:\d{2})/)
  return m ? `${m[1]} ${m[2]}` : iso
}
