export interface NodeParam {
  name: string
  label: string
  type:
    | 'text' | 'textarea' | 'select' | 'number' | 'image' | 'password' | 'folder' | 'file'
    | 'webhookUrls' | 'webhookTemplate' | 'geminiModel' | 'memoryInfo' | 'httpOpen' | 'scheduleInfo'
  defaultValue: unknown
  options: string[] | null
  showWhen?: string | null  // "다른파라미터=값" 일 때만 표시
}

/** 노드 아래쪽 소켓 — 부품 노드를 끼우는 자리 */
export interface NodeSocket {
  id: string
  label: string
  accepts: string     // 끼울 수 있는 부품 종류 (부품 노드의 provides 와 같아야 함)
  required: boolean
  multiple: boolean
}

export interface NodeTypeInfo {
  id: string
  label: string
  icon: string
  color: string
  category?: string | null  // 팔레트 그룹명 (null = 레거시, 팔레트 미노출)
  params: NodeParam[]
  trigger?: boolean  // 트리거 노드 (입력 연결 없음, 파이프라인 시작점)
  sockets?: NodeSocket[]     // 아래쪽 소켓 (AI 에이전트 등)
  provides?: string | null   // 부품 노드라면 부품 종류 (ai_model 등) — 단독 실행 안 됨
}

/** 부품 노드의 위쪽 연결점 id */
export const PART_HANDLE = 'part'

/** showWhen 조건 확인 ("source=파일" 같음 / "bodyType!=없음" 다름) */
export function paramVisible(param: NodeParam, config: Record<string, unknown>, all: NodeParam[]): boolean {
  if (!param.showWhen) return true
  const not = param.showWhen.indexOf('!=')
  const i = not >= 0 ? not : param.showWhen.indexOf('=')
  if (i < 0) return true
  const key = param.showWhen.slice(0, i)
  const expected = param.showWhen.slice(i + (not >= 0 ? 2 : 1))
  const ref = all.find((p) => p.name === key)
  const actual = String(config[key] ?? ref?.defaultValue ?? '')
  return not >= 0 ? actual !== expected : actual === expected
}

export interface PipelineNodeDto {
  id: string
  type: string
  posX: number
  posY: number
  config: Record<string, unknown>
}

export interface PipelineConnectionDto {
  fromNodeId: string
  toNodeId: string
  toHandle?: string | null  // 소켓 id (부품 연결), 없으면 일반 실행 흐름
}

export interface PipelineDto {
  id: string
  name: string
  description: string
  nodes: PipelineNodeDto[]
  connections: PipelineConnectionDto[]
}

export interface RunLog {
  nodeId: string
  nodeType: string
  status: 'running' | 'success' | 'error'
  message: string
  output?: string
  outputVar?: string
}

export type RunMode = 'manual' | 'test' | 'prod' | 'schedule'

export interface ExecutionStateDto {
  executionId: string
  status: 'running' | 'done' | 'error'
  mode: RunMode
  startedAt: string
  logs: RunLog[]
}

export type ExecutionSummaryDto = Omit<ExecutionStateDto, 'logs'>

/** 웹훅 수신 기록 한 건 (GET /api/webhooks/{nodeId}/history) */
export interface WebhookHistoryEntry {
  id: string
  receivedAt: string
  mode: 'test' | 'prod'
  method: string
  httpStatus: number
  accepted: boolean
  message: string
  executionId: string | null
  executionStatus: 'running' | 'done' | 'error' | null
  payload: Record<string, unknown>
}

/** POST /api/webhooks/{nodeId}/preview */
export interface WebhookPreview {
  hasSample: boolean
  sampleAt?: string
  keys?: string[]
  result?: string | null
}

/** 노드 표시 이름 — 설정에 name 이 있으면 그것, 없으면 노드 타입 이름 (모든 모듈 공통 규칙) */
export function nodeDisplayName(nodeType: NodeTypeInfo, config: Record<string, unknown>): string {
  const name = typeof config.name === 'string' ? config.name.trim() : ''
  return name || nodeType.label
}

/** POST /api/schedule/{pipelineId}/{nodeId}/status */
export interface ScheduleStatus {
  registered: boolean
  error: string | null
  description?: string
  next?: string[]
  history: {
    at: string
    result: 'fired' | 'skipped' | 'error'
    trigger: 'timer' | 'now'
    executionId: string | null
    message: string | null
    executionStatus: 'running' | 'done' | 'error' | null
  }[]
  preview: { description?: string; next?: string[]; error?: string }
}

export const WEBHOOK_TYPE = 'WEBHOOK'
export const SCHEDULE_TYPE = 'SCHEDULE'
export const ON = '켜짐'
export const OFF = '꺼짐'

export type PipelineNodeData = {
  nodeType: NodeTypeInfo
  config: Record<string, unknown>
}
