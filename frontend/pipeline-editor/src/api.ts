import type { ExecutionStateDto, NodeTypeInfo, PipelineDto } from './types'

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw new Error(`요청 실패 (${res.status})`)
  return res.json() as Promise<T>
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
