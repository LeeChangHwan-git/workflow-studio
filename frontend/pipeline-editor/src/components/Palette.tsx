import { useMemo, useState } from 'react'
import type { NodeTypeInfo } from '../types'

interface Props {
  nodeTypes: NodeTypeInfo[]
  /** 지금 끌고 있는 노드 타입 (팔레트 항목 강조용) */
  draggingTypeId: string | null
  onStartDrag: (typeId: string, e: React.MouseEvent) => void
  /** 더블클릭 → 캔버스 가운데에 추가 */
  onAddAtCenter: (typeId: string) => void
  /** 파이프라인 목록으로 이동 */
  onBack: () => void
}

// 그룹 표시 순서 (여기 없는 그룹은 모듈 등록 순서대로 뒤에 붙음)
const CATEGORY_ORDER: string[] = ['트리거', '흐름', 'AI', 'AI 부품', '통합', '파일']

export function Palette({ nodeTypes, draggingTypeId, onStartDrag, onAddAtCenter, onBack }: Props) {
  const [query, setQuery] = useState('')
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({})

  const groups = useMemo(() => {
    const q = query.trim().toLowerCase()
    const visible = q
      ? nodeTypes.filter(
          (nt) => nt.label.toLowerCase().includes(q) || nt.id.toLowerCase().includes(q),
        )
      : nodeTypes.filter((nt) => nt.category) // category=null은 팔레트 미노출

    const map = new Map<string, NodeTypeInfo[]>()
    for (const nt of visible) {
      const cat = nt.category ?? '기타'
      if (!map.has(cat)) map.set(cat, [])
      map.get(cat)!.push(nt)
    }

    // 지정 순서대로 정렬
    const sorted = new Map<string, NodeTypeInfo[]>()
    for (const cat of CATEGORY_ORDER) {
      if (map.has(cat)) sorted.set(cat, map.get(cat)!)
    }
    for (const [cat, items] of map) {
      if (!sorted.has(cat)) sorted.set(cat, items)
    }
    return sorted
  }, [nodeTypes, query])

  const toggle = (cat: string) =>
    setCollapsed((prev) => ({ ...prev, [cat]: !prev[cat] }))

  return (
    <div className="palette">
      <button type="button" className="palette-back" onClick={onBack} title="파이프라인 목록으로">
        <span className="palette-back-arrow">←</span> 파이프라인 목록
      </button>
      <div className="palette-title">노드</div>
      <input
        className="palette-search"
        type="text"
        placeholder="검색..."
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {groups.size === 0 ? (
        <div className="palette-empty">
          {query.trim() ? '검색 결과가 없습니다' : '등록된 노드 모듈이 없습니다'}
        </div>
      ) : (
        Array.from(groups.entries()).map(([cat, items]) => (
          <div key={cat} className="palette-group">
            <div className="palette-group-header" onClick={() => toggle(cat)}>
              <span className="palette-group-arrow">{collapsed[cat] ? '▶' : '▼'}</span>
              <span className="palette-group-name">{cat}</span>
              <span className="palette-group-count">{items.length}</span>
            </div>
            {!collapsed[cat] &&
              items.map((nt) => (
                <div
                  key={nt.id}
                  className={`palette-item${draggingTypeId === nt.id ? ' dragging' : ''}`}
                  style={{ '--node-color': nt.color } as React.CSSProperties}
                  title="캔버스로 끌어다 놓기 · 더블클릭하면 가운데에 추가"
                  onMouseDown={(e) => onStartDrag(nt.id, e)}
                  onDoubleClick={() => onAddAtCenter(nt.id)}
                >
                  <span className="palette-icon">{nt.icon}</span>
                  <span className="palette-label">{nt.label}</span>
                  <span className="palette-grip" aria-hidden>⋮⋮</span>
                </div>
              ))}
          </div>
        ))
      )}
    </div>
  )
}
