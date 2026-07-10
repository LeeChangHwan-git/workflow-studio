import { useEffect, useRef, useState } from 'react'
import { api } from '../api'
import type { NodeTypeInfo, RunLog } from '../types'

interface Props {
  pipelineId: string
  open: boolean
  onClose: () => void
  nodeTypes: NodeTypeInfo[]
}

type Tab = 'logs' | 'results'

export function RunModal({ pipelineId, open, onClose, nodeTypes }: Props) {
  const [status, setStatus] = useState<'running' | 'done' | 'error'>('running')
  const [logs, setLogs] = useState<RunLog[]>([])
  const [startError, setStartError] = useState<string | null>(null)
  const [activeTab, setActiveTab] = useState<Tab>('logs')
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const intervalRef = useRef<number | null>(null)

  const typeMap = new Map(nodeTypes.map((t) => [t.id, t]))

  useEffect(() => {
    if (!open) return
    setStatus('running')
    setLogs([])
    setStartError(null)
    setActiveTab('logs')

    let cancelled = false

    api.runPipeline(pipelineId).then((res) => {
      if (cancelled) return
      if (!res.executionId) {
        setStartError(res.error ?? '실행을 시작하지 못했습니다')
        setStatus('error')
        return
      }
      intervalRef.current = window.setInterval(async () => {
        const state = await api.getExecution(res.executionId!)
        setLogs(state.logs)
        setStatus(state.status)
        if (state.status !== 'running' && intervalRef.current) {
          window.clearInterval(intervalRef.current)
          intervalRef.current = null
          if (state.status === 'done' && state.logs.some((l) => l.output)) {
            setActiveTab('results')
          }
        }
      }, 1000)
    })

    return () => {
      cancelled = true
      if (intervalRef.current) {
        window.clearInterval(intervalRef.current)
        intervalRef.current = null
      }
    }
  }, [open, pipelineId])

  if (!open) return null

  const outputLogs = logs.filter((l) => l.output && l.status === 'success')

  const copy = (text: string, id: string) => {
    const fallback = () => {
      const el = document.createElement('textarea')
      el.value = text
      document.body.appendChild(el)
      el.select()
      document.execCommand('copy')
      document.body.removeChild(el)
    }
    if (navigator.clipboard) {
      navigator.clipboard.writeText(text).catch(fallback)
    } else {
      fallback()
    }
    setCopiedId(id)
    setTimeout(() => setCopiedId(null), 1500)
  }

  return (
    <div className="modal-backdrop show" onClick={onClose}>
      <div className="modal-box run-modal" onClick={(e) => e.stopPropagation()}>

        {/* 헤더 */}
        <div className="modal-header">
          <div className="modal-title">파이프라인 실행</div>
          <span className={`run-status-badge ${status}`}>
            {status === 'running' && '● 실행 중'}
            {status === 'done'    && '✓ 완료'}
            {status === 'error'   && '✕ 오류'}
          </span>
          <button type="button" className="modal-close" onClick={onClose}>✕</button>
        </div>

        {/* 탭 */}
        <div className="modal-tabs">
          <button
            type="button"
            className={`modal-tab${activeTab === 'logs' ? ' active' : ''}`}
            onClick={() => setActiveTab('logs')}
          >
            실행 로그
          </button>
          <button
            type="button"
            className={`modal-tab${activeTab === 'results' ? ' active' : ''}`}
            onClick={() => setActiveTab('results')}
          >
            결과
            {outputLogs.length > 0 && (
              <span className="tab-badge">{outputLogs.length}</span>
            )}
          </button>
        </div>

        {/* 바디 */}
        <div className="modal-body">
          {activeTab === 'logs' && (
            <>
              {startError && <div className="log-item error"><div>{startError}</div></div>}
              {logs.length === 0 && !startError && (
                <div className="log-empty">실행 대기 중...</div>
              )}
              {logs.map((log, i) => (
                <div key={i} className={`log-item ${log.status}`}>
                  <div className="log-msg">{log.message}</div>
                  {log.output && log.status === 'success' && (
                    <div className="log-output-preview">
                      {log.output.slice(0, 120)}{log.output.length > 120 ? '...' : ''}
                    </div>
                  )}
                  {log.output && log.status === 'error' && (
                    <div className="log-output">{log.output}</div>
                  )}
                </div>
              ))}
            </>
          )}

          {activeTab === 'results' && (
            <>
              {outputLogs.length === 0 ? (
                <div className="result-empty">
                  {status === 'running'
                    ? '실행 중... 완료되면 결과가 표시됩니다.'
                    : '출력 데이터가 없습니다.'}
                </div>
              ) : (
                outputLogs.map((log, i) => {
                  const nt = typeMap.get(log.nodeType)
                  const cardId = `result-${i}`
                  return (
                    <div key={i} className="result-card">
                      <div className="result-card-header" style={{ borderLeftColor: nt?.color ?? '#555' }}>
                        <span className="result-node-icon">{nt?.icon ?? '🔹'}</span>
                        <span className="result-node-label">{nt?.label ?? log.nodeType}</span>
                        {nt?.category && (
                          <span className="result-node-cat">{nt.category}</span>
                        )}
                        <button
                          type="button"
                          className={`result-copy-btn${copiedId === cardId ? ' copied' : ''}`}
                          onClick={() => copy(log.output!, cardId)}
                        >
                          {copiedId === cardId ? '✓ 복사됨' : '복사'}
                        </button>
                      </div>
                      <div className="result-card-body">
                        <SmartRenderer text={log.output!} outputVar={log.outputVar} />
                      </div>
                    </div>
                  )
                })
              )}
            </>
          )}
        </div>

        <div className="modal-footer">
          <button type="button" className="btn" onClick={onClose}>닫기</button>
        </div>
      </div>
    </div>
  )
}

