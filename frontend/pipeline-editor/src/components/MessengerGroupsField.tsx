import { useEffect, useState } from 'react'

type Group = { name: string; description: string; members: { id: string; name: string }[] }

/** 쪽지 노드: ⚙ 설정의 메신저 그룹을 칩으로 보여 주고, 누르면 받는 사람 칸에 @그룹 을 넣거나 뺌 */
export function MessengerGroupsField({ label, empIds, onChange }: { label: string; empIds: string; onChange: (v: string) => void }) {
  const [groups, setGroups] = useState<Group[] | null>(null)

  useEffect(() => {
    fetch('/api/messenger/groups')
      .then((r) => r.json())
      .then((r) => setGroups(r.groups))
      .catch(() => setGroups([]))
  }, [])

  const tokens = empIds.split(/[,;\s]+/).filter(Boolean)
  const has = (name: string) => tokens.some((t) => t.toLowerCase() === '@' + name.toLowerCase())
  const toggle = (name: string) => {
    const next = has(name) ? tokens.filter((t) => t.toLowerCase() !== '@' + name.toLowerCase()) : [...tokens, '@' + name]
    onChange(next.join(', '))
  }

  return (
    <div className="cfg-group">
      <label className="cfg-label">{label}</label>
      {groups === null ? (
        <div className="wh-url-hint">불러오는 중…</div>
      ) : groups.length === 0 ? (
        <div className="wh-url-hint">아직 그룹이 없습니다 — <a href="/settings#groups" target="_blank" rel="noreferrer">⚙ 설정 → 👥 메신저 그룹</a>에서 만드세요.</div>
      ) : (
        <>
          <div className="mg-chips">
            {groups.map((g) => (
              <button
                type="button"
                key={g.name}
                className={`mg-chip${has(g.name) ? ' on' : ''}`}
                title={`${g.description ? g.description + '\n' : ''}${g.members.map((m) => `${m.name} (${m.id})`).join('\n')}`}
                onClick={() => toggle(g.name)}
              >
                {has(g.name) ? '✓ ' : '+ '}@{g.name} <span>{g.members.length}명</span>
              </button>
            ))}
          </div>
          <div className="wh-url-hint">
            누르면 받는 사람 칸에 넣거나 뺍니다. 그룹 구성원은 <a href="/settings#groups" target="_blank" rel="noreferrer">⚙ 설정 → 👥 메신저 그룹</a>에서 바꾸고, 바꾸면 이 노드에도 바로 반영됩니다.
          </div>
        </>
      )}
    </div>
  )
}
