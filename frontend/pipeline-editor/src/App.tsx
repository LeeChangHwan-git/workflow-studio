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
import { nodeDisplayName, nodeOutputs, OFF, ON, PART_HANDLE, WEBHOOK_TYPE } from './types'
import type { NodeTypeInfo, PipelineDto, RunLog, RunMode } from './types'
import type { PipelineFlowNode } from './components/PipelineNodeCard'
import { Palette } from './components/Palette'
import { PipelineCanvas } from './components/PipelineCanvas'
import { ConfigPanel } from './components/ConfigPanel'
import { Toolbar } from './components/Toolbar'
import { BottomPanel, BOTTOM_HEADER_HEIGHT, BOTTOM_MIN_HEIGHT } from './components/BottomPanel'

const BOTTOM_HEIGHT_KEY = 'pipeline-bottom-panel-height'
const BOTTOM_DEFAULT_HEIGHT = 200
import { usePaletteDrag } from './usePaletteDrag'
import { DragGhost } from './components/DragGhost'

/**
 * 연결선 생성 — 소켓(부품) 연결은 점선·화살표 없음, 일반 실행 흐름은 실선 화살표.
 * sourceHandle: 출력이 여러 개인 노드(분기·확인 요청)에서 나온 화살표면 그 출력 id
 */
/** 분기 화살표가 아직 있는 출력에서 나오는지 (규칙을 지우면 그 화살표는 버림) */
function validOutput(e: Edge, nodes: PipelineFlowNode[]): boolean {
  if (e.targetHandle || !e.sourceHandle) return true
  const n = nodes.find((x) => x.id === e.source)
  if (!n) return false
  const outs = nodeOutputs(n.data.nodeType, n.data.config)
  return outs.length === 0 || outs.some((o) => o.id === e.sourceHandle)
}

function makeEdge(source: string, target: string, targetHandle?: string | null, sourceHandle?: string | null): Edge {
  if (targetHandle) {
    return {
      id: `${source}-${target}-${targetHandle}`,
      source,
      target,
      sourceHandle: PART_HANDLE,
      targetHandle,
      className: 'edge-socket',
      style: { strokeDasharray: '5 4', stroke: '#8B8B8B' },
    }
  }
  if (sourceHandle) {
    return {
      id: `${source}-${sourceHandle}-${target}`,
      source,
      target,
      sourceHandle,
      markerEnd: { type: MarkerType.ArrowClosed },
      className: 'edge-branch',
    }
  }
  return { id: `${source}-${target}`, source, target, markerEnd: { type: MarkerType.ArrowClosed } }
}

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

