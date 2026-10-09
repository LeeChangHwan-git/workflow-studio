import { useEffect, useRef, useState } from 'react'
import type { BranchRule } from '../types'

/** 연산자 — 서버(BranchRules.OPS)와 같은 목록 */
const OPS: [string, string][] = [
  ['contains', '포함'],
  ['notContains', '포함하지 않음'],
  ['equals', '같음'],
  ['notEquals', '다름'],
  ['startsWith', '~로 시작'],
  ['endsWith', '~로 끝남'],
  ['anyOf', '다음 중 하나 포함 (쉼표로)'],
  ['regex', '정규식에 맞음'],
  ['empty', '비어 있음'],
  ['notEmpty', '비어 있지 않음'],
  ['gt', '숫자 >'],
  ['gte', '숫자 ≥'],
  ['lt', '숫자 <'],
  ['lte', '숫자 ≤'],
  ['numEquals', '숫자 ='],
  ['lenGt', '글자 수 >'],
  ['lenLt', '글자 수 <'],
  ['expr', '식 (고급)'],
]
const NO_VALUE = new Set(['empty', 'notEmpty'])
const PLACEHOLDER: Record<string, string> = {
  contains: '예: 1',
  anyOf: '예: 긴급, 장애, 오류',
  regex: '예: ^\\d{8}$ (사번 8자리)',
  gt: '예: 80',
  gte: '예: 80',
  lt: '예: 80',
  lte: '예: 80',
  numEquals: '예: 3',
  lenGt: '예: 100',
  lenLt: '예: 10',
  expr: "예: #value.contains('FW') and #num > 3",
}

function parse(value: string): BranchRule[] {
  try {
    const v = JSON.parse(value || '[]')
    return Array.isArray(v) ? v : []
  } catch {
    return []
  }
}

function autoLabel(r: BranchRule) {
  const op = OPS.find(([k]) => k === r.op)?.[1] ?? r.op
  return NO_VALUE.has(r.op) ? op : `${r.value ?? ''} ${op}`.trim()
}

type TestResult = { picked: string[]; results: { id: string; label: string; matched: boolean; error?: string }[] } | { error: string }

/**
 * 분기 규칙 편집 — 규칙 하나마다 노드 오른쪽에 출력이 생김 (+ 그 외).
 * 예시 값을 넣으면 실제 실행과 같은 판정으로 어느 출력으로 갈지 바로 보여 줌.
 */
