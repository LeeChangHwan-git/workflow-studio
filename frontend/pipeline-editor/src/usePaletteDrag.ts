import { useCallback, useEffect, useRef, useState } from 'react'
import { useReactFlow } from '@xyflow/react'
import type { NodeTypeInfo } from './types'

/**
 * 팔레트 → 캔버스 드래그를 HTML5 DnD 대신 mouse 이벤트로 구현.
 * (JavaFX WebView 시절 HTML5 드래그앤드롭 미지원으로 도입한 방식 — 브라우저에서도 그대로 동작)
 */
export function usePaletteDrag(
  nodeTypes: NodeTypeInfo[],
  canvasRef: React.RefObject<HTMLElement | null>,
  onAdd: (typeId: string, position: { x: number; y: number }) => void,
) {
  const { screenToFlowPosition } = useReactFlow()
  const [dragType, setDragType] = useState<string | null>(null)
  const [ghostPos, setGhostPos] = useState<{ x: number; y: number } | null>(null)
  const [overCanvas, setOverCanvas] = useState(false)
  const dragTypeRef = useRef<string | null>(null)

  const startDrag = useCallback((typeId: string, e: React.MouseEvent) => {
    if (e.button !== 0) return
    e.preventDefault()
    dragTypeRef.current = typeId
    setDragType(typeId)
    setGhostPos({ x: e.clientX, y: e.clientY })
  }, [])

  useEffect(() => {
    function isOverCanvas(x: number, y: number) {
      const rect = canvasRef.current?.getBoundingClientRect()
      return !!rect && x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom
    }

    function handleMove(e: MouseEvent) {
      if (!dragTypeRef.current) return
      setGhostPos({ x: e.clientX, y: e.clientY })
      setOverCanvas(isOverCanvas(e.clientX, e.clientY))
    }

    function handleUp(e: MouseEvent) {
      const typeId = dragTypeRef.current
      if (!typeId) return
      if (isOverCanvas(e.clientX, e.clientY)) {
        const position = screenToFlowPosition({ x: e.clientX, y: e.clientY })
        onAdd(typeId, position)
      }
      dragTypeRef.current = null
      setDragType(null)
      setGhostPos(null)
      setOverCanvas(false)
    }

    window.addEventListener('mousemove', handleMove)
    window.addEventListener('mouseup', handleUp)
    return () => {
      window.removeEventListener('mousemove', handleMove)
      window.removeEventListener('mouseup', handleUp)
    }
  }, [canvasRef, onAdd, screenToFlowPosition])

  const nodeTypeInfo = nodeTypes.find((nt) => nt.id === dragType) ?? null

  return { startDrag, dragging: !!dragType, ghostPos, overCanvas, nodeTypeInfo }
}
