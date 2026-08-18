import Box from '@mui/material/Box';
import { DataGrid } from '../panels/DataGrid';
import { QueryEditor } from '../panels/QueryEditor';
import { activeTab, useTabs } from '../layout/tabsModel';
import { TabBar } from '../layout/TabBar';

/**
 * Main work area: the tab strip over a split editor / results view (plan §5.1, §7, §8).
 */
export function WorkspacePage() {
  const { state, dispatch } = useTabs();
  const current = activeTab(state);

  return (
    <Box sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <TabBar
        tabs={state.tabs}
        activeTabId={state.activeTabId}
        onActivate={(id) => dispatch({ type: 'activate', id })}
        onClose={(id) => dispatch({ type: 'close', id })}
        onNew={() =>
          dispatch({
            type: 'open',
            tab: { kind: 'query', title: `Query ${state.sequence + 1}`, content: '' },
          })
        }
      />

      {current ? (
        <Box sx={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
          <Box
            sx={{ flex: '1 1 45%', minHeight: 0, borderBottom: 1, borderColor: 'chrome.border' }}
          >
            <QueryEditor
              value={current.content}
              onChange={(content) => dispatch({ type: 'update', id: current.id, content })}
            />
          </Box>
          <Box sx={{ flex: '1 1 55%', minHeight: 0 }}>
            <DataGrid columns={[]} rows={[]} />
          </Box>
        </Box>
      ) : (
        <Box sx={{ flex: 1, display: 'grid', placeItems: 'center' }}>
          <Box sx={{ color: 'text.secondary' }}>No open tabs — press + to start a query.</Box>
        </Box>
      )}
    </Box>
  );
}
