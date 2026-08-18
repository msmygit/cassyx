import type { RouteObject } from 'react-router';
import { AppShell } from '../layout/AppShell';
import { JobsPanel } from '../panels/JobsPanel';
import { VectorPanel } from '../panels/VectorPanel';
import { WorkspacePage } from './WorkspacePage';
import { NotFoundPage } from './NotFoundPage';

/** The route table, shared by the browser router and by tests. */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <WorkspacePage /> },
      { path: 'jobs', element: <JobsPanel /> },
      { path: 'vector', element: <VectorPanel /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
