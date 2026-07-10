import { useRef } from 'react'

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
}: Props) {
  const importInputRef = useRef<HTMLInputElement>(null)

  return (
    <div className="editor-toolbar" style={{ bottom: bottomPanelOpen ? 220 : 20 }}>
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
      <button type="button" className="btn" onClick={onSave} disabled={saving}>
        💾 저장
      </button>
      <button type="button" className="btn" onClick={onRun}>
        ▶ 실행
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
    </div>
  )
}
