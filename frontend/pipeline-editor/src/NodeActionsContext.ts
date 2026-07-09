import { createContext, useContext } from 'react'

interface NodeActions {
  onDeleteNode: (id: string) => void
}

export const NodeActionsContext = createContext<NodeActions>({
  onDeleteNode: () => {},
})

export const useNodeActions = () => useContext(NodeActionsContext)
