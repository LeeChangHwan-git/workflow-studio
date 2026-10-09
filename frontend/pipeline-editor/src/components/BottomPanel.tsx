import type { NodeTypeInfo, RunLog, RunMode } from '../types'
import { openApprovalWindow } from './ApprovalInfoField'

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
  /** 펼쳤을 때 높이(px) — 위쪽 가장자리 드래그로 조절 */
  height: number
  onHeightChange: (h: number, done: boolean) => void
  onResetHeight: () => void
  collapsed: boolean
  onToggleCollapse: () => void
}

export const BOTTOM_HEADER_HEIGHT = 36
export const BOTTOM_MIN_HEIGHT = 90

const MODE_LABEL: Record<RunMode, string | null> = {
  manual: null,
  test: '🪝 웹훅 테스트 수신',
  prod: '🪝 웹훅 운영 수신',
  schedule: '⏰ 타이머 실행',
}

export function BottomPanel({
  logs, status, mode, listening, nodeTypes, nodeNames, onSelectNode, onClose,
  height, onHeightChange, onResetHeight, collapsed, onToggleCollapse,
}: Props) {
  const typeMap = new Map(nodeTypes.map((t) => [t.id, t]))

  // 위쪽 가장자리를 위로 끌수록 높아짐 (최소 BOTTOM_MIN_HEIGHT, 최대 화면의 75%)
  const startResize = (e: React.MouseEvent) => {
    if (collapsed) return
    e.preventDefault()
    const startY = e.clientY
    const startH = height
    const clamp = (h: number) =>
      Math.round(Math.min(window.innerHeight * 0.75, Math.max(BOTTOM_MIN_HEIGHT, h)))
    let last = startH
    document.body.classList.add('resizing-row')
    const onMove = (ev: MouseEvent) => {
      last = clamp(startH + (startY - ev.clientY))
      onHeightChange(last, false)
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.classList.remove('resizing-row')
      onHeightChange(last, true)
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  const successCount = logs.filter((l) => l.status === 'success').length
  const errorCount   = logs.filter((l) => l.status === 'error').length
  const waiting      = logs.some((l) => l.status === 'waiting')

  const statusLabel =
    status === 'running' && waiting ? '🙋 확인 기다리는 중' :
    status === 'running' ? '● 실행 중' :
    status === 'done'    ? `✓ 완료` :
    status === 'error'   ? '✕ 오류' :
    listening            ? '● 수신 대기 중' : ''

  return (
    <div
      className={`bottom-panel${collapsed ? ' collapsed' : ''}`}
      style={{ height: collapsed ? BOTTOM_HEADER_HEIGHT : height }}
    >
      {!collapsed && (
        <div
          className="bottom-resizer"
          onMouseDown={startResize}
          onDoubleClick={onResetHeight}
          title="드래그해서 높이 조절 (더블클릭: 초기화)"
        />
      )}
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
            onClick={onToggleCollapse}
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
                log.status === 'error'   ? '❌' :
                log.status === 'waiting' ? '🙋' :
                log.status === 'skipped' ? '⏭️' : '⏳'
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
                    <span className="bp-log-preview">{/[가-힣]/.test(log.message ?? '') && !/실행 중...$/.test(log.message) ? log.message : '실행 중...'}</span>
                  )}
                  {log.status === 'waiting' && (
                    <>
                      <span className="bp-log-preview waiting">{log.message}</span>
                      <button
                        type="button"
                        className="bp-approve-btn"
                        onClick={(e) => {
                          e.stopPropagation()
                          openApprovalWindow(log.nodeId)
                        }}
                      >
                        🙋 확인 창 열기
                      </button>
                    </>
                  )}
                  {log.status === 'skipped' && <span className="bp-log-preview">{log.message}</span>}
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
