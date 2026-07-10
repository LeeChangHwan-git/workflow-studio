import { useState } from 'react'
import type { NodeTypeInfo, RunLog } from '../types'

interface Props {
  logs: RunLog[]
  status: 'idle' | 'running' | 'done' | 'error'
  nodeTypes: NodeTypeInfo[]
  onSelectNode: (nodeId: string) => void
  onClose: () => void
}

export function BottomPanel({ logs, status, nodeTypes, onSelectNode, onClose }: Props) {
  const [collapsed, setCollapsed] = useState(false)
  const typeMap = new Map(nodeTypes.map((t) => [t.id, t]))

  const successCount = logs.filter((l) => l.status === 'success').length
  const errorCount   = logs.filter((l) => l.status === 'error').length

  const statusLabel =
    status === 'running' ? '● 실행 중' :
    status === 'done'    ? `✓ 완료` :
    status === 'error'   ? '✕ 오류' : ''

  return (
    <div className={`bottom-panel${collapsed ? ' collapsed' : ''}`}>
      {/* 헤더 */}
      <div className="bottom-panel-header">
        <span className={`bottom-status-badge ${status}`}>{statusLabel}</span>
        {status !== 'running' && (
          <span className="bottom-panel-counts">
            {successCount > 0 && <span className="bp-count success">✓ {successCount}</span>}
            {errorCount   > 0 && <span className="bp-count error">✕ {errorCount}</span>}
          </span>
        )}
        <span className="bottom-panel-title">실행 결과</span>
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
            <div className="bp-empty">실행 대기 중...</div>
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
                  <span className="bp-log-label">{nt?.label ?? log.nodeType}</span>
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
