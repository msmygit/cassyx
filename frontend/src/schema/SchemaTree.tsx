import { useMemo, useState, type DragEvent, type MouseEvent } from 'react';
import Box from '@mui/material/Box';
import Checkbox from '@mui/material/Checkbox';
import Chip from '@mui/material/Chip';
import FormControlLabel from '@mui/material/FormControlLabel';
import IconButton from '@mui/material/IconButton';
import InputAdornment from '@mui/material/InputAdornment';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Stack from '@mui/material/Stack';
import TextField from '@mui/material/TextField';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import ChevronRightRoundedIcon from '@mui/icons-material/ChevronRightRounded';
import ExpandMoreRoundedIcon from '@mui/icons-material/ExpandMoreRounded';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import ClearRoundedIcon from '@mui/icons-material/ClearRounded';
import StorageRoundedIcon from '@mui/icons-material/StorageRounded';
import TableChartRoundedIcon from '@mui/icons-material/TableChartRounded';
import LayersRoundedIcon from '@mui/icons-material/LayersRounded';
import ViewColumnRoundedIcon from '@mui/icons-material/ViewColumnRounded';
import KeyRoundedIcon from '@mui/icons-material/KeyRounded';
import type { SchemaNodeKind } from '../api/types';
import {
  collectExpandableIds,
  filterTree,
  qualifiedName,
  SCHEMA_DRAG_MIME,
  statementForNode,
  toDragPayload,
  type SchemaNode,
} from './model';

export interface SchemaTreeProps {
  nodes: SchemaNode[];
  /** Fired with the node's OWN identity — callers must not infer it from anywhere else. */
  onSelect?: (node: SchemaNode) => void;
  onOpenInEditor?: (node: SchemaNode) => void;
  /** Default preview page size used when building drag statements. */
  previewLimit?: number | null;
  selectedId?: string | null;
}

const KIND_ICON: Record<SchemaNodeKind, typeof StorageRoundedIcon> = {
  KEYSPACE: StorageRoundedIcon,
  TABLE: TableChartRoundedIcon,
  VIEW: LayersRoundedIcon,
  INDEX: KeyRoundedIcon,
  TYPE: LayersRoundedIcon,
  FUNCTION: LayersRoundedIcon,
  AGGREGATE: LayersRoundedIcon,
  COLUMN: ViewColumnRoundedIcon,
  ROLE: KeyRoundedIcon,
};

interface ContextMenuState {
  x: number;
  y: number;
  node: SchemaNode;
}

/**
 * Schema browser (plan §4).
 *
 * Two prior-art gaps closed here: a search box, and a "show system keyspaces" toggle.
 * One prior-art *bug* closed here: every interaction — click, double-click, drag, context menu —
 * resolves its target from `node.identity`, which each node carries itself. Nothing in this
 * component ever infers a keyspace from a parent, an index, or an ambient selection.
 */
