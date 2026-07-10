import { useEffect, useRef, useState } from 'react'
import type { NodeParam, NodeTypeInfo, RunLog } from '../types'
import { api } from '../api'
import { OutputRenderer } from './OutputRenderer'

interface Props {
  nodeType: NodeTypeInfo | null
  config: Record<string, unknown>
  onChange: (key: string, value: unknown) => void
  runLog?: RunLog
}

type PanelTab = 'config' | 'result'

export function ConfigPanel({ nodeType, config, onChange, runLog }: Props) {
  const [tab, setTab] = useState<PanelTab>('config')
  const prevNodeId = useRef<string | null>(null)

  // 선택 노드가 바뀌면 'config' 탭으로 리셋
  useEffect(() => {
    const cur = nodeType?.id ?? null
    if (cur !== prevNodeId.current) {
      prevNodeId.current = cur
      setTab('config')
    }
  }, [nodeType?.id])

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

  const hasResult = !!(runLog?.output)

  return (
    <div className="config-panel">
      {/* 헤더 */}
      <div className="config-header">{nodeType.icon} {nodeType.label}</div>

      {/* 탭 */}
      <div className="config-tabs">
        <button
          type="button"
          className={`config-tab${tab === 'config' ? ' active' : ''}`}
          onClick={() => setTab('config')}
        >
          설정
        </button>
        <button
          type="button"
          className={`config-tab${tab === 'result' ? ' active' : ''}`}
          onClick={() => setTab('result')}
        >
          결과
          {hasResult && (
            <span className={`config-tab-dot ${runLog!.status}`} />
          )}
        </button>
      </div>

      {/* 설정 탭 */}
      {tab === 'config' && (
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
      )}

      {/* 결과 탭 */}
      {tab === 'result' && (
        <div className="config-result-body">
          {!runLog ? (
            <div className="config-result-empty">
              <div className="config-empty-icon">🚀</div>
              <div>아직 실행 결과가 없습니다.<br />파이프라인을 실행해보세요.</div>
            </div>
          ) : runLog.status === 'running' ? (
            <div className="config-result-empty">
              <div className="config-empty-icon">⏳</div>
              <div>실행 중...</div>
            </div>
          ) : runLog.status === 'error' ? (
            <div className="config-result-error">
              <div className="config-result-error-title">❌ 실행 오류</div>
              <div className="config-result-error-msg">{runLog.message}</div>
              {runLog.output && (
                <pre className="config-result-error-output">{runLog.output}</pre>
              )}
            </div>
          ) : runLog.output ? (
            <div className="config-result-output">
              <OutputRenderer text={runLog.output} outputVar={runLog.outputVar} />
            </div>
          ) : (
            <div className="config-result-empty">
              <div>출력 데이터가 없습니다.</div>
            </div>
          )}
        </div>
      )}
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
