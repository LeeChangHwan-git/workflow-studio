export interface NodeParam {
  name: string
  label: string
  type:
    | 'text' | 'textarea' | 'select' | 'number' | 'image' | 'password' | 'folder' | 'file'
    | 'webhookUrls' | 'webhookTemplate' | 'geminiModel' | 'memoryInfo' | 'httpOpen' | 'scheduleInfo'
    | 'outlineConnection' | 'outlineCollection' | 'htmlPicker' | 'secretTextarea' | 'branchRules' | 'approvalInfo' | 'messengerGroups'
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
  outputs?: NodeOutputInfo[]  // 오른쪽 출력이 여러 개인 노드 (분기·확인 요청)
  outputsParam?: string | null // 출력이 설정값(규칙 목록)에서 정해지는 노드면 그 파라미터 이름
}

export interface NodeOutputInfo {
  id: string
  label: string
}

/** 분기 규칙 (BRANCH 노드 rules 설정값 = 이 목록의 JSON) */
export interface BranchRule {
  id: string
  label: string
  op: string
  value?: string
  subject?: string
}

export const BRANCH_ELSE = 'else'

/** 이 노드의 출력 연결점 목록 — 없으면 [] (출력 하나: 기본) */
export function nodeOutputs(nodeType: NodeTypeInfo, config: Record<string, unknown>): NodeOutputInfo[] {
  if (nodeType.outputsParam) {
    let rules: BranchRule[] = []
    try {
      const raw = config[nodeType.outputsParam]
      const def = nodeType.params.find((p) => p.name === nodeType.outputsParam)?.defaultValue
      rules = JSON.parse(String(raw ?? def ?? '[]'))
    } catch {
      rules = []
    }
    return [...rules.filter((r) => r && r.id).map((r) => ({ id: r.id, label: r.label || r.id })), { id: BRANCH_ELSE, label: '그 외' }]
  }
  return nodeType.outputs ?? []
}

/** 부품 노드의 위쪽 연결점 id */
export const PART_HANDLE = 'part'

/** showWhen 조건 확인 ("source=파일" 같음 / "bodyType!=없음" 다름 / "action=조회|검색" 여러 값 중 하나) */
export function paramVisible(param: NodeParam, config: Record<string, unknown>, all: NodeParam[]): boolean {
  if (!param.showWhen) return true
  const not = param.showWhen.indexOf('!=')
  const i = not >= 0 ? not : param.showWhen.indexOf('=')
  if (i < 0) return true
  const key = param.showWhen.slice(0, i)
  const expected = param.showWhen.slice(i + (not >= 0 ? 2 : 1))
  const ref = all.find((p) => p.name === key)
  const actual = String(config[key] ?? ref?.defaultValue ?? '')
  const matches = expected.split('|').includes(actual)
  return not >= 0 ? !matches : matches
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
  fromHandle?: string | null // 출력이 여러 개인 노드에서 나온 화살표면 그 출력 id
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
  status: 'running' | 'success' | 'error' | 'waiting' | 'skipped'
  message: string
  output?: string
  outputVar?: string
  route?: string[]  // 분기 노드가 고른 출력
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

/** 아웃라인 연결 확인 결과 (/api/outline/verify) */
export interface OutlineVerify {
  ok: boolean
  message: string
  keySource: 'node' | 'settings' | 'env' | 'none'
  user?: string
  team?: string
  collections?: { id: string; name: string }[]
  /** 위치 자동완성: 컬렉션 + 그 아래 문서 경로 ("FW팀/회의록/2026년") */
  paths?: string[]
}