export function SchemaTree({
  nodes,
  onSelect,
  onOpenInEditor,
  previewLimit = 500,
  selectedId = null,
}: SchemaTreeProps) {
  const [search, setSearch] = useState('');
  const [showSystem, setShowSystem] = useState(false);
  const [manuallyExpanded, setManuallyExpanded] = useState<Set<string>>(new Set());
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);

  const filtered = useMemo(
    () => filterTree(nodes, { search, showSystem }),
    [nodes, search, showSystem],
  );

  const searching = search.trim().length > 0;
  const autoExpanded = useMemo(
    () => (searching ? new Set(collectExpandableIds(filtered)) : null),
    [searching, filtered],
  );

  const isExpanded = (id: string) => autoExpanded?.has(id) ?? manuallyExpanded.has(id);

  const toggleExpanded = (id: string) => {
    setManuallyExpanded((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleContextMenu = (event: MouseEvent, node: SchemaNode) => {
    event.preventDefault();
    // The node is passed by value: the menu acts on THIS node's identity, not on whatever is
    // currently selected elsewhere.
    setContextMenu({ x: event.clientX, y: event.clientY, node });
  };

  const closeMenu = () => setContextMenu(null);

  return (
    <Stack sx={{ height: '100%', minHeight: 0 }} data-testid="schema-tree">
      <Box sx={{ p: 1, borderBottom: 1, borderColor: 'chrome.border' }}>
        <TextField
          fullWidth
          size="small"
          placeholder="Search schema…"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          slotProps={{
            htmlInput: { 'aria-label': 'Search schema', 'data-testid': 'schema-search' },
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchRoundedIcon fontSize="small" />
                </InputAdornment>
              ),
              endAdornment: searching ? (
                <InputAdornment position="end">
                  <IconButton size="small" aria-label="Clear search" onClick={() => setSearch('')}>
                    <ClearRoundedIcon fontSize="small" />
                  </IconButton>
                </InputAdornment>
              ) : undefined,
            },
          }}
        />
        <FormControlLabel
          sx={{ mt: 0.5, ml: 0.25 }}
          control={
            <Checkbox
              size="small"
              checked={showSystem}
              onChange={(event) => setShowSystem(event.target.checked)}
              inputProps={
                {
                  'aria-label': 'Show system keyspaces',
                } as React.InputHTMLAttributes<HTMLInputElement>
              }
            />
          }
          label={
            <Typography variant="caption" color="text.secondary">
              Show system keyspaces
            </Typography>
          }
        />
      </Box>

      <Box sx={{ flex: 1, minHeight: 0, overflow: 'auto', py: 0.5 }} role="tree">
        {filtered.length === 0 ? (
          <Typography variant="caption" color="text.secondary" sx={{ display: 'block', p: 2 }}>
            {searching ? `No schema objects match “${search}”.` : 'No keyspaces to show.'}
          </Typography>
        ) : (
          filtered.map((node) => (
            <TreeRow
              key={node.id}
              node={node}
              depth={0}
              expanded={isExpanded}
              onToggle={toggleExpanded}
              onSelect={onSelect}
              onOpenInEditor={onOpenInEditor}
              onContextMenu={handleContextMenu}
              previewLimit={previewLimit}
              selectedId={selectedId}
            />
          ))
        )}
      </Box>

      <Menu
        open={contextMenu !== null}
        onClose={closeMenu}
        anchorReference="anchorPosition"
        anchorPosition={contextMenu ? { top: contextMenu.y, left: contextMenu.x } : undefined}
      >
        {contextMenu && (
          <Typography
            variant="caption"
            sx={{
              px: 2,
              py: 0.5,
              display: 'block',
              color: 'text.secondary',
              fontFamily: 'monospace',
            }}
            data-testid="context-menu-identity"
          >
            {qualifiedName(contextMenu.node.identity)}
          </Typography>
        )}
        <MenuItem
          onClick={() => {
            if (contextMenu) onOpenInEditor?.(contextMenu.node);
            closeMenu();
          }}
        >
          Open in new editor tab
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (contextMenu) {
              void navigator.clipboard?.writeText(
                statementForNode(contextMenu.node, { limit: previewLimit }),
              );
            }
            closeMenu();
          }}
        >
          Copy statement
        </MenuItem>
        <MenuItem
          onClick={() => {
            if (contextMenu)
              void navigator.clipboard?.writeText(qualifiedName(contextMenu.node.identity));
            closeMenu();
          }}
        >
          Copy qualified name
        </MenuItem>
      </Menu>
    </Stack>
  );
}

interface TreeRowProps {
  node: SchemaNode;
  depth: number;
  expanded: (id: string) => boolean;
  onToggle: (id: string) => void;
  onSelect?: (node: SchemaNode) => void;
  onOpenInEditor?: (node: SchemaNode) => void;
  onContextMenu: (event: MouseEvent, node: SchemaNode) => void;
  previewLimit: number | null;
  selectedId: string | null;
}

