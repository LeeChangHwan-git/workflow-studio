import { Handle, Position, type NodeProps } from '@xyflow/react'
import type { Node } from '@xyflow/react'
import { nodeDisplayName, ON, WEBHOOK_TYPE, type PipelineNodeData } from '../types'
import { useNodeActions } from '../NodeActionsContext'
import { useRunStatus } from '../RunStatusContext'

export type PipelineFlowNode = Node<PipelineNodeData, 'pipelineNode'>

const STATUS_ICON: Record<string, string> = {
  running: '●',
  success: '✓',
  error:   '✕',
}

export function PipelineNodeCard({ id, data, selected }: NodeProps<PipelineFlowNode>) {
  const { onDeleteNode } = useNodeActions()
  const runStatusMap = useRunStatus()
  const runLog = runStatusMap.get(id)
  const runStatus = runLog?.status  // 'running' | 'success' | 'error' | undefined

  const { nodeType, config } = data

  // 웹훅 수신 중 표시: 테스트(수신 대기) / 운영 URL 중 하나라도 켜져 있으면 초록 LIVE
  const isWebhook = nodeType.id === WEBHOOK_TYPE
  const testOn = isWebhook && config.testEnabled === ON
  const prodOn = isWebhook && config.prodEnabled === ON
  const live = testOn || prodOn

  return (
    <div
      className={`pnode${selected ? ' selected' : ''}${runStatus ? ` run-${runStatus}` : ''}${nodeType.trigger ? ' trigger' : ''}${live ? ' live' : ''}`}
    >
      {/* 트리거 노드는 파이프라인 시작점 → 입력 연결 없음 */}
      {!nodeType.trigger && (
        <Handle type="target" position={Position.Left} className="pnode-handle pnode-handle-in" />
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
        </div>
      )}

      <Handle type="source" position={Position.Right} className="pnode-handle pnode-handle-out" />
    </div>
  )
}
