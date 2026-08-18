import { useState } from 'react';
import Box from '@mui/material/Box';
import IconButton from '@mui/material/IconButton';
import LinearProgress from '@mui/material/LinearProgress';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import Tooltip from '@mui/material/Tooltip';
import Typography from '@mui/material/Typography';
import AddRoundedIcon from '@mui/icons-material/AddRounded';
import RefreshRoundedIcon from '@mui/icons-material/RefreshRounded';
import { useNavigate } from 'react-router';
import { DdlEditorDialog } from '../ddl/DdlEditorDialog';
import { availableObjectTypes, type DdlTarget } from '../ddl/ddlModel';
import { SchemaTree, type SchemaTreeDdlAction } from '../schema/SchemaTree';
import type { SchemaNode } from '../schema/model';
import type { DdlAction, DdlObjectType } from '../schema/schemaTypes';
import type { SchemaNodeKind } from '../api/types';
import { useWorkspace } from './workspaceContext';

export interface SchemaSidebarProps {
  onOpenInEditor: (node: SchemaNode) => void;
}

/** Tree node kinds map 1:1 onto DDL object types, except that a view is a materialized view. */
const OBJECT_TYPE_FOR_KIND: Record<SchemaNodeKind, DdlObjectType> = {
  KEYSPACE: 'KEYSPACE',
  TABLE: 'TABLE',
  VIEW: 'MATERIALIZED_VIEW',
  INDEX: 'INDEX',
  TYPE: 'TYPE',
  FUNCTION: 'FUNCTION',
  AGGREGATE: 'AGGREGATE',
  COLUMN: 'COLUMN',
  ROLE: 'ROLE',
};

interface DdlRequest {
  objectType: DdlObjectType;
  action: DdlAction;
  target: DdlTarget;
}

/**
 * The schema sidebar: header actions (New object, Refresh), the tree, and the DDL editor host.
 *
 * The "New object" menu is built from `availableObjectTypes(capabilities)`, so a cluster without
 * materialized views or UDFs simply does not list them (plan §7.1) — and the context menu's
 * Alter / Drop / Truncate entries carry the clicked node's OWN identity into the editor.
 */
export function SchemaSidebar({ onOpenInEditor }: SchemaSidebarProps) {
  const workspace = useWorkspace();
  const navigate = useNavigate();
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);
  const [ddl, setDdl] = useState<DdlRequest | null>(null);

  const objectTypes = availableObjectTypes(workspace.capabilityNames);
  const connectionId = workspace.activeConnectionId;

  const openDdl = (request: DdlRequest) => {
    setMenuAnchor(null);
    setDdl(request);
  };

  const handleTreeDdl = (action: SchemaTreeDdlAction, node: SchemaNode) => {
    openDdl({
      objectType: OBJECT_TYPE_FOR_KIND[node.kind] ?? 'TABLE',
      action,
      // Straight from the node's own identity — never from the selection or the parent row.
      target: {
        ...(node.identity.keyspace ? { keyspace: node.identity.keyspace } : {}),
        ...(node.identity.table ? { table: node.identity.table } : {}),
      },
    });
  };

  return (
    <>
      <Box
        sx={{
          px: 1.5,
          py: 0.75,
          borderBottom: 1,
          borderColor: 'chrome.border',
          display: 'flex',
          alignItems: 'center',
          gap: 0.5,
        }}
      >
        <Typography variant="caption" sx={{ fontWeight: 700, letterSpacing: '0.06em' }}>
          SCHEMA
        </Typography>
        <Box sx={{ flex: 1 }} />

        <Tooltip
          title={
            connectionId
              ? 'New object — keyspace, table, index, view, type, function, role…'
              : 'Connect to a cluster to create objects'
          }
        >
          <span>
            <IconButton
              size="small"
              aria-label="New object"
              data-testid="new-object"
              disabled={!connectionId}
              onClick={(event) => setMenuAnchor(event.currentTarget)}
            >
              <AddRoundedIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>

        <Tooltip
          title={connectionId ? 'Refresh schema' : 'Connect to a cluster to load the schema'}
        >
          <span>
            <IconButton
              size="small"
              aria-label="Refresh schema"
              data-testid="refresh-schema"
              disabled={!connectionId}
              onClick={workspace.refreshSchema}
            >
              <RefreshRoundedIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
      </Box>

      <Menu
        anchorEl={menuAnchor}
        open={menuAnchor !== null}
        onClose={() => setMenuAnchor(null)}
        data-testid="new-object-menu"
      >
        {objectTypes.map((spec) => (
          <MenuItem
            key={spec.objectType}
            data-testid={`new-object-${spec.objectType}`}
            onClick={() =>
              openDdl({
                objectType: spec.objectType,
                action: spec.actions[0] ?? 'CREATE',
                target: spec.clusterScoped
                  ? {}
                  : {
                      ...(workspace.selectedTable?.keyspace
                        ? { keyspace: workspace.selectedTable.keyspace }
                        : {}),
                      ...(spec.tableScoped && workspace.selectedTable?.table
                        ? { table: workspace.selectedTable.table }
                        : {}),
                    },
              })
            }
          >
            {spec.label}
          </MenuItem>
        ))}
      </Menu>

      {workspace.schemaLoading && <LinearProgress data-testid="schema-loading" />}

      <SchemaTree
        nodes={workspace.schema}
        selectedId={workspace.selectedNodeId}
        showSystem={workspace.showSystem}
        onShowSystemChange={workspace.setShowSystem}
        onSelect={(node) => workspace.setSelectedNodeId(node.id)}
        onOpenInEditor={onOpenInEditor}
        {...(connectionId ? { onDdlAction: handleTreeDdl } : {})}
        extraMenuItems={(node, close) =>
          node.kind === 'TABLE' && node.identity.table ? (
            <MenuItem
              data-testid="context-menu-load"
              onClick={() => {
                close();
                void navigate(
                  `/jobs/load?keyspace=${encodeURIComponent(
                    node.identity.keyspace,
                  )}&table=${encodeURIComponent(node.identity.table as string)}`,
                );
              }}
            >
              Load data into…
            </MenuItem>
          ) : null
        }
      />

      {ddl && connectionId && (
        <DdlEditorDialog
          open
          onClose={() => setDdl(null)}
          connectionId={connectionId}
          objectType={ddl.objectType}
          action={ddl.action}
          target={ddl.target}
          {...(workspace.capabilityNames ? { capabilities: workspace.capabilityNames } : {})}
          onExecuted={() => {
            workspace.refreshSchema();
            setDdl(null);
          }}
        />
      )}
    </>
  );
}