/* ── 스마트 렌더러: JSON이면 JsonViewer, 아니면 SimpleMarkdown ── */

function SmartRenderer({ text, outputVar }: { text: string; outputVar?: string }) {
  // "HTTP 200\n\n{...}" 같은 prefix 처리
  const prefixMatch = text.match(/^(HTTP \d+[^\n]*\n+)([\s\S]*)$/)
  const prefix = prefixMatch ? prefixMatch[1].trim() : null
  const body = prefixMatch ? prefixMatch[2] : text

  // JSON 파싱 시도
  let parsed: unknown = undefined
  try {
    parsed = JSON.parse(body.trim())
  } catch {
    // JSON이 아님
  }

  if (parsed !== undefined) {
    return (
      <div>
        {prefix && <div className="json-prefix-line">{prefix}</div>}
        <JsonViewer json={parsed} outputVar={outputVar} />
      </div>
    )
  }

  return <SimpleMarkdown text={text} />
}

/* ── JSON Viewer ─────────────────────────────────────────────── */

function JsonViewer({ json, outputVar }: { json: unknown; outputVar?: string }) {
  const [hoverPath, setHoverPath] = useState<string | null>(null)
  const [copiedPath, setCopiedPath] = useState<string | null>(null)
  const varName = outputVar ?? 'result'

  const buildFullPath = (p: string) =>
    `\${${varName}${p ? '.' + p : ''}}`

  const handleCopy = (p: string) => {
    const text = buildFullPath(p)
    const fallback = () => {
      const el = document.createElement('textarea')
      el.value = text
      document.body.appendChild(el)
      el.select()
      document.execCommand('copy')
      document.body.removeChild(el)
    }
    if (navigator.clipboard) {
      navigator.clipboard.writeText(text).catch(fallback)
    } else {
      fallback()
    }
    setCopiedPath(p)
    setTimeout(() => setCopiedPath(null), 1200)
  }

  const isCopied = copiedPath !== null
  const displayPath = hoverPath !== null ? buildFullPath(hoverPath) : null

  return (
    <div className="json-viewer">
      <div className={`json-path-bar${isCopied ? ' copied' : ''}`}>
        {isCopied
          ? <span className="json-path-copied">✓ 복사됨: {buildFullPath(copiedPath!)}</span>
          : displayPath !== null
            ? <span className="json-path-active">{displayPath}<span className="json-path-tip"> 클릭하여 복사</span></span>
            : <span className="json-path-hint">값에 마우스를 올리고 클릭하면 경로가 복사됩니다</span>
        }
      </div>
      <div className="json-tree">
        <JsonNode value={json} path="" depth={0} onHover={setHoverPath} onCopy={handleCopy} />
      </div>
    </div>
  )
}

