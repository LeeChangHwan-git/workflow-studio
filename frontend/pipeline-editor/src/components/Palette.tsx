import { useMemo, useState } from 'react'
import type { NodeTypeInfo } from '../types'

interface Props {
  nodeTypes: NodeTypeInfo[]
  onStartDrag: (typeId: string, e: React.MouseEvent) => void
}

const CATEGORY_ORDER = ['아웃라인', 'AI', '통합', '화면 자동화']

export function Palette({ nodeTypes, onStartDrag }: Props) {
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
      <div className="palette-title">노드</div>
      <input
        className="palette-search"
        type="text"
        placeholder="검색..."
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {groups.size === 0 ? (
        <div className="palette-empty">검색 결과가 없습니다</div>
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
                  className="palette-item"
                  onMouseDown={(e) => onStartDrag(nt.id, e)}
                >
                  <span className="palette-dot" style={{ background: nt.color }} />
                  <span>{nt.icon}</span>
                  <span>{nt.label}</span>
                </div>
              ))}
          </div>
        ))
      )}
    </div>
  )
}