function TreeRow({
  node,
  depth,
  expanded,
  onToggle,
  onSelect,
  onOpenInEditor,
  onContextMenu,
  previewLimit,
  selectedId,
}: TreeRowProps) {
  const hasChildren = Boolean(node.children && node.children.length > 0);
  const open = hasChildren && expanded(node.id);
  const Icon = KIND_ICON[node.kind] ?? TableChartRoundedIcon;
  const selected = selectedId === node.id;

  const handleDragStart = (event: DragEvent<HTMLDivElement>) => {
    const payload = toDragPayload(node, { limit: previewLimit });
    event.dataTransfer.setData(SCHEMA_DRAG_MIME, JSON.stringify(payload));
    // Plain-text fallback is the statement built from THIS node's identity.
    event.dataTransfer.setData('text/plain', payload.statement);
    event.dataTransfer.effectAllowed = 'copy';
  };

  return (
    <>
      <Box
        role="treeitem"
        aria-expanded={hasChildren ? open : undefined}
        aria-selected={selected}
        data-testid={`node-${node.id}`}
        data-identity={qualifiedName(node.identity)}
        draggable
        onDragStart={handleDragStart}
        onClick={() => {
          if (hasChildren) onToggle(node.id);
          onSelect?.(node);
        }}
        onDoubleClick={() => onOpenInEditor?.(node)}
        onContextMenu={(event) => onContextMenu(event, node)}
        sx={{
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
          pl: 1 + depth * 1.5,
          pr: 1,
          py: 0.25,
          cursor: 'pointer',
          userSelect: 'none',
          bgcolor: selected ? 'chrome.hover' : 'transparent',
          '&:hover': { bgcolor: 'chrome.hover' },
        }}
      >
        <Box sx={{ width: 18, display: 'flex', alignItems: 'center', color: 'text.secondary' }}>
          {hasChildren &&
            (open ? (
              <ExpandMoreRoundedIcon fontSize="small" />
            ) : (
              <ChevronRightRoundedIcon fontSize="small" />
            ))}
        </Box>
        <Icon sx={{ fontSize: 16, color: colorForKind(node.kind), flexShrink: 0 }} />
        <Typography
          variant="body2"
          sx={{
            fontFamily: node.kind === 'COLUMN' ? 'monospace' : undefined,
            fontWeight: node.kind === 'KEYSPACE' ? 600 : 400,
            color: node.system ? 'cql.system' : 'text.primary',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {node.label}
        </Typography>

        {node.columnKind === 'PARTITION_KEY' && <KindBadge label="PK" title="Partition key" />}
        {node.columnKind === 'CLUSTERING' && <KindBadge label="CK" title="Clustering column" />}
        {node.columnKind === 'STATIC' && <KindBadge label="S" title="Static column" />}
        {node.vectorDimension ? (
          <KindBadge label={`vec ${node.vectorDimension}`} title="Vector column" vector />
        ) : null}

        {node.dataType && !node.vectorDimension && (
          <Typography
            variant="caption"
            sx={{
              ml: 'auto',
              color: 'text.secondary',
              fontFamily: 'monospace',
              fontSize: '0.68rem',
            }}
          >
            {node.dataType}
          </Typography>
        )}
      </Box>

      {open &&
        node.children?.map((child) => (
          <TreeRow
            key={child.id}
            node={child}
            depth={depth + 1}
            expanded={expanded}
            onToggle={onToggle}
            onSelect={onSelect}
            onOpenInEditor={onOpenInEditor}
            onContextMenu={onContextMenu}
            previewLimit={previewLimit}
            selectedId={selectedId}
          />
        ))}
    </>
  );
}

function KindBadge({ label, title, vector }: { label: string; title: string; vector?: boolean }) {
  return (
    <Tooltip title={title}>
      <Chip
        label={label}
        size="small"
        sx={{
          height: 16,
          fontSize: '0.6rem',
          bgcolor: vector ? 'cql.vector' : 'chrome.hover',
          color: vector ? 'background.paper' : 'text.secondary',
          '& .MuiChip-label': { px: 0.6 },
        }}
      />
    </Tooltip>
  );
}

function colorForKind(kind: SchemaNodeKind): string {
  switch (kind) {
    case 'KEYSPACE':
      return 'cql.keyspace';
    case 'VIEW':
      return 'cql.view';
    case 'INDEX':
      return 'cql.index';
    case 'TYPE':
      return 'cql.type';
    case 'COLUMN':
      return 'text.secondary';
    default:
      return 'cql.table';
  }
}
