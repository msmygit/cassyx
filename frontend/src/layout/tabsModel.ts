/**
 * Multi-tab editing state (plan §8 — multi-tab was a documented gap in the prior art).
 *
 * A pure reducer so tab behaviour (which tab becomes active when you close the active one,
 * dirty-state tracking, de-duplication of "open table" requests) is unit-testable without React.
 */
import { createContext, useContext } from 'react';
import type { SchemaIdentity } from '../schema/model';

export type TabKind = 'query' | 'table' | 'job' | 'vector';

export interface EditorTab {
  id: string;
  kind: TabKind;
  title: string;
  /** Editor content for `query` tabs; the generated SELECT for `table` tabs. */
  content: string;
  /** Fully-qualified identity this tab was opened for, when it came from the schema tree. */
  identity?: SchemaIdentity;
  /** Unsaved changes — shown as a dot and used to warn on close. */
  dirty: boolean;
}

export interface TabsState {
  tabs: EditorTab[];
  activeTabId: string | null;
  /** Monotonic counter so new untitled tabs get stable, non-colliding names. */
  sequence: number;
}

export type TabsAction =
  | { type: 'open'; tab: Omit<EditorTab, 'id' | 'dirty'> & { id?: string; dirty?: boolean } }
  | { type: 'openFromSchema'; identity: SchemaIdentity; title: string; content: string }
  | { type: 'close'; id: string }
  | { type: 'closeOthers'; id: string }
  | { type: 'activate'; id: string }
  | { type: 'update'; id: string; content: string }
  | { type: 'markSaved'; id: string }
  | { type: 'rename'; id: string; title: string }
  | { type: 'reorder'; from: number; to: number };

export function initialTabsState(): TabsState {
  const first: EditorTab = {
    id: 'tab-1',
    kind: 'query',
    title: 'Query 1',
    content: '',
    dirty: false,
  };
  return { tabs: [first], activeTabId: first.id, sequence: 1 };
}

/**
 * When the active tab closes, activate its right-hand neighbour, falling back to the left —
 * the behaviour every editor uses, and the one users do not have to think about.
 */
function nextActiveId(tabs: EditorTab[], closedIndex: number): string | null {
  if (tabs.length === 0) return null;
  const index = Math.min(closedIndex, tabs.length - 1);
  return tabs[index]?.id ?? null;
}

export function tabsReducer(state: TabsState, action: TabsAction): TabsState {
  switch (action.type) {
    case 'open': {
      const sequence = state.sequence + 1;
      const tab: EditorTab = {
        id: action.tab.id ?? `tab-${sequence}`,
        kind: action.tab.kind,
        title: action.tab.title,
        content: action.tab.content,
        dirty: action.tab.dirty ?? false,
        ...(action.tab.identity ? { identity: action.tab.identity } : {}),
      };
      return { tabs: [...state.tabs, tab], activeTabId: tab.id, sequence };
    }

    case 'openFromSchema': {
      // De-duplicate on the FULLY-QUALIFIED identity, not on the bare table name: `demo.users`
      // and `system_auth.users` are different tabs.
      const existing = state.tabs.find(
        (tab) =>
          tab.identity?.keyspace === action.identity.keyspace &&
          tab.identity?.table === action.identity.table,
      );
      if (existing) {
        return { ...state, activeTabId: existing.id };
      }
      const sequence = state.sequence + 1;
      const tab: EditorTab = {
        id: `tab-${sequence}`,
        kind: 'table',
        title: action.title,
        content: action.content,
        identity: action.identity,
        dirty: false,
      };
      return { tabs: [...state.tabs, tab], activeTabId: tab.id, sequence };
    }

    case 'close': {
      const index = state.tabs.findIndex((tab) => tab.id === action.id);
      if (index === -1) return state;
      const tabs = state.tabs.filter((tab) => tab.id !== action.id);
      const activeTabId =
        state.activeTabId === action.id ? nextActiveId(tabs, index) : state.activeTabId;
      return { ...state, tabs, activeTabId };
    }

    case 'closeOthers': {
      const kept = state.tabs.filter((tab) => tab.id === action.id);
      if (kept.length === 0) return state;
      return { ...state, tabs: kept, activeTabId: action.id };
    }

    case 'activate':
      return state.tabs.some((tab) => tab.id === action.id)
        ? { ...state, activeTabId: action.id }
        : state;

    case 'update':
      return {
        ...state,
        tabs: state.tabs.map((tab) =>
          tab.id === action.id ? { ...tab, content: action.content, dirty: true } : tab,
        ),
      };

    case 'markSaved':
      return {
        ...state,
        tabs: state.tabs.map((tab) => (tab.id === action.id ? { ...tab, dirty: false } : tab)),
      };

    case 'rename':
      return {
        ...state,
        tabs: state.tabs.map((tab) =>
          tab.id === action.id ? { ...tab, title: action.title } : tab,
        ),
      };

    case 'reorder': {
      const tabs = [...state.tabs];
      const [moved] = tabs.splice(action.from, 1);
      if (!moved) return state;
      tabs.splice(action.to, 0, moved);
      return { ...state, tabs };
    }

    default:
      return state;
  }
}

export function activeTab(state: TabsState): EditorTab | null {
  return state.tabs.find((tab) => tab.id === state.activeTabId) ?? null;
}

export interface TabsContextValue {
  state: TabsState;
  dispatch: (action: TabsAction) => void;
}

export const TabsContext = createContext<TabsContextValue | null>(null);

export function useTabs(): TabsContextValue {
  const context = useContext(TabsContext);
  if (!context) throw new Error('useTabs must be used inside <WorkspaceProvider>');
  return context;
}
