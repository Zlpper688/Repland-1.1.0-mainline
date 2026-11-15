import { createDatabaseClient, createSiteContext, errorResponse } from './_qoder/backend.mjs';
import { application } from './routes.mjs';

// Identity is application-owned: friends sign in with the shared experience
// code, and every business query is scoped to the session's profile id.
const handle = application({ db: createDatabaseClient() });

Deno.serve(async (request) => {
  let context;
  try {
    context = createSiteContext(request);
    return await handle(context);
  } catch (error) {
    return errorResponse(error, context?.requestId);
  }
});
