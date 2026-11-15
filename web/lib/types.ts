export type TaskStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'POSTPONED' | 'CANCELLED';
export type Priority = 'REQUIRED' | 'HIGH' | 'MEDIUM' | 'LOW';
export type Category = 'COURSE' | 'EXTRACURRICULAR' | 'OFFICE' | 'LEISURE' | 'UNSPECIFIED';
export type FixedKind = 'CLASS' | 'FIXED' | 'REST';

export interface Task {
  id: string;
  user_id: string;
  name: string;
  note: string;
  category: Category;
  priority: Priority;
  duration_minutes: number | null;
  due_date: string | null;
  planned_date: string | null;
  status: TaskStatus;
  progress: number | null;
  actual_minutes: number | null;
  postpone_count: number;
  created_at: string;
  updated_at: string;
  completed_at: string | null;
}

export interface Segment {
  id: string;
  user_id: string;
  task_id: string;
  on_date: string;
  date: string;
  start_minute: number;
  end_minute: number;
  track_id: string;
  locked: boolean;
  from_user: boolean;
  nested_under_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface FixedBlock {
  id: string;
  user_id: string;
  title: string;
  kind: FixedKind;
  weekday: number | null;
  specific_date: string | null;
  date: string | null;
  start_minute: number;
  end_minute: number;
  created_at: string;
}

export interface FocusSession {
  id: string;
  user_id: string;
  task_id: string;
  started_at: string;
  ended_at: string | null;
  net_minutes: number | null;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  created_at: string;
}

export interface Placement {
  id?: string;
  date: string;
  startMinute: number;
  endMinute: number;
  trackId?: string;
  nestedUnderId?: string | null;
  nestedUnderTitle?: string | null;
  fromUser?: boolean;
}

export interface ProposalItem {
  itemId: string;
  kind: 'create' | 'schedule' | 'status' | 'update' | 'routine';
  action: 'create' | 'schedule' | 'complete' | 'progress' | 'cancel' | 'postpone' | 'update' | 'routine';
  taskId: string | null;
  name: string;
  note?: string;
  durationMinutes?: number;
  priority?: Priority;
  category?: Category;
  dueDate?: string | null;
  plannedDate?: string | null;
  wantsSchedule?: boolean;
  date?: string | null;
  startMinute?: number | null;
  endMinute?: number | null;
  weekdays?: number[];
  insertWeekdays?: number[];
  remove?: boolean;
  replace?: boolean;
  /** True when the slot was chosen from the free windows instead of the user's clock. */
  autoPicked?: boolean;
  blockKind?: FixedKind;
  blockIds?: string[];
  placement?: Placement | null;
  progress?: number | null;
  existingTask?: boolean;
  move?: boolean;
  replaceSegmentIds?: string[];
  moveFrom?: { date: string; startMinute: number; endMinute: number; count: number } | null;
}

export interface UnscheduledItem {
  taskId: string;
  taskName: string;
  remainingMinutes: number;
  reason: string;
}

export interface ProposalBody {
  intent: string;
  source: 'ai' | 'local' | string;
  reply: string;
  summary: string;
  items: ProposalItem[];
  unplaced: Array<{ itemId: string; name: string; reason: string }>;
  activeDate: string;
  adjustedPinned: boolean;
  /** 用户点名的钟点今天已经过去，落点被挪到最近空档。 */
  pinnedPast?: boolean;
}

export interface Proposal extends ProposalBody {
  id: string;
  created_at?: string;
}

export interface PendingProposal {
  id: string;
  payload: ProposalBody;
  created_at: string;
}

export interface AppState {
  today: string;
  nowMinute: number;
  timezone: string;
  profile: { id: string; name: string };
  tasks: Task[];
  segments: Segment[];
  fixedBlocks: FixedBlock[];
  chat: ChatMessage[];
  pendingProposal: PendingProposal | null;
  lastOperation: { id: string; kind: string; label: string; created_at: string } | null;
  focusSessions: FocusSession[];
  ai: { configured: boolean; usedToday: number; dailyLimit: number };
}

export interface PlanResult {
  created: number;
  deleted: number;
  unscheduled: UnscheduledItem[];
  message: string;
  operationId?: string;
}

export interface StateWithPlan extends AppState {
  planResult?: PlanResult;
}

export interface StateWithExecution extends AppState {
  executed?: { operationId: string; reply: string };
  undoLabel?: string;
  placed?: {
    date: string;
    startMinute: number;
    endMinute: number;
    exact: boolean;
    /** 点名时间没排上时的原因：past=已经过去，occupied=被别的安排占了。 */
    adjusted?: 'past' | 'occupied' | null;
  };
}

export interface AssistantReply {
  reply: string;
  source: 'ai' | 'local' | 'local-query' | 'clarify' | 'error';
  proposal: Proposal | null;
}

export interface SummaryFacts {
  created: number;
  completed: number;
  active: number;
  overdue: number;
  focusMinutes: number;
  segments: number;
  range: { from: string; to: string };
}

export interface Summary {
  text: string;
  facts: SummaryFacts;
}

export type Perform = <T>(fn: () => Promise<T>, options?: { apply?: boolean }) => Promise<T | null>;

export interface TimetableEntry {
  id?: string;
  title: string;
  weekday: number;
  startMinute: number;
  endMinute: number;
  kind?: 'CLASS' | 'FIXED';
}

export interface TeamMemberInfo {
  id: string;
  name: string;
  role: string;
  bound: boolean;
  classHours: number;
  timetable: TimetableEntry[];
}

export interface BusyEntry {
  startMinute: number;
  endMinute: number;
  title: string;
  kind: 'class' | 'meeting';
}

export interface AvailabilityDay {
  date: string;
  weekdayLabel: string;
  monthDay: string;
  windows: Array<{ startMinute: number; endMinute: number }>;
  members: Array<{ id: string; busy: BusyEntry[] }>;
}

export interface MeetingSuggestion {
  date: string;
  weekdayLabel: string;
  startMinute: number;
  endMinute: number;
  freeMinutes: number;
  score: number;
}

export interface TeamMeeting {
  id: string;
  title: string;
  date: string;
  startMinute: number;
  endMinute: number;
  memberIds: string[];
  memberNames: string[];
  note: string;
  createdAt: string;
}

export interface TeamSchedule {
  teamName: string;
  today: string;
  duration: number;
  members: TeamMemberInfo[];
  days: AvailabilityDay[];
  suggestions: MeetingSuggestion[];
  meetings: TeamMeeting[];
}

export type Tab = 'schedule' | 'tasks' | 'team' | 'assistant' | 'profile';

export interface TaskInput {
  name: string;
  note?: string;
  durationMinutes?: number | null;
  priority?: Priority;
  category?: Category;
  dueDate?: string | null;
  plannedDate?: string | null;
  date?: string;
  start?: string | null;
  schedule?: boolean;
}
