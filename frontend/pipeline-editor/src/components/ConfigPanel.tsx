import { useRef, useState } from 'react'
import type { NodeParam, NodeTypeInfo } from '../types'
import { api } from '../api'

interface Props {
  nodeType: NodeTypeInfo | null
  config: Record<string, unknown>
  onChange: (key: string, value: unknown) => void
}

export function ConfigPanel({ nodeType, config, onChange }: Props) {
  if (!nodeType) {
    return (
      <div className="config-panel">
        <div className="config-empty">
          <div className="config-empty-icon">🧩</div>
          <div>노드를 선택하면 설정이 표시됩니다</div>
        </div>
      </div>
    )
  }

  return (
    <div className="config-panel">
      <div className="config-header">{nodeType.icon} {nodeType.label}</div>
      <div className="config-body">
        {nodeType.params.map((param) => (
          <ParamField
            key={param.name}
            param={param}
            value={config[param.name] ?? param.defaultValue ?? ''}
            onChange={(v) => onChange(param.name, v)}
          />
        ))}
      </div>
    </div>
  )
}

function ParamField({
  param,
  value,
  onChange,
}: {
  param: NodeParam
  value: unknown
  onChange: (v: unknown) => void
}) {
  if (param.type === 'image') {
    return <ImageField param={param} value={value as string} onChange={onChange} />
  }

  return (
    <div className="cfg-group">
      <label className="cfg-label">{param.label}</label>
      {param.type === 'textarea' && (
        <textarea
          className="cfg-textarea"
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
      {param.type === 'select' && (
        <select
          className="cfg-select"
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
        >
          {(param.options ?? []).map((opt) => (
            <option key={opt} value={opt}>
              {opt}
            </option>
          ))}
        </select>
      )}
      {param.type === 'number' && (
        <input
          className="cfg-input"
          type="number"
          value={value === '' || value == null ? '' : Number(value)}
          onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
        />
      )}
      {param.type === 'text' && (
        <input
          className="cfg-input"
          type="text"
          value={String(value ?? '')}
          onChange={(e) => onChange(e.target.value)}
        />
      )}
    </div>
  )
}

function ImageField({
  param,
  value,
  onChange,
}: {
  param: NodeParam
  value: string
  onChange: (v: string) => void
}) {
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)

  const handleUpload = async (file: File) => {
    setBusy(true)
    try {
      const { imagePath } = await api.uploadImage(file)
      onChange(imagePath)
    } finally {
      setBusy(false)
    }
  }

  const handleCapture = async () => {
    setBusy(true)
    try {
      const result = await api.captureRegion()
      if (result.imagePath) onChange(result.imagePath)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="cfg-group">
      <label className="cfg-label">{param.label}</label>
      <div className="cfg-img-wrap">
        {value && <img className="cfg-img-preview visible" src={api.imageUrl(value)} alt="" />}
        <div className="cfg-img-btns">
          <button type="button" className="cfg-upload-btn" onClick={() => fileInputRef.current?.click()} disabled={busy}>
            📁 업로드
          </button>
          <button type="button" className="cfg-capture-btn" onClick={handleCapture} disabled={busy}>
            📷 화면 캡처
          </button>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          accept="image/*"
          className="cfg-upload-input"
          onChange={(e) => {
            const file = e.target.files?.[0]
            if (file) handleUpload(file)
            e.target.value = ''
          }}
        />
      </div>
    </div>
  )
}
