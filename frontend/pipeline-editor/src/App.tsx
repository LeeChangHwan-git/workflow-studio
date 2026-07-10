import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  addEdge,
  MarkerType,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
} from '@xyflow/react'
import { api } from './api'
import type { NodeTypeInfo, PipelineDto, RunLog } from './types'
import type { PipelineFlowNode } from './components/PipelineNodeCard'
import { Palette } from './components/Palette'
import { PipelineCanvas } from './components/PipelineCanvas'
import { ConfigPanel } from './components/ConfigPanel'
import { Toolbar } from './components/Toolbar'
import { BottomPanel } from './components/BottomPanel'
import { usePaletteDrag } from './usePaletteDrag'

function defaultConfig(nodeType: NodeTypeInfo): Record<string, unknown> {
  const config: Record<string, unknown> = {}
  for (const param of nodeType.params) {
    config[param.name] = param.defaultValue ?? (param.type === 'number' ? 0 : '')
  }
  return config
}

interface Props {
  initialPipelineId: string
}

export default function App({ initialPipelineId }: Props) {
  const [nodeTypes, setNodeTypes] = useState<NodeTypeInfo[]>([])
  const [pipelineId, setPipelineId] = useState<string | null>(initialPipelineId || null)
  const [name, setName] = useState('새 파이프라인')
  const [description, setDescription] = useState('')
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)

  // ── 실행 상태 ──
  const [runLogs, setRunLogs] = useState<RunLog[]>([])
  const [runStatus, setRunStatus] = useState<'idle' | 'running' | 'done' | 'error'>('idle')
  const [bottomPanelOpen, setBottomPanelOpen] = useState(false)
  const execIntervalRef = useRef<number | null>(null)

  const [nodes, setNodes, onNodesChange] = useNodesState<PipelineFlowNode>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])
  const canvasWrapperRef = useRef<HTMLDivElement>(null)

  // nodeId → RunLog 맵 (PipelineNodeCard에서 상태 표시용)
  const runStatusMap = useMemo(() => {
    const map = new Map<string, RunLog>()
    for (const log of runLogs) map.set(log.nodeId, log)
    return map
  }, [runLogs])

  // 선택된 노드의 실행 로그
  const selectedNodeLog = selectedNodeId ? runStatusMap.get(selectedNodeId) : undefined

  useEffect(() => {
    let cancelled = false

    async function load() {
      const types = await api.getNodeTypes()
      if (cancelled) return
      setNodeTypes(types)

      if (initialPipelineId) {
        const pipeline = await api.getPipeline(initialPipelineId)
        if (cancelled) return
        applyPipeline(pipeline, types)
      }
      setLoading(false)

      if (initialPipelineId && new URLSearchParams(window.location.search).get('autorun') === 'true') {
        window.history.replaceState(null, '', `/pipelines/${initialPipelineId}`)
        startExecution(initialPipelineId)
      }
    }

    load()
    return () => { cancelled = true }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialPipelineId])

  // 정리
  useEffect(() => {
    return () => {
      if (execIntervalRef.current) window.clearInterval(execIntervalRef.current)
    }
  }, [])

  function applyPipeline(pipeline: PipelineDto, types: NodeTypeInfo[]) {
    setName(pipeline.name || '새 파이프라인')
    setDescription(pipeline.description || '')
    const typeMap = new Map(types.map((t) => [t.id, t]))
    setNodes(
      pipeline.nodes.map((n) => ({
        id: n.id,
        type: 'pipelineNode',
        position: { x: n.posX, y: n.posY },
        data: { nodeType: typeMap.get(n.type)!, config: n.config },
      })),
    )
    setEdges(
      pipeline.connections.map((c) => ({
        id: `${c.fromNodeId}-${c.toNodeId}`,
        source: c.fromNodeId,
        target: c.toNodeId,
        markerEnd: { type: MarkerType.ArrowClosed },
      })),
    )
  }

  async function startExecution(id: string) {
    if (execIntervalRef.current) {
      window.clearInterval(execIntervalRef.current)
      execIntervalRef.current = null
    }
    setRunLogs([])
    setRunStatus('running')
    setBottomPanelOpen(true)

    try {
      const res = await api.runPipeline(id)
      if (!res.executionId) {
        setRunStatus('error')
        return
      }
      execIntervalRef.current = window.setInterval(async () => {
        const state = await api.getExecution(res.executionId!)
        setRunLogs(state.logs)
        if (state.status !== 'running') {
          setRunStatus(state.status as 'done' | 'error')
          window.clearInterval(execIntervalRef.current!)
          execIntervalRef.current = null
        }
      }, 1000)
    } catch {
      setRunStatus('error')
    }
  }

  const handleAddNode = useCallback(
    (typeId: string, position: { x: number; y: number }) => {
      const nodeType = nodeTypes.find((nt) => nt.id === typeId)
      if (!nodeType) return
      const newNode: PipelineFlowNode = {
        id: crypto.randomUUID(),
        type: 'pipelineNode',
        position,
        data: { nodeType, config: defaultConfig(nodeType) },
      }
      setNodes((nds) => [...nds, newNode])
    },
    [nodeTypes, setNodes],
  )

  const { startDrag, dragging, ghostPos, overCanvas, nodeTypeInfo } = usePaletteDrag(
    nodeTypes,
    canvasWrapperRef,
    handleAddNode,
  )

  const handleDeleteNode = useCallback(
    (id: string) => {
      setNodes((nds) => nds.filter((n) => n.id !== id))
      setEdges((eds) => eds.filter((e) => e.source !== id && e.target !== id))
      setSelectedNodeId((cur) => (cur === id ? null : cur))
    },
    [setNodes, setEdges],
  )

  const handleConnect = useCallback(
    (connection: Connection) => {
      if (connection.source === connection.target) return
      setEdges((eds) => addEdge({ ...connection, markerEnd: { type: MarkerType.ArrowClosed } }, eds))
    },
    [setEdges],
  )

  const handleConfigChange = useCallback(
    (key: string, value: unknown) => {
      setNodes((nds) =>
        nds.map((n) =>
          n.id === selectedNodeId
            ? { ...n, data: { ...n.data, config: { ...n.data.config, [key]: value } } }
            : n,
        ),
      )
    },
    [selectedNodeId, setNodes],
  )

  const buildPayload = useCallback(
    (): Partial<PipelineDto> => ({
      name,
      description,
      nodes: nodes.map((n) => ({
        id: n.id,
        type: n.data.nodeType.id,
        posX: n.position.x,
        posY: n.position.y,
        config: n.data.config,
      })),
      connections: edges.map((e) => ({ fromNodeId: e.source, toNodeId: e.target })),
    }),
    [name, description, nodes, edges],
  )

  const handleSave = useCallback(async (): Promise<string> => {
    setSaving(true)
    try {
      const payload = buildPayload()
      if (pipelineId) {
        await api.updatePipeline(pipelineId, payload)
        return pipelineId
      }
      const created = await api.createPipeline(payload)
      setPipelineId(created.id)
      window.history.replaceState(null, '', `/pipelines/${created.id}`)
      return created.id
    } finally {
      setSaving(false)
    }
  }, [pipelineId, buildPayload])

  const handleRun = useCallback(async () => {
    const id = await handleSave()
    setPipelineId(id)
    await startExecution(id)
  }, [handleSave])  // eslint-disable-line react-hooks/exhaustive-deps

  const handleExport = useCallback(() => {
    if (!pipelineId) return
    window.location.href = api.exportPipeline(pipelineId)
  }, [pipelineId])

  const handleImport = useCallback(async (file: File) => {
    const created = await api.importPipeline(file)
    window.location.href = `/pipelines/${created.id}`
  }, [])

  const handleDeleteAll = useCallback(() => {
    if (!window.confirm('모든 노드와 연결을 삭제하시겠습니까?')) return
    setNodes([])
    setEdges([])
    setSelectedNodeId(null)
  }, [setNodes, setEdges])

  const handleSelectNodeFromPanel = useCallback((nodeId: string) => {
    setSelectedNodeId(nodeId)
  }, [])

  if (loading) return null

  const selectedNode = nodes.find((n) => n.id === selectedNodeId) ?? null

  return (
    <div className="editor-wrap">
      <div className="editor-main">
        <Palette nodeTypes={nodeTypes} onStartDrag={startDrag} />
        <PipelineCanvas
          nodes={nodes}
          edges={edges}
          wrapperRef={canvasWrapperRef}
          dragOver={dragging && overCanvas}
          runStatusMap={runStatusMap}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={handleConnect}
          onSelectNode={setSelectedNodeId}
          onDeleteNode={handleDeleteNode}
        />
        {dragging && ghostPos && nodeTypeInfo && (
          <div className="drag-ghost" style={{ left: ghostPos.x + 12, top: ghostPos.y + 8 }}>
            {nodeTypeInfo.icon} {nodeTypeInfo.label}
          </div>
        )}
        <ConfigPanel
          nodeType={selectedNode?.data.nodeType ?? null}
          config={selectedNode?.data.config ?? {}}
          onChange={handleConfigChange}
          runLog={selectedNodeLog}
        />
      </div>

      {/* 하단 실행 결과 패널 */}
      {bottomPanelOpen && (
        <BottomPanel
          logs={runLogs}
          status={runStatus}
          nodeTypes={nodeTypes}
          onSelectNode={handleSelectNodeFromPanel}
          onClose={() => setBottomPanelOpen(false)}
        />
      )}

      <Toolbar
        name={name}
        description={description}
        onNameChange={setName}
        onDescriptionChange={setDescription}
        onSave={handleSave}
        onRun={handleRun}
        onExport={handleExport}
        onImport={handleImport}
        onDeleteAll={handleDeleteAll}
        saving={saving}
        canExport={!!pipelineId}
        bottomPanelOpen={bottomPanelOpen}
      />
    </div>
  )
}
