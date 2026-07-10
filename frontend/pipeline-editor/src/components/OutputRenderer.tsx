import { useState } from 'react'

interface Props {
  text: string
  outputVar?: string
}

export function OutputRenderer({ text, outputVar }: Props) {
  const prefixMatch = text.match(/^(HTTP \d+[^\n]*\n+)([\s\S]*)$/)
  const prefix = prefixMatch ? prefixMatch[1].trim() : null
  const body = prefixMatch ? prefixMatch[2] : text

  let parsed: unknown = undefined
  try {
    parsed = JSON.parse(body.trim())
  } catch {
    // not JSON
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

  const buildFullPath = (p: string) => `\${${varName}${p ? '.' + p : ''}}`

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
        <div><span className="json-punct">{']'}</span></div>
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
        <div><span className="json-punct">{'}'}</span></div>
      </span>
    )
  }

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
