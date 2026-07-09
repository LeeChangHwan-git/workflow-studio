import { useMemo, useState } from 'react'
import type { NodeTypeInfo } from '../types'

interface Props {
  nodeTypes: NodeTypeInfo[]
  onStartDrag: (typeId: string, e: React.MouseEvent) => void
}

export function Palette({ nodeTypes, onStartDrag }: Props) {
  const [query, setQuery] = useState('')

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return nodeTypes
    return nodeTypes.filter(
      (nt) => nt.label.toLowerCase().includes(q) || nt.id.toLowerCase().includes(q),
    )
  }, [nodeTypes, query])

  return (
    <div className="palette">
      <div className="palette-title">드래그하여 추가</div>
      <input
        className="palette-search"
        type="text"
        placeholder="노드 검색..."
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {filtered.length === 0 ? (
        <div className="palette-empty">검색 결과가 없습니다</div>
      ) : (
        filtered.map((nt) => (
          <div key={nt.id} className="palette-item" onMouseDown={(e) => onStartDrag(nt.id, e)}>
            <span className="palette-dot" style={{ background: nt.color }} />
            <span>{nt.icon}</span>
            <span>{nt.label}</span>
          </div>
        ))
      )}
    </div>
  )
}
