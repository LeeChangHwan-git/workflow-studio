import { useEffect, useRef, useState } from 'react'

interface Props {
  name: string
  description: string
  onNameChange: (v: string) => void
  onDescriptionChange: (v: string) => void
  onSave: () => void
  onRun: () => void
  onExport: () => void
  onImport: (file: File) => void
  onDeleteAll: () => void
  saving: boolean
  canExport: boolean
  bottomPanelOpen?: boolean
  /** 실행 버튼 문구 (트리거 파이프라인이면 '▶ 수신 대기' / '■ 중지') */
  runLabel?: string
  /** 수신 대기 중 → 실행 버튼 초록 강조 */
  runActive?: boolean
  /** 저장 안 된 변경 있음 → 저장 버튼 강조 */
  dirty?: boolean
}

const POS_KEY = 'pipeline-toolbar-pos'
const SCALE_KEY = 'pipeline-toolbar-scale'
const MIN_SCALE = 0.6
const MAX_SCALE = 1.3

function loadPos(): { left: number; top: number } | null {
  try {
    const raw = localStorage.getItem(POS_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function loadScale(): number {
  const raw = Number(localStorage.getItem(SCALE_KEY))
  return raw >= MIN_SCALE && raw <= MAX_SCALE ? raw : 1
}

export function Toolbar({
  name,
  description,
  onNameChange,
  onDescriptionChange,
  onSave,
  onRun,
  onExport,
  onImport,
  onDeleteAll,
  saving,
  canExport,
  bottomPanelOpen,
  runLabel = '▶ 실행',
  runActive = false,
  dirty = false,
}: Props) {
  const importInputRef = useRef<HTMLInputElement>(null)
  const toolbarRef = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ left: number; top: number } | null>(loadPos)
  const [scale, setScale] = useState<number>(loadScale)

  const startDrag = (e: React.MouseEvent) => {
    e.preventDefault()
    const rect = toolbarRef.current!.getBoundingClientRect()
    const offsetX = e.clientX - rect.left
    const offsetY = e.clientY - rect.top

    const onMove = (ev: MouseEvent) => {
      setPos({ left: ev.clientX - offsetX, top: ev.clientY - offsetY })
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      setPos((p) => {
        if (p) localStorage.setItem(POS_KEY, JSON.stringify(p))
        return p
      })
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault()
    e.stopPropagation()
    const startX = e.clientX
    const startScale = scale

    const onMove = (ev: MouseEvent) => {
      const delta = (ev.clientX - startX) / 250
      const next = Math.min(MAX_SCALE, Math.max(MIN_SCALE, startScale + delta))
      setScale(next)
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      setScale((s) => {
        localStorage.setItem(SCALE_KEY, String(s))
        return s
      })
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  const resetLayout = () => {
    setPos(null)
    setScale(1)
    localStorage.removeItem(POS_KEY)
    localStorage.removeItem(SCALE_KEY)
  }

  // 창 크기가 바뀌어 툴바가 화면 밖으로 나가는 것을 방지
  useEffect(() => {
    if (!pos) return
    const clamp = () => {
      const rect = toolbarRef.current?.getBoundingClientRect()
      if (!rect) return
      const maxLeft = window.innerWidth - rect.width
      const maxTop = window.innerHeight - rect.height
      setPos((p) =>
        p
          ? {
              left: Math.min(Math.max(0, p.left), Math.max(0, maxLeft)),
              top: Math.min(Math.max(0, p.top), Math.max(0, maxTop)),
            }
          : p
      )
    }
    window.addEventListener('resize', clamp)
    return () => window.removeEventListener('resize', clamp)
  }, [pos])

  const style: React.CSSProperties = pos
    ? {
        left: pos.left,
        top: pos.top,
        bottom: 'auto',
        transform: `scale(${scale})`,
        transformOrigin: 'top left',
      }
    : {
        bottom: bottomPanelOpen ? 220 : 20,
        transform: `translateX(-50%) scale(${scale})`,
        transformOrigin: 'bottom center',
      }

  return (
    <div className="editor-toolbar" ref={toolbarRef} style={style}>
      <div
        className="toolbar-drag-handle"
        onMouseDown={startDrag}
        onDoubleClick={resetLayout}
        title="드래그해서 위치 이동 (더블클릭: 초기화)"
      >
        ⠿
      </div>
      <input
        className="pipeline-name-input"
        placeholder="파이프라인 이름"
        value={name}
        onChange={(e) => onNameChange(e.target.value)}
      />
      <input
        className="pipeline-desc-input"
        placeholder="설명 (선택)"
        value={description}
        onChange={(e) => onDescriptionChange(e.target.value)}
      />
      <button
        type="button"
        className={`btn${dirty ? ' btn-dirty' : ''}`}
        onClick={onSave}
        disabled={saving}
        title={dirty ? '저장하지 않은 변경이 있습니다' : '저장됨'}
      >
        💾 저장{dirty && <span className="dirty-dot">●</span>}
      </button>
      <button type="button" className={`btn${runActive ? ' btn-live' : ''}`} onClick={onRun}>
        {runLabel}
      </button>
      <button type="button" className="btn" onClick={onExport} disabled={!canExport}>
        ⬇ 내보내기
      </button>
      <button type="button" className="btn" onClick={() => importInputRef.current?.click()}>
        ⬆ 가져오기
      </button>
      <input
        ref={importInputRef}
        type="file"
        accept=".zip"
        style={{ display: 'none' }}
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) onImport(file)
          e.target.value = ''
        }}
      />
      <button type="button" className="btn btn-danger-soft" onClick={onDeleteAll}>
        🗑 전체삭제
      </button>
      <div
        className="toolbar-resize-handle"
        onMouseDown={startResize}
        onDoubleClick={resetLayout}
        title="드래그해서 크기 조절 (더블클릭: 초기화)"
      >
        ⤡
      </div>
    </div>
  )
}
