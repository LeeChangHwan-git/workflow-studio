import { useState } from 'react'
import type { NodeTypeInfo, RunLog, RunMode } from '../types'

interface Props {
  logs: RunLog[]
  status: 'idle' | 'running' | 'done' | 'error'
  mode: RunMode
  /** 웹훅 수신 대기 중 (실행 로그가 없을 때 안내 표시) */
  listening?: boolean
  nodeTypes: NodeTypeInfo[]
  /** nodeId → 표시 이름 (노드에 이름을 지정한 경우) */
  nodeNames?: Map<string, string>
  onSelectNode: (nodeId: string) => void
  onClose: () => void
}

const MODE_LABEL: Record<RunMode, string | null> = {
  manual: null,
  test: '🪝 웹훅 테스트 수신',
  prod: '🪝 웹훅 운영 수신',
}

export function BottomPanel({ logs, status, mode, listening, nodeTypes, nodeNames, onSelectNode, onClose }: Props) {
  const [collapsed, setCollapsed] = useState(false)
  const typeMap = new Map(nodeTypes.map((t) => [t.id, t]))

  const successCount = logs.filter((l) => l.status === 'success').length
  const errorCount   = logs.filter((l) => l.status === 'error').length

  const statusLabel =
    status === 'running' ? '● 실행 중' :
    status === 'done'    ? `✓ 완료` :
    status === 'error'   ? '✕ 오류' :
    listening            ? '● 수신 대기 중' : ''

  return (
    <div className={`bottom-panel${collapsed ? ' collapsed' : ''}`}>
      {/* 헤더 */}
      <div className="bottom-panel-header">
        <span className={`bottom-status-badge ${status}${status === 'idle' && listening ? ' listening' : ''}`}>
          {statusLabel}
        </span>
        {status !== 'running' && (
          <span className="bottom-panel-counts">
            {successCount > 0 && <span className="bp-count success">✓ {successCount}</span>}
            {errorCount   > 0 && <span className="bp-count error">✕ {errorCount}</span>}
          </span>
        )}
        <span className="bottom-panel-title">
          실행 결과{MODE_LABEL[mode] && <span className="bottom-panel-mode"> · {MODE_LABEL[mode]}</span>}
        </span>
        <div className="bottom-panel-actions">
          <button
            type="button"
            className="bp-icon-btn"
            title={collapsed ? '펼치기' : '접기'}
            onClick={() => setCollapsed((c) => !c)}
          >
            {collapsed ? '▲' : '▼'}
          </button>
          <button type="button" className="bp-icon-btn" title="닫기" onClick={onClose}>
            ✕
          </button>
        </div>
      </div>

      {/* 로그 목록 */}
      {!collapsed && (
        <div className="bottom-panel-body">
          {logs.length === 0 ? (
            <div className="bp-empty">
              {listening
                ? '🪝 웹훅 수신 대기 중 — 테스트 URL로 요청이 오면 여기에 실행 과정이 표시됩니다. (모든 수신 내역은 웹훅 노드의 "수신 기록" 탭)'
                : '실행 대기 중...'}
            </div>
          ) : (
            logs.map((log, i) => {
              const nt = typeMap.get(log.nodeType)
              const icon =
                log.status === 'success' ? '✅' :
                log.status === 'error'   ? '❌' : '⏳'
              return (
                <div
                  key={i}
                  className={`bp-log-item ${log.status}`}
                  onClick={() => onSelectNode(log.nodeId)}
                >
                  <span className="bp-log-status">{icon}</span>
                  <span className="bp-log-icon">{nt?.icon ?? '🔹'}</span>
                  <span className="bp-log-label">{nodeNames?.get(log.nodeId) ?? nt?.label ?? log.nodeType}</span>
                  {log.output && (
                    <span className="bp-log-preview">
                      {log.output.replace(/\n/g, ' ').slice(0, 80)}
                      {log.output.length > 80 ? '…' : ''}
                    </span>
                  )}
                  {!log.output && log.status === 'running' && (
                    <span className="bp-log-preview">실행 중...</span>
                  )}
                  {!log.output && log.status === 'error' && (
                    <span className="bp-log-preview error">{log.message}</span>
                  )}
                </div>
              )
            })
          )}
        </div>
      )}
    </div>
  )
}
