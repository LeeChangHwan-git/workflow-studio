import type { NodeTypeInfo } from '../types'
import type { GhostState } from '../usePaletteDrag'

/** 실제 노드 카드와 같은 모양 (연결점·버튼 없이) */
function CardPreview({ nodeType }: { nodeType: NodeTypeInfo }) {
  const sockets = nodeType.sockets ?? []
  return (
    <div className={`pnode${sockets.length > 0 ? ' has-sockets' : ''}`}>
      <div className="pnode-cat-bar" style={{ background: nodeType.color }}>
        <span className="pnode-cat-label">{nodeType.category ?? nodeType.id}</span>
      </div>
      <div className="pnode-body">
        <span className="pnode-icon">{nodeType.icon}</span>
        <span className="pnode-label">{nodeType.label}</span>
      </div>
      {sockets.length > 0 && (
        <div className="pnode-sockets">
          {sockets.map((s) => (
            <div key={s.id} className="pnode-socket">
              <span className="pnode-socket-label">
                {s.label}
                {s.required && <span className="pnode-socket-req">*</span>}
              </span>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

interface Props {
  ghost: GhostState
  ghostRef: React.RefObject<HTMLDivElement | null>
}

/**
 * 팔레트 드래그 미리보기.
 * - 카드: 커서가 잡은 지점을 기준으로 따라다님 (살짝 들어 올린 듯 기울기·그림자)
 * - 슬롯: 캔버스 위일 때 실제로 놓일 자리 (격자 정렬, 점선)
 */
export function DragGhost({ ghost, ghostRef }: Props) {
  const { nodeType, left, top, scale, overCanvas, slot } = ghost
  return (
    <>
      {slot && (
        <div
          className="drag-slot"
          style={{ translate: `${slot.left}px ${slot.top}px`, scale: String(scale) }}
        >
          <CardPreview nodeType={nodeType} />
        </div>
      )}
      <div
        ref={ghostRef}
        className={`drag-ghost${overCanvas ? ' over' : ' outside'}`}
        style={{ translate: `${left}px ${top}px`, scale: String(scale) }}
      >
        <CardPreview nodeType={nodeType} />
      </div>
      <div className={`drag-hint${overCanvas ? ' over' : ''}`}>
        {overCanvas ? '놓으면 이 자리에 추가' : '캔버스로 끌어다 놓으세요'}
        <kbd>Esc</kbd> 취소
      </div>
    </>
  )
}
