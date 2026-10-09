import { Handle, Position, type NodeProps } from '@xyflow/react'
import type { Node } from '@xyflow/react'
import { nodeDisplayName, nodeOutputs, ON, PART_HANDLE, SCHEDULE_TYPE, WEBHOOK_TYPE, type PipelineNodeData } from '../types'
import { useNodeActions } from '../NodeActionsContext'
import { openApprovalWindow } from './ApprovalInfoField'
import { useRunStatus } from '../RunStatusContext'
import { useSocketStatus } from '../SocketStatusContext'

export type PipelineFlowNode = Node<PipelineNodeData, 'pipelineNode'>

const STATUS_ICON: Record<string, string> = {
  running: '●',
  success: '✓',
  error:   '✕',
  waiting: '⏳',
  skipped: '–',
}

export function PipelineNodeCard({ id, data, selected }: NodeProps<PipelineFlowNode>) {
  const { onDeleteNode } = useNodeActions()
  const runStatusMap = useRunStatus()
  const socketStatus = useSocketStatus().get(id) ?? {}
  const runLog = runStatusMap.get(id)
  const runStatus = runLog?.status  // 'running' | 'success' | 'error' | undefined

  const { nodeType, config } = data
  const isPart = !!nodeType.provides
  const sockets = nodeType.sockets ?? []
  // 출력이 여러 개(분기·확인 요청)면 오른쪽에 출력마다 연결점 — 이번 실행에서 고른 출력은 강조
  const outputs = isPart ? [] : nodeOutputs(nodeType, config)
  const chosen = new Set(runLog?.route ?? [])

  // 웹훅 수신 중 표시: 테스트(수신 대기) / 운영 URL 중 하나라도 켜져 있으면 초록 LIVE
  const isWebhook = nodeType.id === WEBHOOK_TYPE
  const testOn = isWebhook && config.testEnabled === ON
  const prodOn = isWebhook && config.prodEnabled === ON
  const timerOn = nodeType.id === SCHEDULE_TYPE && config.active === ON
  const live = testOn || prodOn || timerOn

  const classes = [
    'pnode',
    selected && 'selected',
    runStatus && `run-${runStatus}`,
    nodeType.trigger && 'trigger',
    live && 'live',
    isPart && 'part',
    sockets.length > 0 && 'has-sockets',
    outputs.length > 0 && 'has-outputs',
  ].filter(Boolean).join(' ')

  return (
    <div className={classes}>
      {/* 부품 노드: 위쪽 연결점 하나 (에이전트 소켓에 끼움). 일반 노드: 왼쪽 입력 / 오른쪽 출력 */}
      {isPart ? (
        <Handle type="source" id={PART_HANDLE} position={Position.Top} className="pnode-handle pnode-handle-part" />
      ) : (
        !nodeType.trigger && (
          <Handle type="target" position={Position.Left} className="pnode-handle pnode-handle-in" />
        )
      )}

      {/* n8n 스타일: 카테고리 컬러 헤더 */}
      <div className="pnode-cat-bar" style={{ background: nodeType.color }}>
        <span className="pnode-cat-label">
          {/* 이름을 지정했으면 헤더에 원래 타입명을 함께 표시 */}
          {nodeDisplayName(nodeType, config) !== nodeType.label
            ? `${nodeType.category ?? nodeType.id} · ${nodeType.label}`
            : nodeType.category ?? nodeType.id}
        </span>
        {runStatus && (
          <span className={`pnode-run-icon ${runStatus}`} title={runLog?.message}>
            {STATUS_ICON[runStatus]}
          </span>
        )}
        <button
          type="button"
          className="pnode-del"
          title="삭제"
          onClick={(e) => {
            e.stopPropagation()
            onDeleteNode(id)
          }}
        >
          ✕
        </button>
      </div>

      {/* 오퍼레이션 이름 */}
      <div className="pnode-body">
        <span className="pnode-icon">{nodeType.icon}</span>
        <span className="pnode-label">{nodeDisplayName(nodeType, config)}</span>
      </div>

      {live && (
        <div className="pnode-live">
          <span className="pnode-live-dot" />
          {testOn && <span>LIVE 테스트</span>}
          {testOn && prodOn && <span className="pnode-live-sep">·</span>}
          {prodOn && <span>운영</span>}
          {timerOn && <span>타이머 ON</span>}
        </div>
      )}

      {/* 확인 요청이 기다리는 중이면 카드에서 바로 확인 창 열기 */}
      {runStatus === 'waiting' && (
        <button
          type="button"
          className="pnode-approve-btn nodrag"
          onClick={(e) => {
            e.stopPropagation()
            openApprovalWindow(id)
          }}
        >
          🙋 확인하기
        </button>
      )}

      {/* 오른쪽 출력 여러 개 (분기 규칙 · 승인/다시/거절) */}
      {outputs.length > 0 && (
        <div className="pnode-outputs">
          {outputs.map((o) => (
            <div key={o.id} className={`pnode-output${chosen.has(o.id) ? ' chosen' : ''}${o.id === 'else' ? ' else' : ''}`}>
              <span className="pnode-output-label">{o.label}</span>
              <Handle type="source" id={o.id} position={Position.Right} className="pnode-handle pnode-handle-out" />
            </div>
          ))}
        </div>
      )}

      {/* 아래쪽 소켓 (AI 에이전트: 모델 · 메모리 · 지시문) */}
      {sockets.length > 0 && (
        <div className="pnode-sockets">
          {sockets.map((s) => {
            const count = socketStatus[s.id] ?? 0
            const missing = s.required && count === 0
            return (
              <div key={s.id} className={`pnode-socket${count > 0 ? ' filled' : ''}${missing ? ' missing' : ''}`}>
                <span className="pnode-socket-label">
                  {s.label}
                  {s.required && <span className="pnode-socket-req">*</span>}
                  {s.multiple && count > 0 && <span className="pnode-socket-count"> {count}</span>}
                </span>
                <Handle
                  type="target"
                  id={s.id}
                  position={Position.Bottom}
                  className="pnode-handle pnode-handle-socket"
                />
              </div>
            )
          })}
        </div>
      )}

      {!isPart && outputs.length === 0 && (
        <Handle type="source" position={Position.Right} className="pnode-handle pnode-handle-out" />
      )}
    </div>
  )
}
