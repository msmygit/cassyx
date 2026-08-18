import { describe, expect, it } from 'vitest';
import { activeTab, initialTabsState, tabsReducer, type TabsState } from './tabsModel';

function open(state: TabsState, title: string): TabsState {
  return tabsReducer(state, { type: 'open', tab: { kind: 'query', title, content: '' } });
}

describe('tabsReducer', () => {
  it('starts with one active query tab', () => {
    const state = initialTabsState();
    expect(state.tabs).toHaveLength(1);
    expect(activeTab(state)?.title).toBe('Query 1');
  });

  it('opens and activates new tabs with unique ids', () => {
    let state = initialTabsState();
    state = open(state, 'Query 2');
    state = open(state, 'Query 3');
    expect(state.tabs.map((t) => t.id)).toEqual(['tab-1', 'tab-2', 'tab-3']);
    expect(state.activeTabId).toBe('tab-3');
  });

  it('de-duplicates schema-opened tabs on the FULL identity, not the bare table name', () => {
    let state = initialTabsState();
    state = tabsReducer(state, {
      type: 'openFromSchema',
      identity: { keyspace: 'demo', table: 'users' },
      title: 'demo.users',
      content: 'SELECT * FROM demo.users;',
    });
    const demoTabId = state.activeTabId;

    // Same table name, different keyspace → a SEPARATE tab.
    state = tabsReducer(state, {
      type: 'openFromSchema',
      identity: { keyspace: 'system_auth', table: 'users' },
      title: 'system_auth.users',
      content: 'SELECT * FROM system_auth.users;',
    });
    expect(state.tabs).toHaveLength(3);
    expect(state.activeTabId).not.toBe(demoTabId);

    // Re-opening demo.users focuses the existing tab instead of duplicating it.
    state = tabsReducer(state, {
      type: 'openFromSchema',
      identity: { keyspace: 'demo', table: 'users' },
      title: 'demo.users',
      content: 'SELECT * FROM demo.users;',
    });
    expect(state.tabs).toHaveLength(3);
    expect(state.activeTabId).toBe(demoTabId);
  });

  it('activates the right-hand neighbour when the active tab is closed', () => {
    let state = initialTabsState();
    state = open(state, 'Query 2');
    state = open(state, 'Query 3');
    state = tabsReducer(state, { type: 'activate', id: 'tab-2' });
    state = tabsReducer(state, { type: 'close', id: 'tab-2' });
    expect(state.activeTabId).toBe('tab-3');
  });

  it('falls back to the left when the last tab is closed', () => {
    let state = initialTabsState();
    state = open(state, 'Query 2');
    state = tabsReducer(state, { type: 'close', id: 'tab-2' });
    expect(state.activeTabId).toBe('tab-1');
  });

  it('leaves the active tab alone when a different tab is closed', () => {
    let state = initialTabsState();
    state = open(state, 'Query 2');
    state = tabsReducer(state, { type: 'close', id: 'tab-1' });
    expect(state.activeTabId).toBe('tab-2');
  });

  it('handles closing the only tab', () => {
    const state = tabsReducer(initialTabsState(), { type: 'close', id: 'tab-1' });
    expect(state.tabs).toHaveLength(0);
    expect(state.activeTabId).toBeNull();
    expect(activeTab(state)).toBeNull();
  });

  it('ignores closing and activating unknown ids', () => {
    const state = initialTabsState();
    expect(tabsReducer(state, { type: 'close', id: 'nope' })).toBe(state);
    expect(tabsReducer(state, { type: 'activate', id: 'nope' })).toBe(state);
  });

  it('tracks dirty state on edit and clears it on save', () => {
    let state = tabsReducer(initialTabsState(), {
      type: 'update',
      id: 'tab-1',
      content: 'SELECT 1;',
    });
    expect(activeTab(state)?.dirty).toBe(true);
    expect(activeTab(state)?.content).toBe('SELECT 1;');

    state = tabsReducer(state, { type: 'markSaved', id: 'tab-1' });
    expect(activeTab(state)?.dirty).toBe(false);
  });

  it('renames, reorders and closes-others', () => {
    let state = initialTabsState();
    state = open(state, 'Query 2');
    state = tabsReducer(state, { type: 'rename', id: 'tab-2', title: 'Renamed' });
    expect(state.tabs[1]?.title).toBe('Renamed');

    state = tabsReducer(state, { type: 'reorder', from: 1, to: 0 });
    expect(state.tabs.map((t) => t.id)).toEqual(['tab-2', 'tab-1']);

    state = tabsReducer(state, { type: 'closeOthers', id: 'tab-1' });
    expect(state.tabs.map((t) => t.id)).toEqual(['tab-1']);
    expect(state.activeTabId).toBe('tab-1');
  });

  it('ignores closeOthers for an unknown id', () => {
    const state = initialTabsState();
    expect(tabsReducer(state, { type: 'closeOthers', id: 'nope' })).toBe(state);
  });
});
