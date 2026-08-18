import { useState } from 'react';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { routes } from './routes';

/**
 * The data router is created lazily inside the component, so merely importing the route table
 * (as tests and tooling do) never touches browser history.
 */
export function AppRouter() {
  const [router] = useState(() => createBrowserRouter(routes));
  return <RouterProvider router={router} />;
}
