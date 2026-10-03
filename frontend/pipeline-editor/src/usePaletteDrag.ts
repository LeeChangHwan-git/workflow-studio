import { useCallback, useEffect, useRef, useState } from 'react'
import { useReactFlow } from '@xyflow/react'
import type { NodeTypeInfo } from './types'

/** 캔버스 배경 점 간격과 같은 격자 — 놓을 때 이 간격에 맞춰 정렬 */
export const GRID = 24
/** 이만큼 움직여야 드래그 시작 (클릭과 구분) */
const DRAG_THRESHOLD = 4
/** 커서가 잡는 카드 위치 (가로 가운데, 세로 위쪽 35%) */
const GRAB = { x: 0.5, y: 0.35 }
/** 측정 전 카드 크기 추정값 */
const FALLBACK = { w: 180, h: 64 }

type Pending = { typeId: string; x: number; y: number }

export interface GhostState {
  nodeType: NodeTypeInfo
  /** 카드 왼쪽 위 (화면 좌표) — 커서를 따라다님 */
  left: number
  top: number
  /** 캔버스 위면 현재 확대 배율, 밖이면 0.9 */
  scale: number
  overCanvas: boolean
  /** 캔버스 위일 때 실제로 놓일 자리 (격자 정렬, 화면 좌표) */
  slot: { left: number; top: number } | null
}

/**
 * 팔레트 → 캔버스 드래그 (mouse 이벤트 직접 처리).
 * - 실제 노드 카드 모양의 미리보기가 따라다니고, 캔버스 위에서는 확대 배율·격자에 맞춰 '놓일 자리'에 그대로 표시
 * - 4px 이상 움직여야 드래그 시작, Esc 로 취소
 * - addAtCenter: 더블클릭 시 화면 가운데(겹치면 비켜서)에 추가
 */
export function usePaletteDrag(
  nodeTypes: NodeTypeInfo[],
  canvasRef: React.RefObject<HTMLElement | null>,
  onAdd: (typeId: string, position: { x: number; y: number }) => void,
  occupied: () => { x: number; y: number }[],
) {
  const { screenToFlowPosition, flowToScreenPosition, getZoom } = useReactFlow()
  const pendingRef = useRef<Pending | null>(null)
  const [dragTypeId, setDragTypeId] = useState<string | null>(null)
  const [cursor, setCursor] = useState<{ x: number; y: number } | null>(null)
  const [overCanvas, setOverCanvas] = useState(false)
  const ghostRef = useRef<HTMLDivElement | null>(null)

  const size = useCallback(() => {
    const el = ghostRef.current
    return el && el.offsetWidth > 0 ? { w: el.offsetWidth, h: el.offsetHeight } : FALLBACK
  }, [])

  /** 커서 위치 → 격자에 맞춘 노드 왼쪽 위 (캔버스 좌표) */
  const dropPosition = useCallback(
    (x: number, y: number) => {
      const p = screenToFlowPosition({ x, y })
      const s = size()
      return {
        x: Math.round((p.x - s.w * GRAB.x) / GRID) * GRID,
        y: Math.round((p.y - s.h * GRAB.y) / GRID) * GRID,
      }
    },
    [screenToFlowPosition, size],
  )

  const isOverCanvas = useCallback(
    (x: number, y: number) => {
      const r = canvasRef.current?.getBoundingClientRect()
      return !!r && x >= r.left && x <= r.right && y >= r.top && y <= r.bottom
    },
    [canvasRef],
  )

  const cancel = useCallback(() => {
    pendingRef.current = null
    setDragTypeId(null)
    setCursor(null)
    setOverCanvas(false)
    document.body.classList.remove('palette-dragging')
  }, [])

  const startDrag = useCallback((typeId: string, e: React.MouseEvent) => {
    if (e.button !== 0) return
    e.preventDefault()
    pendingRef.current = { typeId, x: e.clientX, y: e.clientY }
  }, [])

  useEffect(() => {
    function handleMove(e: MouseEvent) {
      const pending = pendingRef.current
      if (!pending) return
      if (!dragTypeId) {
        if (Math.hypot(e.clientX - pending.x, e.clientY - pending.y) < DRAG_THRESHOLD) return
        setDragTypeId(pending.typeId)
        document.body.classList.add('palette-dragging')
      }
      setCursor({ x: e.clientX, y: e.clientY })
      setOverCanvas(isOverCanvas(e.clientX, e.clientY))
    }

    function handleUp(e: MouseEvent) {
      const pending = pendingRef.current
      if (!pending) return
      if (dragTypeId && isOverCanvas(e.clientX, e.clientY)) {
        onAdd(dragTypeId, dropPosition(e.clientX, e.clientY))
      }
      cancel()
    }

    function handleKey(e: KeyboardEvent) {
      if (e.key === 'Escape' && pendingRef.current) cancel()
    }

    window.addEventListener('mousemove', handleMove)
    window.addEventListener('mouseup', handleUp)
    window.addEventListener('keydown', handleKey)
    return () => {
      window.removeEventListener('mousemove', handleMove)
      window.removeEventListener('mouseup', handleUp)
      window.removeEventListener('keydown', handleKey)
    }
  }, [dragTypeId, isOverCanvas, onAdd, dropPosition, cancel])

  /** 더블클릭 → 보이는 캔버스 가운데에 추가 (같은 자리에 노드가 있으면 격자만큼 비켜서) */
  const addAtCenter = useCallback(
    (typeId: string) => {
      const r = canvasRef.current?.getBoundingClientRect()
      if (!r) return
      const base = dropPosition(r.left + r.width / 2, r.top + r.height / 2)
      const taken = occupied()
      const pos = { ...base }
      for (let i = 0; i < 30 && taken.some((t) => Math.abs(t.x - pos.x) < GRID * 2 && Math.abs(t.y - pos.y) < GRID * 2); i++) {
        pos.x += GRID * 2
        pos.y += GRID * 2
      }
      onAdd(typeId, pos)
    },
    [canvasRef, dropPosition, occupied, onAdd],
  )

  // 미리보기: 카드는 커서를 따라다니고(캔버스 위에선 확대 배율과 같은 크기), 놓일 자리는 격자에 맞춘 점선 슬롯으로 표시
  let ghost: GhostState | null = null
  const nodeType = nodeTypes.find((nt) => nt.id === dragTypeId)
  if (dragTypeId && cursor && nodeType) {
    const s = size()
    const scale = overCanvas ? getZoom() : 0.9
    let slot: GhostState['slot'] = null
    if (overCanvas) {
      const p = flowToScreenPosition(dropPosition(cursor.x, cursor.y))
      slot = { left: p.x, top: p.y }
    }
    ghost = {
      nodeType,
      left: cursor.x - s.w * GRAB.x * scale,
      top: cursor.y - s.h * GRAB.y * scale,
      scale,
      overCanvas,
      slot,
    }
  }

  return { startDrag, addAtCenter, dragTypeId, ghost, ghostRef }
}