function JsonNode({
  value, path, depth, onHover, onCopy,
}: {
  value: unknown
  path: string
  depth: number
  onHover: (p: string | null) => void
  onCopy: (p: string) => void
}) {
  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="json-punct">{'[]'}</span>
    return (
      <span>
        <span className="json-punct">{'['}</span>
        {value.map((item, idx) => {
          const childPath = path ? `${path}[${idx}]` : `[${idx}]`
          return (
            <div key={idx} style={{ paddingLeft: '1em' }}>
              <JsonNode value={item} path={childPath} depth={depth + 1} onHover={onHover} onCopy={onCopy} />
              {idx < value.length - 1 && <span className="json-punct">,</span>}
            </div>
          )
        })}
        <div style={{ paddingLeft: `${depth * 0.5}em` }}>
          <span className="json-punct">{']'}</span>
        </div>
      </span>
    )
  }

  if (value !== null && typeof value === 'object') {
    const entries = Object.entries(value as Record<string, unknown>)
    if (entries.length === 0) return <span className="json-punct">{'{}'}</span>
    return (
      <span>
        <span className="json-punct">{'{'}</span>
        {entries.map(([k, v], idx) => {
          const childPath = path ? `${path}.${k}` : k
          return (
            <div key={k} style={{ paddingLeft: '1em' }}>
              <span
                className="json-key"
                onMouseEnter={() => onHover(childPath)}
                onMouseLeave={() => onHover(null)}
                onClick={() => onCopy(childPath)}
              >"{k}"</span>
              <span className="json-punct">: </span>
              <JsonNode value={v} path={childPath} depth={depth + 1} onHover={onHover} onCopy={onCopy} />
              {idx < entries.length - 1 && <span className="json-punct">,</span>}
            </div>
          )
        })}
        <div style={{ paddingLeft: `${depth * 0.5}em` }}>
          <span className="json-punct">{'}'}</span>
        </div>
      </span>
    )
  }

  // 원시값
  const cls =
    value === null ? 'json-null' :
    typeof value === 'string' ? 'json-str' :
    typeof value === 'boolean' ? 'json-bool' :
    'json-num'
  const display =
    value === null ? 'null' :
    typeof value === 'string' ? `"${value}"` :
    String(value)

  return (
    <span
      className={`json-val ${cls}`}
      onMouseEnter={() => onHover(path)}
      onMouseLeave={() => onHover(null)}
      onClick={() => onCopy(path)}
    >{display}</span>
  )
}

/* ── 간이 Markdown 렌더러 ──────────────────────────────────── */

function SimpleMarkdown({ text }: { text: string }) {
  const lines = text.split('\n')
  return (
    <div className="md-body">
      {lines.map((line, i) => {
        if (line.startsWith('# '))
          return <div key={i} className="md-h1">{inlineFmt(line.slice(2))}</div>
        if (line.startsWith('## '))
          return <div key={i} className="md-h2">{inlineFmt(line.slice(3))}</div>
        if (line.startsWith('### '))
          return <div key={i} className="md-h3">{inlineFmt(line.slice(4))}</div>
        if (line.startsWith('- ') || line.startsWith('* '))
          return (
            <div key={i} className="md-li">
              <span className="md-li-dot">•</span>
              {inlineFmt(line.slice(2))}
            </div>
          )
        if (line.startsWith('> '))
          return <div key={i} className="md-quote">{inlineFmt(line.slice(2))}</div>
        if (line.match(/^-{3,}$/) || line.match(/^={3,}$/))
          return <hr key={i} className="md-hr" />
        if (line.trim() === '')
          return <div key={i} className="md-gap" />
        return <div key={i} className="md-p">{inlineFmt(line)}</div>
      })}
    </div>
  )
}

function inlineFmt(text: string): React.ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*|\*[^*]+\*|`[^`]+`)/g)
  return (
    <>
      {parts.map((part, i) => {
        if (part.startsWith('**') && part.endsWith('**'))
          return <strong key={i}>{part.slice(2, -2)}</strong>
        if (part.startsWith('*') && part.endsWith('*'))
          return <em key={i}>{part.slice(1, -1)}</em>
        if (part.startsWith('`') && part.endsWith('`'))
          return <code key={i} className="md-code">{part.slice(1, -1)}</code>
        return <span key={i}>{part}</span>
      })}
    </>
  )
}
