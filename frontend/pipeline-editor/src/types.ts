export interface NodeParam {
  name: string
  label: string
  type: 'text' | 'textarea' | 'select' | 'number' | 'image' | 'password' | 'webhookUrls' | 'webhookTemplate'
  defaultValue: unknown
  options: string[] | null
}

export interface NodeTypeInfo {
  id: string
  label: string
  icon: string
  color: string
  category?: string | null  // 팔레트 그룹명 (null = 레거시, 팔레트 미노출)
  params: NodeParam[]
  trigger?: boolean  // 트리거 노드 (입력 연결 없음, 파이프라인 시작점)
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

export type RunMode = 'manual' | 'test' | 'prod'

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

export const WEBHOOK_TYPE = 'WEBHOOK'
export const ON = '켜짐'
export const OFF = '꺼짐'

export type PipelineNodeData = {
  nodeType: NodeTypeInfo
  config: Record<string, unknown>
}
