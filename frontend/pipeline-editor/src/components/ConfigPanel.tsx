import { useEffect, useRef, useState } from 'react'
import { nodeDisplayName, WEBHOOK_TYPE } from '../types'
import type { NodeParam, NodeTypeInfo, RunLog, RunMode } from '../types'
import { api } from '../api'
import { OutputRenderer } from './OutputRenderer'
import { WebhookHistory } from './WebhookHistory'
import { WebhookTemplateField } from './WebhookTemplateField'

interface Props {
  nodeId: string | null
  nodeType: NodeTypeInfo | null
  config: Record<string, unknown>
  onChange: (key: string, value: unknown) => void
  runLog?: RunLog
  onShowExecution: (execId: string, mode: RunMode) => void
}

type PanelTab = 'config' | 'result' | 'history'

// ── 패널 너비 (왼쪽 가장자리 드래그로 조절, localStorage 저장) ──
const WIDTH_KEY = 'pipeline-config-panel-width'
const DEFAULT_WIDTH = 300
const MIN_WIDTH = 240
const MAX_WIDTH = 900

function loadWidth(): number {
  try {
    const w = Number(localStorage.getItem(WIDTH_KEY))
    return w >= MIN_WIDTH && w <= MAX_WIDTH ? w : DEFAULT_WIDTH
  } catch {
    return DEFAULT_WIDTH
  }
}