/** 지워졌거나 아직 가져오지 않은 모듈로 만든 노드 — 화면이 깨지지 않게 자리 표시 (저장하면 원래 타입 그대로 남음) */
function missingType(id: string): NodeTypeInfo {
  return {
    id, label: '없는 모듈 (' + id + ')', icon: '❓', color: '#6B7280', category: null,
    params: [{ name: 'name', label: '노드 이름', type: 'text', defaultValue: '' }],
    trigger: false, sockets: [], provides: null,
  } as unknown as NodeTypeInfo
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
  const [runMode, setRunMode] = useState<RunMode>('manual')
  const [bottomPanelOpen, setBottomPanelOpen] = useState(false)
  const [bottomCollapsed, setBottomCollapsed] = useState(false)
  const [bottomHeight, setBottomHeight] = useState<number>(() => {
    try {
      const v = Number(localStorage.getItem(BOTTOM_HEIGHT_KEY))
      return v >= BOTTOM_MIN_HEIGHT && v <= 2000 ? v : BOTTOM_DEFAULT_HEIGHT
    } catch {
      return BOTTOM_DEFAULT_HEIGHT
    }
  })
  const handleBottomHeight = useCallback((h: number, done: boolean) => {
    setBottomHeight(h)
    if (done) {
      try { localStorage.setItem(BOTTOM_HEIGHT_KEY, String(h)) } catch { /* 무시 */ }
    }
  }, [])
  const resetBottomHeight = useCallback(() => {
    setBottomHeight(BOTTOM_DEFAULT_HEIGHT)
    try { localStorage.removeItem(BOTTOM_HEIGHT_KEY) } catch { /* 무시 */ }
  }, [])
  // 툴바(하단 중앙)가 패널에 가리지 않도록 패널 위로 띄우는 거리
  const toolbarBottom = bottomPanelOpen ? (bottomCollapsed ? BOTTOM_HEADER_HEIGHT : bottomHeight) + 20 : 20
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
        data: { nodeType: typeMap.get(n.type) ?? missingType(n.type), config: n.config },
      })),
    )
    setEdges(pipeline.connections.map((c) => makeEdge(c.fromNodeId, c.toNodeId, c.toHandle, c.fromHandle)))
  }

  /** 실행 하나를 폴링하며 하단 패널/노드 상태에 반영 */
  function followExecution(execId: string, mode: RunMode) {
    if (execIntervalRef.current) {
      window.clearInterval(execIntervalRef.current)
      execIntervalRef.current = null
    }
    setRunLogs([])
    setRunStatus('running')
    setRunMode(mode)
    setBottomPanelOpen(true)

    const poll = async () => {
      try {
        const state = await api.getExecution(execId)
        setRunLogs(state.logs)
        if (state.status !== 'running') {
          setRunStatus(state.status as 'done' | 'error')
          if (execIntervalRef.current) window.clearInterval(execIntervalRef.current)
          execIntervalRef.current = null
        }
      } catch {
        setRunStatus('error')
        if (execIntervalRef.current) window.clearInterval(execIntervalRef.current)
        execIntervalRef.current = null
      }
    }
    poll()
    execIntervalRef.current = window.setInterval(poll, 1000)
  }

  async function startExecution(id: string) {
    try {
      const res = await api.runPipeline(id)
      if (!res.executionId) {
        setRunStatus('error')
        setBottomPanelOpen(true)
        return
      }
      followExecution(res.executionId, 'manual')
    } catch {
      setRunStatus('error')
      setBottomPanelOpen(true)
    }
  }

  // ── 웹훅 테스트 수신 감지: 트리거 노드가 있는 저장된 파이프라인이면 최근 test 실행을 폴링 ──
  const hasTrigger = nodes.some((n) => n.data.nodeType?.trigger)
  const lastSeenTestExecRef = useRef<string | null | undefined>(undefined) // undefined = 아직 기준점 없음

  useEffect(() => {
    if (!pipelineId || !hasTrigger) return
    let stopped = false

    const check = async () => {
      try {
        const latest = await api.getLatestExecution(pipelineId, 'test')
        if (stopped) return
        const latestId = latest?.executionId ?? null
        if (lastSeenTestExecRef.current === undefined) {
          // 에디터를 연 시점의 기존 실행은 무시하고 기준점만 기록
          lastSeenTestExecRef.current = latestId
          return
        }
        if (latestId && latestId !== lastSeenTestExecRef.current) {
          lastSeenTestExecRef.current = latestId
          followExecution(latestId, 'test')
        }
      } catch {
        // 일시적 오류는 다음 주기에 재시도
      }
    }

    check()
    const timer = window.setInterval(check, 1500)
    return () => {
      stopped = true
      window.clearInterval(timer)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pipelineId, hasTrigger])

  const handleAddNode = useCallback(
    (typeId: string, position: { x: number; y: number }) => {
      const nodeType = nodeTypes.find((nt) => nt.id === typeId)
      if (!nodeType) return
      const newNode: PipelineFlowNode = {
        id: crypto.randomUUID(),
        type: 'pipelineNode',
        position,
        className: 'node-enter',
        selected: true,
        data: { nodeType, config: defaultConfig(nodeType) },
      }
      // 새 노드만 선택해 바로 설정 패널을 열고, 등장 애니메이션이 끝나면 표시용 클래스 제거
      setNodes((nds) => [...nds.map((n) => (n.selected ? { ...n, selected: false } : n)), newNode])
      setSelectedNodeId(newNode.id)
      window.setTimeout(() => {
        setNodes((nds) => nds.map((n) => (n.id === newNode.id ? { ...n, className: undefined } : n)))
      }, 400)
    },
    [nodeTypes, setNodes],
  )

  const occupiedPositions = useCallback(() => nodes.map((n) => n.position), [nodes])

  const { startDrag, addAtCenter, dragTypeId, ghost, ghostRef } = usePaletteDrag(
    nodeTypes,
    canvasWrapperRef,
    handleAddNode,
    occupiedPositions,
  )

  const handleDeleteNode = useCallback(
    (id: string) => {
      setNodes((nds) => nds.filter((n) => n.id !== id))
      setEdges((eds) => eds.filter((e) => e.source !== id && e.target !== id))
      setSelectedNodeId((cur) => (cur === id ? null : cur))
    },
    [setNodes, setEdges],
  )

  // 연결 규칙: 부품 노드 → 맞는 종류의 소켓에만, 일반 노드 → 일반 입력에만
  const isValidConnection = useCallback(
    (c: Connection | Edge) => {
      if (c.source === c.target) return false
      const src = nodes.find((n) => n.id === c.source)?.data.nodeType
      const tgt = nodes.find((n) => n.id === c.target)?.data.nodeType
      if (!src || !tgt) return false
      if (c.targetHandle) {
        const socket = tgt.sockets?.find((s) => s.id === c.targetHandle)
        return !!socket && src.provides === socket.accepts
      }
      return !src.provides && !tgt.provides
    },
    [nodes],
  )

  // 화면에 그릴 연결선: 분기 화살표에 출력 이름 표시, 없어진 출력의 화살표는 숨김
  const displayEdges = useMemo(
    () =>
      edges.filter((e) => validOutput(e, nodes)).map((e) => {
        if (e.targetHandle || !e.sourceHandle) return e
        const n = nodes.find((x) => x.id === e.source)
        const label = n ? nodeOutputs(n.data.nodeType, n.data.config).find((o) => o.id === e.sourceHandle)?.label : undefined
        return label ? { ...e, label, labelBgPadding: [6, 3] as [number, number], labelBgBorderRadius: 4 } : e
      }),
    [edges, nodes],
  )

  const handleConnect = useCallback(
    (connection: Connection) => {
      if (!isValidConnection(connection)) return
      const socket = connection.targetHandle
        ? nodes.find((n) => n.id === connection.target)?.data.nodeType.sockets?.find((s) => s.id === connection.targetHandle)
        : undefined
      setEdges((eds) => {
        // 1개만 끼우는 소켓이면 기존 부품 연결을 교체
        const kept = socket && !socket.multiple
          ? eds.filter((e) => !(e.target === connection.target && e.targetHandle === connection.targetHandle))
          : eds
        return addEdge(makeEdge(connection.source, connection.target, connection.targetHandle,
          connection.targetHandle ? null : connection.sourceHandle), kept)
      })
    },
    [isValidConnection, nodes, setEdges],
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
    (nodeList: PipelineFlowNode[] = nodes): Partial<PipelineDto> => ({
      name,
      description,
      nodes: nodeList.map((n) => ({
        id: n.id,
        type: n.data.nodeType.id,
        posX: n.position.x,
        posY: n.position.y,
        config: n.data.config,
      })),
      connections: edges
        .filter((e) => validOutput(e, nodeList))
        .map((e) => ({
          fromNodeId: e.source,
          toNodeId: e.target,
          toHandle: e.targetHandle ?? null,
          fromHandle: e.targetHandle ? null : (e.sourceHandle ?? null),
        })),
    }),
    [name, description, nodes, edges],
  )

  // ── 저장 안 된 변경 감지: 마지막 저장(또는 불러온) 상태와 현재 상태 비교 ──
  const savedSnapshotRef = useRef<string | null>(null)
  const currentSnapshot = loading ? null : JSON.stringify(buildPayload())
  useEffect(() => {
    if (!loading && savedSnapshotRef.current === null) savedSnapshotRef.current = currentSnapshot
  }, [loading, currentSnapshot])
  const dirty = savedSnapshotRef.current !== null && currentSnapshot !== savedSnapshotRef.current

  // 저장 안 한 채 페이지를 떠나면 브라우저 확인창
  useEffect(() => {
    if (!dirty) return
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [dirty])

  // 💬 도우미(assistant.js)가 이 파이프라인을 바꾸거나 지우면: 변경 없으면 바로 다시 불러오고, 편집 중이면 알림 띄우기
  const [externalChange, setExternalChange] = useState<'updated' | 'deleted' | null>(null)
  const reloadFromServer = useCallback(async () => {
    if (!pipelineId) return
    const p = await api.getPipeline(pipelineId)
    applyPipeline(p, nodeTypes)
    savedSnapshotRef.current = null // 다시 불러온 상태를 새 기준으로
    setExternalChange(null)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pipelineId, nodeTypes])
  useEffect(() => {
    const onChange = (detail: { id: string; type: string }[]) => {
      const mine = detail.find((d) => d.id === pipelineId)
      if (!mine) return
      if (mine.type === 'deleted') setExternalChange('deleted')
      else if (dirty) setExternalChange('updated')
      else reloadFromServer()
    }
    const onWindow = (e: Event) => onChange((e as CustomEvent).detail ?? [])
    window.addEventListener('workflow-pipelines-changed', onWindow)
    let bc: BroadcastChannel | null = null
    try {
      bc = new BroadcastChannel('workflow-pipelines')
      bc.onmessage = (e) => onChange(e.data ?? [])
    } catch { /* 지원 안 하는 브라우저 */ }
    return () => {
      window.removeEventListener('workflow-pipelines-changed', onWindow)
      bc?.close()
    }
  }, [pipelineId, dirty, reloadFromServer])

  // 탭 제목 = 파이프라인 이름 (열린 탭 관리 패널에도 이 이름으로 표시)
  useEffect(() => {
    document.title = `${name || '새 파이프라인'} · 파이프라인 — worKBench`
  }, [name])

  const handleSave = useCallback(async (nodeList?: PipelineFlowNode[]): Promise<string> => {
    setSaving(true)
    try {
      const payload = buildPayload(nodeList)
      let id: string
      if (pipelineId) {
        await api.updatePipeline(pipelineId, payload)
        id = pipelineId
      } else {
        const created = await api.createPipeline(payload)
        setPipelineId(created.id)
        window.history.replaceState(null, '', `/pipelines/${created.id}`)
        id = created.id
      }
      savedSnapshotRef.current = JSON.stringify(payload)
      return id
    } finally {
      setSaving(false)
    }
  }, [pipelineId, buildPayload])

  const handleBackToList = useCallback(() => {
    window.location.href = '/pipelines' // 저장 안 된 변경이 있으면 beforeunload 확인창
  }, [])

  // 웹훅 노드가 있으면 ▶ 버튼 = 수신 대기 ON/OFF (테스트 URL 켜짐/꺼짐과 같은 상태)
  const webhookNodes = nodes.filter((n) => n.data.nodeType?.id === WEBHOOK_TYPE)
  const listening = webhookNodes.some((n) => n.data.config.testEnabled === ON)

  const handleToggleListen = useCallback(async () => {
    const next = listening ? OFF : ON
    const updated = nodes.map((n) =>
      n.data.nodeType?.id === WEBHOOK_TYPE
        ? { ...n, data: { ...n.data, config: { ...n.data.config, testEnabled: next } } }
        : n,
    )
    setNodes(updated)
    const id = await handleSave(updated) // 서버는 저장된 설정으로 URL 켜짐 여부를 판단
    setPipelineId(id)
    if (next === ON) {
      // 이전 실행 결과는 지우고 '수신 대기 중' 상태로 하단 패널 표시
      if (execIntervalRef.current) window.clearInterval(execIntervalRef.current)
      execIntervalRef.current = null
      setRunLogs([])
      setRunStatus('idle')
      setRunMode('test')
      setBottomPanelOpen(true)
    }
  }, [listening, nodes, setNodes, handleSave])

  const handleRun = useCallback(async () => {
    if (webhookNodes.length > 0) {
      await handleToggleListen()
      return
    }
    const id = await handleSave()
    setPipelineId(id)
    await startExecution(id)
  }, [handleSave, handleToggleListen, webhookNodes.length])  // eslint-disable-line react-hooks/exhaustive-deps

  /** 수신 기록에서 특정 실행 다시 보기 */
  const handleShowExecution = useCallback((execId: string, mode: RunMode) => {
    followExecution(execId, mode)
  }, [])  // eslint-disable-line react-hooks/exhaustive-deps

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
      {externalChange && (
        <div className="external-change">
          {externalChange === 'deleted' ? (
            <>
              <span>🗑 도우미가 이 파이프라인을 삭제했습니다 (⚙ 설정 → 백업에서 되살릴 수 있음)</span>
              <button type="button" onClick={handleBackToList}>목록으로</button>
            </>
          ) : (
            <>
              <span>💬 도우미가 이 파이프라인을 수정했습니다 — 지금 화면엔 저장 안 한 변경이 있습니다</span>
              <button type="button" onClick={reloadFromServer}>도우미 버전 불러오기</button>
              <button type="button" className="ghost" onClick={() => setExternalChange(null)}>내 편집 유지</button>
            </>
          )}
        </div>
      )}
      <div className="editor-main">
        <Palette
          nodeTypes={nodeTypes}
          draggingTypeId={dragTypeId}
          onStartDrag={startDrag}
          onAddAtCenter={addAtCenter}
          onBack={handleBackToList}
        />
        <PipelineCanvas
          nodes={nodes}
          edges={displayEdges}
          wrapperRef={canvasWrapperRef}
          dragOver={!!ghost?.overCanvas}
          runStatusMap={runStatusMap}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={handleConnect}
          isValidConnection={isValidConnection}
          onSelectNode={setSelectedNodeId}
          onDeleteNode={handleDeleteNode}
        />
        {ghost && <DragGhost ghost={ghost} ghostRef={ghostRef} />}
        <ConfigPanel
          nodeId={selectedNode?.id ?? null}
          pipelineId={pipelineId}
          dirty={dirty}
          nodeType={selectedNode?.data.nodeType ?? null}
          config={selectedNode?.data.config ?? {}}
          onChange={handleConfigChange}
          runLog={selectedNodeLog}
          onShowExecution={handleShowExecution}
        />
      </div>

      {/* 하단 실행 결과 패널 */}
      {bottomPanelOpen && (
        <BottomPanel
          logs={runLogs}
          status={runStatus}
          mode={runMode}
          listening={listening}
          nodeTypes={nodeTypes}
          nodeNames={new Map(nodes.map((n) => [n.id, nodeDisplayName(n.data.nodeType, n.data.config)]))}
          onSelectNode={handleSelectNodeFromPanel}
          onClose={() => setBottomPanelOpen(false)}
          height={bottomHeight}
          onHeightChange={handleBottomHeight}
          onResetHeight={resetBottomHeight}
          collapsed={bottomCollapsed}
          onToggleCollapse={() => setBottomCollapsed((c) => !c)}
        />
      )}

      <Toolbar
        name={name}
        description={description}
        onNameChange={setName}
        onDescriptionChange={setDescription}
        onSave={() => handleSave()}
        dirty={dirty}
        onRun={handleRun}
        onExport={handleExport}
        onImport={handleImport}
        onDeleteAll={handleDeleteAll}
        saving={saving}
        canExport={!!pipelineId}
        bottomOffset={toolbarBottom}
        runLabel={webhookNodes.length === 0 ? '▶ 실행' : listening ? '■ 중지' : '▶ 수신 대기'}
        runActive={listening}
      />
    </div>
  )
}
