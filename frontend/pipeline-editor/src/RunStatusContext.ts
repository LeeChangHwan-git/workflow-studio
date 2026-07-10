import { createContext, useContext } from 'react'
import type { RunLog } from './types'

export const RunStatusContext = createContext<Map<string, RunLog>>(new Map())
export const useRunStatus = () => useContext(RunStatusContext)
