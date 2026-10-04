import { useEffect, useRef, useState } from 'react'
import { nodeDisplayName, paramVisible, WEBHOOK_TYPE } from '../types'
import type { NodeParam, NodeTypeInfo, RunLog, RunMode } from '../types'
import { api } from '../api'
import { OutputRenderer } from './OutputRenderer'
import { WebhookHistory } from './WebhookHistory'
import { WebhookTemplateField } from './WebhookTemplateField'
import { ScheduleInfoField } from './ScheduleInfoField'
import { OutlineCollectionField, OutlineConnectionField } from './OutlineFields'

interface Props {
  nodeId: string | null
  /** 저장된 파이프라인 id (새 파이프라인이면 null) */
  pipelineId: string | null
  /** 저장 안 된 변경 있음 */
  dirty: boolean
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

export function ConfigPanel({ nodeId, pipelineId, dirty, nodeType, config, onChange, runLog, onShowExecution }: Props) {
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
          {nodeType.provides && (
            <div className="cfg-part-hint">
              🔌 부품 노드입니다 — 위쪽 연결점을 <b>AI 에이전트</b> 아래의 맞는 소켓에 끌어다 연결하세요.
            </div>
          )}
          {nodeType.params.filter((p) => paramVisible(p, config, nodeType.params)).map((param) =>
            param.type === 'geminiModel' ? (
              <GeminiModelField
                key={param.name}
                param={param}
                value={String(config[param.name] ?? param.defaultValue ?? '')}
                apiKey={String(config.apiKey ?? '')}
                onChange={(v) => onChange(param.name, v)}
              />
            ) : param.type === 'memoryInfo' ? (
              <MemoryInfoField key={param.name} label={param.label} nodeId={nodeId!} />
            ) : param.type === 'scheduleInfo' ? (
              <ScheduleInfoField
                key={param.name}
                label={param.label}
                pipelineId={pipelineId}
                nodeId={nodeId!}
                config={config}
                dirty={dirty}
                onShowExecution={onShowExecution}
              />
            ) : param.type === 'outlineConnection' ? (
              <OutlineConnectionField
                key={param.name}
                label={param.label}
                baseUrl={String(config.baseUrl ?? '')}
                apiKey={String(config.apiKey ?? '')}
              />
            ) : param.type === 'outlineCollection' ? (
              <OutlineCollectionField
                key={param.name}
                param={param}
                value={String(config[param.name] ?? param.defaultValue ?? '')}
                baseUrl={String(config.baseUrl ?? '')}
                apiKey={String(config.apiKey ?? '')}
                onChange={(v) => onChange(param.name, v)}
              />
            ) : param.type === 'httpOpen' ? (
              <HttpOpenField key={param.name} label={param.label} config={config} />
            ) : param.type === 'webhookUrls' ? (
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
  if (param.type === 'folder' || param.type === 'file') {
    return <PathField param={param} value={String(value ?? '')} onChange={onChange} kind={param.type} />
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
          autoComplete="off"
          value={value === '' || value == null ? '' : Number(value)}
          onChange={(e) => onChange(e.target.value === '' ? '' : Number(e.target.value))}
        />
      )}
      {param.type === 'text' && (
        <input
          className="cfg-input"
          type="text"
          autoComplete="off"
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

/** 폴더/파일 경로 입력 + '📁 찾아보기' (서버 PC에 Windows 선택 창) */
function PathField({
  param,
  value,
  onChange,
  kind,
}: {
  param: NodeParam
  value: string
  onChange: (v: string) => void
  kind: 'folder' | 'file'
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const browse = async () => {
    setBusy(true)
    setError(null)
    try {
      const res = kind === 'folder' ? await api.pickFolder(value) : await api.pickFile(value)
      if (res.path) onChange(res.path)
      else if (res.error) setError(res.error)
    } catch {
      setError('선택 창을 열 수 없습니다')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="cfg-group">
      <label className="cfg-label">{param.label}</label>
      <div className="cfg-folder-wrap">
        <input
          className="cfg-input cfg-folder-input"
          type="text"
          autoComplete="off"
          value={value}
          spellCheck={false}
          onChange={(e) => onChange(e.target.value)}
        />
        <button type="button" className="cfg-folder-btn" onClick={browse} disabled={busy}>
          {busy ? '선택 중…' : '📁 찾아보기'}
        </button>
      </div>
      {busy && (
        <div className="wh-url-hint">
          작업 표시줄에서 "{kind === 'folder' ? '폴더' : '파일'} 선택" 창을 확인하세요.
        </div>
      )}
      {error && <div className="wh-url-warn">{error}</div>}
    </div>
  )
}

type VerifyState =
  | { state: 'checking' }
  | { state: 'ok' | 'fail'; message: string; keyOk: boolean; keySource: 'node' | 'env' | 'none' }

/**
 * Gemini 모델 — 입력 + 자동완성 목록 + 연결 검증.
 * API 키나 모델이 바뀌면 0.8초 뒤 자동으로 검증(실행·토큰 사용 없음: 모델 목록 조회로 키 확인 + 모델 존재 확인).
 */
function GeminiModelField({
  param,
  value,
  apiKey,
  onChange,
}: {
  param: NodeParam
  value: string
  apiKey: string
  onChange: (v: string) => void
}) {
  const [models, setModels] = useState<string[]>([])
  const [verify, setVerify] = useState<VerifyState | null>(null)
  const listId = useRef(`gemini-models-${Math.random().toString(36).slice(2)}`).current
  const seq = useRef(0)

  const run = async () => {
    const my = ++seq.current
    setVerify({ state: 'checking' })
    try {
      const res = await api.verifyGemini(apiKey, value)
      if (my !== seq.current) return // 더 최근 검증이 진행 중
      if (res.models) setModels(res.models)
      setVerify({ state: res.ok ? 'ok' : 'fail', message: res.message, keyOk: res.keyOk, keySource: res.keySource })
    } catch {
      if (my === seq.current) setVerify({ state: 'fail', message: '검증 요청 실패 (서버 연결 확인)', keyOk: false, keySource: 'none' })
    }
  }

  useEffect(() => {
    const t = window.setTimeout(run, 800)
    return () => window.clearTimeout(t)
  }, [apiKey, value]) // eslint-disable-line react-hooks/exhaustive-deps

  const sourceLabel =
    verify && verify.state !== 'checking'
      ? verify.keySource === 'node' ? '노드에 입력한 키' : verify.keySource === 'env' ? 'GEMINI_API_KEY 환경 변수' : '키 없음'
      : null

  return (
    <div className="cfg-group">
      <label className="cfg-label">{param.label}</label>
      <div className="cfg-folder-wrap">
        <input
          className="cfg-input cfg-folder-input"
          type="text"
          autoComplete="off"
          list={listId}
          value={value}
          spellCheck={false}
          onChange={(e) => onChange(e.target.value)}
        />
        <button type="button" className="cfg-folder-btn" onClick={run} disabled={verify?.state === 'checking'}>
          {verify?.state === 'checking' ? '확인 중…' : '🔑 연결 확인'}
        </button>
      </div>
      <datalist id={listId}>
        {models.map((m) => <option key={m} value={m} />)}
      </datalist>
      {verify && (
        <div className={`verify-box ${verify.state}`}>
          {verify.state === 'checking' ? (
            <span>⏳ 키와 모델을 확인하는 중…</span>
          ) : (
            <>
              <div>{verify.state === 'ok' ? '✅' : verify.keyOk ? '⚠️' : '❌'} {verify.message}</div>
              <div className="verify-sub">
                {sourceLabel}
                {models.length > 0 && ` · 사용 가능 모델 ${models.length}개 (입력칸을 눌러 고르기)`}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}

/** HTTP 요청 노드 — 현재 설정을 상단 HTTP 화면(새 탭)에 채워서 바로 보내 보기 */
function HttpOpenField({ label, config }: { label: string; config: Record<string, unknown> }) {
  const BTYPE: Record<string, string> = { '없음': 'none', JSON: 'json', Text: 'text', 'Form (a=1&b=2)': 'form' }
  const url = String(config.url ?? '')
  const hasVars = /\$\{/.test(url + String(config.headers ?? '') + String(config.body ?? ''))

  const open = () => {
    const bodyType = String(config.bodyType ?? '없음')
    const q = new URLSearchParams({
      from: 'node',
      method: String(config.method ?? 'GET'),
      url,
      btype: BTYPE[bodyType] ?? 'none',
      body: bodyType === '없음' ? '' : String(config.body ?? ''),
      headers: String(config.headers ?? ''),
    })
    window.open(`/http?${q.toString()}`, '_blank')
  }

  return (
    <div className="cfg-group">
      <label className="cfg-label">{label}</label>
      <button type="button" className="wh-test-btn http-open-btn" onClick={open}>
        🌐 HTTP 화면에서 이 요청 열기
      </button>
      {hasVars && (
        <div className="wh-url-hint">
          <code>{'${...}'}</code> 변수는 HTTP 화면에서 그대로 보이니, 테스트할 값으로 바꿔서 보내세요.
        </div>
      )}
    </div>
  )
}

/** 대화 메모리 — 저장된 대화(대화 ID별 메시지 수) 보기 + 비우기 */
function MemoryInfoField({ label, nodeId }: { label: string; nodeId: string }) {
  const [sessions, setSessions] = useState<{ sessionKey: string; messages: number }[] | null>(null)

  const load = () => api.memorySessions(nodeId).then(setSessions).catch(() => setSessions([]))
  useEffect(() => {
    load()
    const t = window.setInterval(load, 5000)
    return () => window.clearInterval(t)
  }, [nodeId]) // eslint-disable-line react-hooks/exhaustive-deps

  const clear = async () => {
    if (!window.confirm('이 메모리에 저장된 대화를 모두 지울까요?')) return
    await api.clearMemory(nodeId)
    load()
  }

  return (
    <div className="cfg-group">
      <label className="cfg-label">{label}</label>
      {sessions === null ? (
        <div className="wh-url-hint">불러오는 중...</div>
      ) : sessions.length === 0 ? (
        <div className="wh-url-hint">아직 저장된 대화가 없습니다.</div>
      ) : (
        <>
          {sessions.map((s) => (
            <div key={s.sessionKey} className="mem-row">
              <span className="mem-key">{s.sessionKey}</span>
              <span className="mem-count">메시지 {s.messages}개</span>
            </div>
          ))}
          <button type="button" className="wh-url-copy" onClick={clear}>대화 모두 지우기</button>
        </>
      )}
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
        // 브라우저에 저장된 로그인 비밀번호가 API 키 칸에 자동으로 채워지지 않도록
        autoComplete="new-password"
        data-lpignore="true"
        data-1p-ignore="true"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="비워두면 환경 변수 사용"
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
