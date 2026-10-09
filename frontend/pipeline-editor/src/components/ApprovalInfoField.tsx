import { useEffect, useState } from 'react'

type Pending = { id: string; nodeId: string; pipelineId: string; title: string; round: number; createdAt: string }

/** 확인 창 열기 (approvals.js 가 모든 화면에 넣어 둔 함수) */
export function openApprovalWindow(nodeId?: string) {
  const w = window as unknown as { workflowApprovals?: { open: (id?: string) => void } }
  if (w.workflowApprovals) w.workflowApprovals.open(nodeId)
  else window.alert('확인 창 스크립트가 아직 없습니다 — 새로고침(F5) 후 다시 눌러 주세요')
}

/**
 * 확인 요청 노드 설정 맨 위: 사용법 + 지금 이 노드가 확인을 기다리는지 (2초마다) + 확인 창 열기 버튼
 */
export function ApprovalInfoField({ nodeId, pipelineId }: { nodeId: string; pipelineId: string | null }) {
  const [mine, setMine] = useState<Pending | null>(null)

  useEffect(() => {
    let stop = false
    const check = async () => {
      try {
        const r = await fetch('/api/approvals').then((x) => x.json())
        const p = (r.pending as Pending[]).find((x) => x.nodeId === nodeId && (!pipelineId || x.pipelineId === pipelineId))
        if (!stop) setMine(p ?? null)
      } catch { /* 무시 */ }
    }
    check()
    const t = window.setInterval(check, 2000)
    return () => {
      stop = true
      window.clearInterval(t)
    }
  }, [nodeId, pipelineId])

  return (
    <div className="cfg-group">
      {mine ? (
        <div className="apv-info waiting">
          <div className="apv-info-title">🙋 지금 확인을 기다리는 중{mine.round > 1 ? ` (${mine.round}번째)` : ''}</div>
          <div className="apv-info-sub">{mine.title}</div>
          <button type="button" className="apv-info-btn" onClick={() => openApprovalWindow(nodeId)}>
            확인 창 열기 — 승인 / 다시 / 거절
          </button>
        </div>
      ) : (
        <div className="apv-info">
          <div className="apv-info-title">🙋 이 노드는 실행 중에 멈춰서 확인을 받습니다</div>
          <ol>
            <li>툴바의 <b>▶ 실행</b>(또는 웹훅·타이머)으로 파이프라인을 돌리면</li>
            <li>여기까지 와서 멈추고, <b>화면 가운데에 확인 창</b>이 저절로 뜹니다 (알림음 · 상단 "🙋 확인 요청" 배지)</li>
            <li>창에서 <b>✅ 승인 · ✏️ 직접 고치기 · 🔁 다시(의견) · ❌ 거절</b> 중 고르면 그 출력으로 이어집니다</li>
          </ol>
          <div className="apv-info-sub">
            "수정 AI" 소켓에 Gemini 모델을 끼우면 🔁 다시를 눌렀을 때 AI가 의견대로 고쳐서 다시 보여 줍니다.
            창을 닫았어도 노드 카드의 <b>🙋 확인하기</b> 버튼이나 상단 배지로 다시 열 수 있어요.
          </div>
        </div>
      )}
    </div>
  )
}
