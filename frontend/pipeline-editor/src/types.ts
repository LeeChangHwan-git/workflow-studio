export interface NodeParam {
  name: string
  label: string
  type: 'text' | 'textarea' | 'select' | 'number' | 'image' | 'password'
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

export interface ExecutionStateDto {
  executionId: string
  status: 'running' | 'done' | 'error'
  logs: RunLog[]
}

export type PipelineNodeData = {
  nodeType: NodeTypeInfo
  config: Record<string, unknown>
}
