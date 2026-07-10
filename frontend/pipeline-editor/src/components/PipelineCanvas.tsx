import { useMemo, type RefObject } from 'react'
import {
  Background,
  BackgroundVariant,
  Controls,
  MiniMap,
  ReactFlow,
  type Edge,
  type EdgeChange,
  type NodeChange,
  type OnConnect,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { PipelineNodeCard, type PipelineFlowNode } from './PipelineNodeCard'
import { NodeActionsContext } from '../NodeActionsContext'
import { RunStatusContext } from '../RunStatusContext'
import type { RunLog } from '../types'

const nodeTypeComponents = { pipelineNode: PipelineNodeCard }

interface Props {
  nodes: PipelineFlowNode[]
  edges: Edge[]
  wrapperRef: RefObject<HTMLDivElement | null>
  dragOver: boolean
  runStatusMap: Map<string, RunLog>
  onNodesChange: (changes: NodeChange<PipelineFlowNode>[]) => void
  onEdgesChange: (changes: EdgeChange[]) => void
  onConnect: OnConnect
  onSelectNode: (id: string | null) => void
  onDeleteNode: (id: string) => void
}

export function PipelineCanvas({
  nodes,
  edges,
  wrapperRef,
  dragOver,
  runStatusMap,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onSelectNode,
  onDeleteNode,
}: Props) {
  const actions = useMemo(() => ({ onDeleteNode }), [onDeleteNode])

  return (
    <div className={`canvas-wrap${dragOver ? ' drag-over' : ''}`} ref={wrapperRef}>
      <NodeActionsContext.Provider value={actions}>
        <RunStatusContext.Provider value={runStatusMap}>
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypeComponents}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            onConnect={onConnect}
            onNodeClick={(_, node) => onSelectNode(node.id)}
            onPaneClick={() => onSelectNode(null)}
            colorMode="dark"
            fitView
            proOptions={{ hideAttribution: true }}
          >
            <Background variant={BackgroundVariant.Dots} gap={24} size={1.5} color="#3A3A3A" />
            <Controls position="bottom-left" />
            <MiniMap
              position="top-right"
              pannable
              zoomable
              style={{ background: '#252526' }}
              maskColor="rgba(0,0,0,0.6)"
            />
          </ReactFlow>
        </RunStatusContext.Provider>
      </NodeActionsContext.Provider>
    </div>
  )
}