export function ConfigPanel({ nodeId, nodeType, config, onChange, runLog, onShowExecution }: Props) {
  const [tab, setTab] = useState<PanelTab>('config')
  const [width, setWidth] = useState<number>(loadWidth)
  const prevNodeId = useRef<string | null>(null)

  // 선택 노드가 바뀌면 'config' 탭으로 리셋
  useEffect(() => {
    if (nodeId !== prevNodeId.current) {
      prevNodeId.current = nodeId
      setTab('config')
    }
  }, [nodeId])

  const startResize = (e: React.MouseEvent) => {
    e.preventDefault()
    const startX = e.clientX
    const startW = width
    document.body.classList.add('resizing-col')
    const onMove = (ev: MouseEvent) => {
      // 왼쪽으로 끌수록 넓어짐, 화면의 70%를 넘지 않게
      const max = Math.min(MAX_WIDTH, window.innerWidth * 0.7)
      setWidth(Math.round(Math.min(max, Math.max(MIN_WIDTH, startW + (startX - ev.clientX)))))
    }
    const onUp = () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
      document.body.classList.remove('resizing-col')
      setWidth((w) => {
        try { localStorage.setItem(WIDTH_KEY, String(w)) } catch { /* 저장 실패 무시 */ }
        return w
      })
    }
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
  }

  const resetWidth = () => {
    setWidth(DEFAULT_WIDTH)
    try { localStorage.removeItem(WIDTH_KEY) } catch { /* 무시 */ }
  }

  const panelStyle = { width, minWidth: width }
  const resizer = (
    <div
      className="config-resizer"
      onMouseDown={startResize}
      onDoubleClick={resetWidth}
      title="드래그해서 패널 너비 조절 (더블클릭: 초기화)"
    />
  )

  if (!nodeType) {
    return (
      <div className="config-panel" style={panelStyle}>
        {resizer}
        <div className="config-empty">
          <div className="config-empty-icon">🧩</div>
          <div>노드를 선택하면 설정이 표시됩니다</div>
        </div>
      </div>
    )
  }

  const hasResult = !!(runLog?.output)
  const isWebhook = nodeType.id === WEBHOOK_TYPE

  return (
    <div className="config-panel" style={panelStyle}>
      {resizer}
      {/* 헤더 */}
      <div className="config-header">
        {nodeType.icon} {nodeDisplayName(nodeType, config)}
        {nodeDisplayName(nodeType, config) !== nodeType.label && (
          <span className="config-header-type">{nodeType.label}</span>
        )}
      </div>

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
        {isWebhook && (
          <button
            type="button"
            className={`config-tab${tab === 'history' ? ' active' : ''}`}
            onClick={() => setTab('history')}
          >
            수신 기록
          </button>
        )}
      </div>

      {/* 수신 기록 탭 (웹훅) */}
      {tab === 'history' && isWebhook && nodeId && (
        <WebhookHistory
          nodeId={nodeId}
          outputVar={String(config.outputVar ?? 'webhook')}
          onShowExecution={onShowExecution}
        />
      )}

      {/* 설정 탭 */}
      {tab === 'config' && (
        <div className="config-body">
          {nodeType.params.map((param) =>
            param.type === 'webhookUrls' ? (
              <WebhookUrls key={param.name} label={param.label} nodeId={nodeId!} config={config} />
            ) : param.type === 'webhookTemplate' ? (
              <WebhookTemplateField
                key={param.name}
                label={param.label}
                nodeId={nodeId!}
                value={String(config[param.name] ?? '')}
                onChange={(v) => onChange(param.name, v)}
              />
            ) : (
              <ParamField
                key={param.name}
                param={param}
                value={config[param.name] ?? param.defaultValue ?? ''}
                onChange={(v) => onChange(param.name, v)}
              />
            ),
          )}
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
      {param.type === 'password' && (
        <PasswordField value={String(value ?? '')} onChange={(v) => onChange(v)} />
      )}
    </div>
  )
}

/** 웹훅 테스트/운영 URL 표시 + 복사. 경로 = 설정의 path, 비어 있으면 노드 ID (WebhookModule.effectivePath 와 동일 규칙) */
function WebhookUrls({
  label,
  nodeId,
  config,
}: {
  label: string
  nodeId: string
  config: Record<string, unknown>
}) {
  const [copied, setCopied] = useState<string | null>(null)
  const path = String(config.path ?? '').trim().replace(/^\/+|\/+$/g, '') || nodeId
  const rows = [
    { key: 'test', title: '테스트', url: `${window.location.origin}/webhook-test/${path}`, on: (config.testEnabled ?? '꺼짐') === '켜짐' },
    { key: 'prod', title: '운영', url: `${window.location.origin}/webhook/${path}`, on: (config.prodEnabled ?? '꺼짐') === '켜짐' },
  ]

  const copy = (key: string, text: string) => {
    const fallback = () => {
      const el = document.createElement('textarea')
      el.value = text
      document.body.appendChild(el)
      el.select()
      document.execCommand('copy')
      document.body.removeChild(el)
    }
    if (navigator.clipboard) navigator.clipboard.writeText(text).catch(fallback)
    else fallback()
    setCopied(key)
    setTimeout(() => setCopied(null), 1200)
  }

  // HTTP 테스트 화면을 새 탭으로 열기 (에디터는 그대로 두고 실시간 실행 표시를 보기 위해)
  const openHttpTester = (url: string) => {
    const configured = String(config.httpMethod ?? 'POST')
    const method = configured === '모두' ? 'POST' : configured
    const withBody = method !== 'GET' && method !== 'DELETE'
    const q = new URLSearchParams({
      from: 'webhook',
      method,
      url,
      btype: withBody ? 'json' : 'none',
      body: withBody ? '{\n  "hello": "world"\n}' : '',
    })
    window.open(`/http?${q.toString()}`, '_blank')
  }

  return (
    <div className="cfg-group">
      <label className="cfg-label">{label}</label>
      {rows.map((r) => (
        <div key={r.key} className={`wh-url-row${r.on ? '' : ' off'}`}>
          <span className={`wh-url-badge ${r.key}`}>{r.title}</span>
          <span className="wh-url-text" title={r.url}>{r.url}</span>
          <span className={`wh-url-state${r.on ? ' on' : ''}`}>{r.on ? 'ON' : 'OFF'}</span>
          <button type="button" className="wh-url-copy" onClick={() => copy(r.key, r.url)}>
            {copied === r.key ? '✓' : '복사'}
          </button>
        </div>
      ))}
      <button
        type="button"
        className="wh-test-btn"
        onClick={() => openHttpTester(rows[0].url)}
        title="HTTP 화면을 새 탭으로 열고 테스트 URL·메소드·예시 바디를 채웁니다"
      >
        🚀 HTTP로 테스트 요청 보내기
      </button>
      {!rows[0].on && (
        <div className="wh-url-warn">테스트 URL이 꺼져 있어 404가 납니다 — 먼저 ▶ 수신 대기를 누르세요.</div>
      )}
      <div className="wh-url-hint">
        아래 툴바의 <b>▶ 수신 대기</b>를 누르면 테스트 URL이 켜지고(노드 초록색), <b>■ 중지</b>로 끕니다.
        테스트 요청은 이 화면에 실행 과정이 바로 표시되고, 모든 요청은 <b>수신 기록</b> 탭에 쌓입니다.
        설정을 직접 바꿨다면 <b>저장</b>해야 반영됩니다.
      </div>
    </div>
  )
}

function PasswordField({ value, onChange }: { value: string; onChange: (v: string) => void }) {
  const [show, setShow] = useState(false)
  return (
    <div className="cfg-password-wrap">
      <input
        className="cfg-input cfg-password-input"
        type={show ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="비워두면 앱 설정값 사용"
      />
      <button
        type="button"
        className="cfg-password-toggle"
        onClick={() => setShow((s) => !s)}
        title={show ? '숨기기' : '보기'}
      >
        {show ? '🙈' : '👁️'}
      </button>
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
