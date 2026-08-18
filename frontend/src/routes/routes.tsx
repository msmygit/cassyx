import type { RouteObject } from 'react-router';
import { AppShell } from '../layout/AppShell';
import { JobsPage } from './JobsPage';
import { LoadJobPage } from './LoadJobPage';
import { StatisticsPage } from './StatisticsPage';
import { VectorPage } from './VectorPage';
import { WorkspacePage } from './WorkspacePage';
import { NotFoundPage } from './NotFoundPage';

/** The route table, shared by the browser router and by tests. */
export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    children: [
      { index: true, element: <WorkspacePage /> },
      { path: 'jobs', element: <JobsPage /> },
      { path: 'jobs/load', element: <LoadJobPage /> },
      { path: 'statistics', element: <StatisticsPage /> },
      { path: 'vector', element: <VectorPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
