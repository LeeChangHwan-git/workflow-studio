import { Handle, Position, type NodeProps } from '@xyflow/react'
import type { Node } from '@xyflow/react'
import type { PipelineNodeData } from '../types'
import { useNodeActions } from '../NodeActionsContext'

export type PipelineFlowNode = Node<PipelineNodeData, 'pipelineNode'>

export function PipelineNodeCard({ id, data, selected }: NodeProps<PipelineFlowNode>) {
  const { onDeleteNode } = useNodeActions()
  const { nodeType } = data

  return (
    <div className={`pnode${selected ? ' selected' : ''}`}>
      <Handle type="target" position={Position.Left} className="pnode-handle pnode-handle-in" />

      {/* n8n 스타일: 카테고리 컬러 헤더 */}
      <div className="pnode-cat-bar" style={{ background: nodeType.color }}>
        <span className="pnode-cat-label">{nodeType.category ?? nodeType.id}</span>
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
        <span className="pnode-label">{nodeType.label}</span>
      </div>

      <Handle type="source" position={Position.Right} className="pnode-handle pnode-handle-out" />
    </div>
  )
}
