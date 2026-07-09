import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { ReactFlowProvider } from '@xyflow/react'
import './index.css'
import App from './App.tsx'

const rootEl = document.getElementById('root')!
const initialPipelineId = rootEl.dataset.pipelineId ?? ''

createRoot(rootEl).render(
  <StrictMode>
    <ReactFlowProvider>
      <App initialPipelineId={initialPipelineId} />
    </ReactFlowProvider>
  </StrictMode>,
)
