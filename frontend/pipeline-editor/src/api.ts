import type {
  ExecutionStateDto,
  ExecutionSummaryDto,
  NodeTypeInfo,
  OutlineVerify,
  PipelineDto,
  RunMode,
  ScheduleStatus,
  WebhookHistoryEntry,
  WebhookPreview,
} from './types'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`요청 실패 (${res.status})`)
  return res.json() as Promise<T>
}

function pickPath(url: string, initial: string) {
  return fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ initial }),
  }).then((r) => r.json() as Promise<{ path?: string; cancelled?: boolean; error?: string }>)
}

export const api = {
  getNodeTypes: () => fetch('/api/node-types').then((r) => json<NodeTypeInfo[]>(r)),

  getPipeline: (id: string) => fetch(`/api/pipelines/${id}`).then((r) => json<PipelineDto>(r)),

  createPipeline: (pipeline: Partial<PipelineDto>) =>
    fetch('/api/pipelines', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(pipeline),
    }).then((r) => json<PipelineDto>(r)),

  updatePipeline: (id: string, pipeline: Partial<PipelineDto>) =>
    fetch(`/api/pipelines/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(pipeline),
    }).then((r) => json<PipelineDto>(r)),

  deletePipeline: (id: string) => fetch(`/api/pipelines/${id}`, { method: 'DELETE' }),

  runPipeline: (id: string) =>
    fetch(`/api/pipelines/${id}/run`, { method: 'POST' }).then((r) =>
      json<{ executionId?: string; error?: string }>(r),
    ),

  getExecution: (execId: string) =>
    fetch(`/api/executions/${execId}`).then((r) => json<ExecutionStateDto>(r)),

  /** 최근 실행 요약 (없으면 null) — 웹훅 테스트 실행 감지용 */
  getLatestExecution: (pipelineId: string, mode: RunMode) =>
    fetch(`/api/pipelines/${pipelineId}/executions/latest?mode=${mode}`).then((r) =>
      r.status === 204 ? null : json<ExecutionSummaryDto>(r),
    ),

  getWebhookHistory: (nodeId: string) =>
    fetch(`/api/webhooks/${nodeId}/history`).then((r) => json<WebhookHistoryEntry[]>(r)),

  previewWebhook: (nodeId: string, template: string) =>
    fetch(`/api/webhooks/${nodeId}/preview`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ template }),
    }).then((r) => json<WebhookPreview>(r)),

  clearWebhookHistory: (nodeId: string) =>
    fetch(`/api/webhooks/${nodeId}/history`, { method: 'DELETE' }),

  /** 서버 PC에 폴더/파일 선택 창 표시 */
  pickFolder: (initial: string) => pickPath('/api/dialog/folder', initial),
  pickFile: (initial: string) => pickPath('/api/dialog/file', initial),

  /** Gemini 모델 목록 (키가 비면 서버의 GEMINI_API_KEY) */
  geminiModels: (apiKey: string) =>
    fetch('/api/ai/gemini/models', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiKey }),
    }).then((r) => r.json() as Promise<{ models?: string[]; error?: string }>),

  /** 아웃라인 연결 확인 (실행 없이) — 키 확인 + 컬렉션 목록 */
  verifyOutline: (baseUrl: string, apiKey: string) =>
    fetch('/api/outline/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ baseUrl, apiKey }),
    }).then((r) => r.json() as Promise<OutlineVerify>),

  /** 모델 부품 검증 (실행 없이) — 키 확인 + 모델 존재 확인 */
  verifyGemini: (apiKey: string, model: string) =>
    fetch('/api/ai/gemini/verify', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiKey, model }),
    }).then((r) => r.json() as Promise<{
      ok: boolean
      keyOk: boolean
      modelOk: boolean | null
      keySource: 'node' | 'env' | 'none'
      message: string
      models?: string[]
    }>),

  /** 타이머 상태 (config = 화면의 현재 값으로 미리보기 계산) */
  scheduleStatus: (pipelineId: string | null, nodeId: string, config: Record<string, unknown>) =>
    fetch(`/api/schedule/${pipelineId ?? 'new'}/${nodeId}/status`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(config),
    }).then((r) => json<ScheduleStatus>(r)),

  scheduleRunNow: (pipelineId: string, nodeId: string) =>
    fetch(`/api/schedule/${pipelineId}/${nodeId}/run-now`, { method: 'POST' })
      .then((r) => json<{ executionId?: string; skipped?: boolean; message?: string }>(r)),

  memorySessions: (nodeId: string) =>
    fetch(`/api/ai/memory/${nodeId}`).then((r) => json<{ sessionKey: string; messages: number }[]>(r)),

  clearMemory: (nodeId: string) => fetch(`/api/ai/memory/${nodeId}`, { method: 'DELETE' }),

  cancelExecution: (execId: string) => fetch(`/api/executions/${execId}/cancel`, { method: 'POST' }),

  uploadImage: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return fetch('/api/images', { method: 'POST', body: form }).then((r) =>
      json<{ imagePath: string }>(r),
    )
  },

  captureRegion: () =>
    fetch('/api/capture', { method: 'POST' }).then((r) =>
      json<{ imagePath?: string; cancelled?: string }>(r),
    ),

  imageUrl: (imagePath: string) => `/api/images/${imagePath.replace(/^images\//, '')}`,

  exportPipeline: (id: string) => `/api/pipelines/${id}/export`,

  importPipeline: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return fetch('/api/pipelines/import', { method: 'POST', body: form }).then((r) =>
      json<PipelineDto>(r),
    )
  },
}