export function BranchRulesField({
  label,
  value,
  onChange,
  mode,
  ignoreCase,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  mode: string
  ignoreCase: string
}) {
  const rules = parse(value)
  const [sample, setSample] = useState('')
  const [test, setTest] = useState<TestResult | null>(null)
  const [openSubject, setOpenSubject] = useState<Record<string, boolean>>({})
  const seq = useRef(0)

  const save = (next: BranchRule[]) => onChange(JSON.stringify(next))
  const update = (i: number, patch: Partial<BranchRule>) => {
    const next = rules.map((r, j) => {
      if (j !== i) return r
      const merged = { ...r, ...patch }
      // 이름을 따로 안 바꿨으면 조건에 맞춰 자동 이름
      if (!r.label || r.label === autoLabel(r)) merged.label = autoLabel(merged)
      return merged
    })
    save(next)
  }
  const add = () => {
    const used = new Set(rules.map((r) => r.id))
    let n = rules.length + 1
    while (used.has('r' + n)) n++
    const r: BranchRule = { id: 'r' + n, op: 'contains', value: '' , label: '' }
    r.label = autoLabel(r)
    save([...rules, r])
  }
  const remove = (i: number) => save(rules.filter((_, j) => j !== i))
  const move = (i: number, d: number) => {
    const j = i + d
    if (j < 0 || j >= rules.length) return
    const next = [...rules]
    ;[next[i], next[j]] = [next[j], next[i]]
    save(next)
  }

  // 예시 값으로 시험 (0.4초 뒤 자동)
  useEffect(() => {
    if (!sample) {
      setTest(null)
      return
    }
    const my = ++seq.current
    const t = window.setTimeout(async () => {
      try {
        const r = await fetch('/api/branch/test', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ sample, rules: value, mode, ignoreCase }),
        }).then((x) => x.json())
        if (my === seq.current) setTest(r.ok ? r : { error: r.error })
      } catch {
        if (my === seq.current) setTest({ error: '시험 요청 실패' })
      }
    }, 400)
    return () => window.clearTimeout(t)
  }, [sample, value, mode, ignoreCase])

  const picked = test && 'picked' in test ? new Set(test.picked) : null

  return (
    <div className="cfg-group">
      <div className="br-head">
        <label className="cfg-label" style={{ margin: 0 }}>{label}</label>
        <button
          type="button"
          className="hdr-help"
          onClick={() => window.open('/guide/branch', 'workflow-branch-guide', 'width=980,height=900,resizable=yes,scrollbars=yes')}
        >
          ❓ 표현식 설명서
        </button>
      </div>

      {rules.map((r, i) => (
        <div key={r.id} className={`br-rule${picked?.has(r.id) ? ' hit' : ''}`}>
          <div className="br-row">
            <span className="br-no">{i + 1}</span>
            <input
              className="br-name"
              value={r.label}
              title="출력 이름 (노드 오른쪽에 표시)"
              onChange={(e) => update(i, { label: e.target.value })}
            />
            <button type="button" className="br-icon" title="위로" onClick={() => move(i, -1)}>↑</button>
            <button type="button" className="br-icon" title="아래로" onClick={() => move(i, 1)}>↓</button>
            <button type="button" className="br-icon del" title="삭제" onClick={() => remove(i)}>✕</button>
          </div>
          <div className="br-row">
            <select className="br-op" value={r.op} onChange={(e) => update(i, { op: e.target.value })}>
              {OPS.map(([k, l]) => <option key={k} value={k}>{l}</option>)}
            </select>
            {!NO_VALUE.has(r.op) && (
              <input
                className={`br-val${r.op === 'expr' || r.op === 'regex' ? ' mono' : ''}`}
                value={r.value ?? ''}
                placeholder={PLACEHOLDER[r.op] ?? '비교할 값'}
                spellCheck={false}
                onChange={(e) => update(i, { value: e.target.value })}
              />
            )}
          </div>
          {(openSubject[r.id] || r.subject) ? (
            <input
              className="br-subject"
              value={r.subject ?? ''}
              placeholder="이 규칙만 다른 값 검사 (예: ${webhook.body.type}) — 비우면 위의 '검사할 값'"
              spellCheck={false}
              onChange={(e) => update(i, { subject: e.target.value })}
            />
          ) : (
            <button type="button" className="br-link" onClick={() => setOpenSubject({ ...openSubject, [r.id]: true })}>
              + 이 규칙만 다른 값 검사
            </button>
          )}
          {test && 'results' in test && test.results[i]?.error && <div className="br-err">⚠️ {test.results[i].error}</div>}
        </div>
      ))}
      <div className={`br-rule else${picked?.has('else') ? ' hit' : ''}`}>
        <div className="br-row"><span className="br-no">↳</span><span className="br-else">그 외 — 맞는 규칙이 없을 때</span></div>
      </div>
      <button type="button" className="br-add" onClick={add}>+ 규칙 추가</button>

      <div className="br-test">
        <div className="br-test-title">🧪 시험해 보기 — 예시 값을 넣으면 어느 출력으로 갈지 보여 줌</div>
        <textarea
          value={sample}
          placeholder="예: 주문번호 1번 문의입니다"
          spellCheck={false}
          onChange={(e) => setSample(e.target.value)}
        />
        {test && ('error' in test ? (
          <div className="br-err">⚠️ {test.error}</div>
        ) : (
          <div className="br-result">
            → <b>{test.picked.map((id) => (id === 'else' ? '그 외' : rules.find((r) => r.id === id)?.label ?? id)).join(', ')}</b> 출력으로
          </div>
        ))}
      </div>
    </div>
  )
}
