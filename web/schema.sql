-- Repland web: personal planning data, isolated per application profile.
-- Dates are stored as text (YYYY-MM-DD); timestamps are timestamptz.

CREATE TABLE app.profiles (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  name_key text NOT NULL UNIQUE,
  device_hash text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  token_hash text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL
);

CREATE TABLE app.tasks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  name text NOT NULL,
  note text NOT NULL DEFAULT '',
  category text NOT NULL DEFAULT 'UNSPECIFIED',
  priority text NOT NULL DEFAULT 'MEDIUM',
  duration_minutes integer,
  due_date text,
  planned_date text,
  status text NOT NULL DEFAULT 'NOT_STARTED',
  progress integer,
  actual_minutes integer,
  postpone_count integer NOT NULL DEFAULT 0,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz
);

CREATE TABLE app.segments (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  task_id uuid NOT NULL,
  on_date text NOT NULL,
  start_minute integer NOT NULL,
  end_minute integer NOT NULL,
  track_id text NOT NULL DEFAULT 'focus',
  locked boolean NOT NULL DEFAULT false,
  from_user boolean NOT NULL DEFAULT false,
  nested_under_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.fixed_blocks (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  title text NOT NULL,
  kind text NOT NULL DEFAULT 'FIXED',
  weekday integer,
  specific_date text,
  start_minute integer NOT NULL,
  end_minute integer NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.proposals (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  payload jsonb NOT NULL,
  status text NOT NULL DEFAULT 'pending',
  operation_id uuid,
  created_at timestamptz NOT NULL DEFAULT now(),
  consumed_at timestamptz
);

CREATE TABLE app.operations (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  kind text NOT NULL,
  label text NOT NULL DEFAULT '',
  before jsonb NOT NULL,
  after jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  undone_at timestamptz
);

CREATE TABLE app.focus_sessions (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  task_id uuid NOT NULL,
  started_at timestamptz NOT NULL DEFAULT now(),
  ended_at timestamptz,
  net_minutes integer
);

CREATE TABLE app.chat_messages (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  role text NOT NULL,
  content text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.ai_usage (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- Shared team workspace. The document is the single source of truth for the
-- web app, the Android app and the QQ bot; keep the shape in functions/team.mjs.
CREATE TABLE app.team_state (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  team_key text NOT NULL UNIQUE,
  doc jsonb NOT NULL,
  version integer NOT NULL DEFAULT 1,
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- Public image host for chat rich-media: QQ fetches the PNG by URL, so rows here
-- must be readable without the team code.
CREATE TABLE app.team_media (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  team_key text NOT NULL,
  kind text NOT NULL DEFAULT 'image',
  mime text NOT NULL DEFAULT 'image/png',
  data text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX tasks_user_created_idx ON app.tasks (user_id, created_at);
CREATE INDEX tasks_user_status_idx ON app.tasks (user_id, status);
CREATE INDEX segments_user_date_idx ON app.segments (user_id, on_date);
CREATE INDEX fixed_blocks_user_idx ON app.fixed_blocks (user_id);
CREATE INDEX sessions_token_idx ON app.sessions (token_hash);
CREATE INDEX proposals_user_status_idx ON app.proposals (user_id, status);
CREATE INDEX operations_user_created_idx ON app.operations (user_id, created_at);
CREATE INDEX chat_user_created_idx ON app.chat_messages (user_id, created_at);
CREATE INDEX focus_user_started_idx ON app.focus_sessions (user_id, started_at);
CREATE INDEX ai_usage_user_created_idx ON app.ai_usage (user_id, created_at);
