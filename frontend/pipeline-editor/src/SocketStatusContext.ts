import { createContext, useContext } from 'react'

/** nodeId → (소켓 id → 끼워진 부품 수). 캔버스의 연결(edge)에서 계산 */
export type SocketStatusMap = Map<string, Record<string, number>>

export const SocketStatusContext = createContext<SocketStatusMap>(new Map())

export const useSocketStatus = () => useContext(SocketStatusContext)
