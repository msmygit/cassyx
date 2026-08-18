/**
 * The multi-step "Save & connect" flow (plan §3, §3.1).
 *
 * Saving a connection is not one request. Depending on the SCB acquisition mode it is up to three,
 * and the order is forced by the contract: the bundle endpoints are keyed by `connectionId`, so the
 * connection must exist before a bundle can be attached to it.
 *
 *   1. create (or update) the connection            → we now have an id
 *   2. UPLOAD mode: multipart-upload the .zip       → stored encrypted against that id
 *      AUTO_DOWNLOAD mode: server-side download     → resolved, validated and stored, metadata only
 *      PATH mode: nothing — the path is in the connection itself, resolved on the server
 *   3. optionally open the session
 *
 * Pure orchestration, injectable transports: this file is where the ordering bug would live, so it
 * is unit-testable without a server.
 */
import { downloadAstraBundle } from '../api/endpoints';
import {
  connectConnection,
  createConnection,
  updateConnection,
  uploadSecureConnectBundle,
  type ConnectionResponse,
  type SessionState,
} from './connectionsApi';
import { toConnectionRequest, type ConnectionFormState } from './connectionModel';

export interface SaveConnectionTransport {
  create: typeof createConnection;
  update: typeof updateConnection;
  uploadBundle: typeof uploadSecureConnectBundle;
  downloadBundle: typeof downloadAstraBundle;
  connect: typeof connectConnection;
}

export const defaultSaveTransport: SaveConnectionTransport = {
  create: createConnection,
  update: updateConnection,
  uploadBundle: uploadSecureConnectBundle,
  downloadBundle: downloadAstraBundle,
  connect: connectConnection,
};

export interface SaveConnectionOptions {
  form: ConnectionFormState;
  /** Present when editing; absent when creating. */
  connectionId?: string;
  /** The chosen `.zip` for `UPLOAD` mode. */
  bundleFile?: File | null;
  /** Open a session once everything is stored. */
  connect?: boolean;
  transport?: SaveConnectionTransport;
}

export interface SaveConnectionResult {
  connection: ConnectionResponse;
  session?: SessionState;
  /** True when a bundle was uploaded or downloaded as part of this save. */
  bundleStored: boolean;
}

export async function saveConnection({
  form,
  connectionId,
  bundleFile,
  connect = true,
  transport = defaultSaveTransport,
}: SaveConnectionOptions): Promise<SaveConnectionResult> {
  const request = toConnectionRequest(form);

  const connection = connectionId
    ? await transport.update(connectionId, request)
    : await transport.create(request);

  let bundleStored = false;
  if (form.mode === 'ASTRA') {
    if (form.astra.acquisitionMode === 'UPLOAD' && bundleFile) {
      await transport.uploadBundle(connection.id, bundleFile);
      bundleStored = true;
    } else if (form.astra.acquisitionMode === 'AUTO_DOWNLOAD' && form.astra.databaseId) {
      await transport.downloadBundle(form.astra.databaseId, {
        connectionId: connection.id,
        astraToken: form.astra.astraToken,
        region: form.astra.region || undefined,
        scbType: form.astra.scbType,
        domain: form.astra.scbType === 'custom' ? form.astra.customDomain : undefined,
        force: false,
      });
      bundleStored = true;
    }
  }

  if (!connect) {
    return { connection, bundleStored };
  }

  const session = await transport.connect(connection.id);
  return { connection, session, bundleStored };
}
