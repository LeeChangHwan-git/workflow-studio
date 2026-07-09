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
      <div className="pnode-header">
        <span className="pnode-dot" style={{ background: nodeType.color }} />
        <span className="pnode-icon">{nodeType.icon}</span>
        <span className="pnode-label">{nodeType.label}</span>
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
      <Handle type="source" position={Position.Right} className="pnode-handle pnode-handle-out" />
    </div>
  )
}
