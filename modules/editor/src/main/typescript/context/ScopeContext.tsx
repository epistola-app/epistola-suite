import type {ReactNode} from "react";
import {createContext, useContext, useMemo} from "react";

export interface ScopeVariable {
  name: string;
  type: "loop-item" | "loop-index";
  arrayPath: string; // The path to the array being iterated
}

interface ScopeContextValue {
  variables: ScopeVariable[];
}

const ScopeContext = createContext<ScopeContextValue>({ variables: [] });

interface ScopeProviderProps {
  children: ReactNode;
  variables?: ScopeVariable[];
}

export function ScopeProvider({ children, variables = [] }: ScopeProviderProps) {
  const parentScope = useContext(ScopeContext);

  const mergedScope = useMemo(
    () => ({
      variables: [...parentScope.variables, ...variables],
    }),
    [parentScope.variables, variables],
  );

  return <ScopeContext.Provider value={mergedScope}>{children}</ScopeContext.Provider>;
}

export function useScope() {
  return useContext(ScopeContext);
}
