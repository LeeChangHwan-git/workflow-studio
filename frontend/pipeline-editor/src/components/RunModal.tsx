import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { RunLog } from '../types'

interface Props {
  pipelineId: string
  open: boolean
  onClose: () => void
}

export function RunModal({ pipelineId, open, onClose }: Props) {
  const [status, setStatus] = useState<'running' | 'done' | 'error'>('running')
  const [logs, setLogs] = useState<RunLog[]>([])
  const [startError, setStartError] = useState<string | null>(null)
  const intervalRef = useRef<number | null>(null)

  useEffect(() => {
    if (!open) return

    setStatus('running')
    setLogs([])
    setStartError(null)

    let cancelled = false

    api.runPipeline(pipelineId).then((res) => {
      if (cancelled) return
      if (!res.executionId) {
        setStartError(res.error ?? '실행을 시작하지 못했습니다')
        setStatus('error')
        return
      }
      intervalRef.current = window.setInterval(async () => {
        const state = await api.getExecution(res.executionId!)
        setLogs(state.logs)
        setStatus(state.status)
        if (state.status !== 'running' && intervalRef.current) {
          window.clearInterval(intervalRef.current)
          intervalRef.current = null
        }
      }, 1000)
    })

    return () => {
      cancelled = true
      if (intervalRef.current) {
        window.clearInterval(intervalRef.current)
        intervalRef.current = null
      }
    }
  }, [open, pipelineId])

  if (!open) return null

  return (
    <div className="modal-backdrop show" onClick={onClose}>
      <div className="modal-box" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <div className="modal-title">파이프라인 실행</div>
          <button type="button" className="modal-close" onClick={onClose}>
            ✕
          </button>
        </div>
        <div className="modal-body">
          <div className={`overall-status ${status}`}>
            {status === 'running' && '실행 중...'}
            {status === 'done' && '완료되었습니다'}
            {status === 'error' && (startError ?? '오류가 발생했습니다')}
          </div>
          {logs.map((log, i) => (
            <div key={i} className={`log-item ${log.status}`}>
              <div>
                {log.message}
                {log.output && <div className="log-output">{log.output}</div>}
              </div>
            </div>
          ))}
        </div>
        <div className="modal-footer">
          <button type="button" className="btn" onClick={onClose}>
            닫기
          </button>
        </div>
      </div>
    </div>
  )
}
