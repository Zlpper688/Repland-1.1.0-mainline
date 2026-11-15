import { useEffect, useRef, useState } from 'react';
import { ArrowUp, Loader2, Sparkles } from 'lucide-react';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
import { Button } from '@/components/ui/button';
import { Textarea } from '@/components/ui/textarea';
import { api } from '@/lib/api';
import { minutesToClock } from '@/lib/date';
import type { AppState, ChatMessage, Perform } from '@/lib/types';
import { toast } from 'sonner';
import { MountainWash } from './MountainWash';
import { ProposalCard } from './ProposalCard';

interface Props {
  state: AppState;
  perform: Perform;
}

const SUGGESTIONS = [
  '明天下午三点复习高数，大概一个半小时',
  '周五之前交实验报告，要两小时',
  '今天还有多少空档？',
  '帮我把这周剩下的时间排一排',
];

export function AssistantView({ state, perform }: Props) {
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [pendingEcho, setPendingEcho] = useState('');
  const [proposalBusy, setProposalBusy] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ChatMessage | null>(null);
  const endRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLTextAreaElement | null>(null);
  const pressTimer = useRef<number | null>(null);
  const pendingProposal = state.pendingProposal;

  useEffect(() => {
    endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, [state.chat.length, pendingProposal?.id, sending]);

  async function send(message?: string) {
    const content = (message ?? text).trim();
    if (!content || sending) return;
    setText('');
    setSending(true);
    setPendingEcho(content);
    await perform(() => api.assistant(content, state.today));
    await perform(() => api.bootstrap());
    setSending(false);
    setPendingEcho('');
  }

  async function confirmProposal() {
    if (!pendingProposal || proposalBusy) return;
    setProposalBusy(true);
    const result = await perform(() => api.confirm(pendingProposal.id));
    setProposalBusy(false);
    if (result) toast.success(result.executed?.reply || '已经按你说的安排好。');
  }

  async function cancelProposal() {
    if (!pendingProposal || proposalBusy) return;
    setProposalBusy(true);
    const result = await perform(() => api.cancelProposal(pendingProposal.id));
    setProposalBusy(false);
    if (result) toast.info('这次先不执行，任务没有改动。');
  }

  function cancelPress() {
    if (pressTimer.current != null) {
      window.clearTimeout(pressTimer.current);
      pressTimer.current = null;
    }
  }

  function startPress(message: ChatMessage) {
    cancelPress();
    pressTimer.current = window.setTimeout(() => {
      pressTimer.current = null;
      setDeleteTarget(message);
    }, 520);
  }

  const pressHandlers = (message: ChatMessage) => ({
    onPointerDown: () => startPress(message),
    onPointerUp: cancelPress,
    onPointerMove: cancelPress,
    onPointerCancel: cancelPress,
    onPointerLeave: cancelPress,
  });

  async function deleteMessage() {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setDeleteTarget(null);
    const result = await perform(() => api.deleteChatMessage(target.id));
    if (result) toast.success('已从对话记录里删除。');
  }

  function handleKeyDown(event: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void send();
    }
  }

  const empty = !state.chat.length && !pendingProposal;
  const pendingReply = pendingProposal?.payload.reply?.trim();

  return (
    <div className="page-gutter flex min-h-[calc(100dvh-9rem)] flex-col pt-4 pb-4">
      <section className="hero-wash relative overflow-hidden rounded-[28px] px-5 pt-4 pb-5">
        <MountainWash className="pointer-events-none absolute -right-8 -bottom-8 h-[110px] w-[104%] opacity-60" />
        <div className="relative flex items-center gap-3">
          <span className="flex size-10 items-center justify-center rounded-2xl bg-[var(--site-surface)]/80 text-primary shadow-sm backdrop-blur">
            <Sparkles className="size-5" />
          </span>
          <div className="min-w-0">
            <h1 className="font-serif text-lg leading-tight font-semibold text-[var(--hero-ink)]">和小满说一句</h1>
            <p className="mt-0.5 text-[11px] text-[var(--ink-soft)]">
              {state.ai.configured
                ? `今天还剩 ${Math.max(0, state.ai.dailyLimit - state.ai.usedToday)} 次 AI 理解`
                : 'AI 未开通 · 本地规则可用'}
            </p>
          </div>
          <span className="ml-auto shrink-0 rounded-full bg-[var(--site-surface)]/75 px-2 py-1 text-[10px] text-[var(--ink-soft)] backdrop-blur tabular-nums">
            现在 {minutesToClock(state.nowMinute)}
          </span>
        </div>
      </section>

      {empty ? (
        <section className="card-paper rise-in mt-4 rounded-[26px] px-5 py-6">
          <p className="font-serif text-lg font-semibold">把要做的事说给我听</p>
          <div className="mt-4 space-y-2">
            {SUGGESTIONS.map((suggestion) => (
              <button
                key={suggestion}
                type="button"
                onClick={() => send(suggestion)}
                disabled={sending}
                className="tap flex w-full items-center gap-2 rounded-2xl border border-[var(--site-border)] bg-[var(--site-bg)]/40 px-3.5 py-2.5 text-left text-xs leading-5 transition hover:border-primary/40 hover:bg-[var(--site-surface)]"
              >
                <Sparkles className="size-3.5 shrink-0 text-primary" />
                {suggestion}
              </button>
            ))}
          </div>
        </section>
      ) : null}

      <div className="mt-4 flex-1 space-y-3">
        {state.chat.map((message) => {
          // The waiting card already carries this line; showing both reads as an echo.
          if (message.role === 'assistant' && pendingReply && message.content.trim() === pendingReply) return null;
          return message.role === 'user' ? (
            <div key={message.id} className="flex justify-end">
              <div
                {...pressHandlers(message)}
                className="max-w-[82%] rounded-[20px] rounded-br-md bg-primary px-3.5 py-2.5 text-sm leading-6 text-primary-foreground transition-transform duration-150 [-webkit-touch-callout:none] active:scale-[0.98]"
              >
                {message.content}
              </div>
            </div>
          ) : (
            <div key={message.id} className="flex items-start gap-2">
              <span className="mt-1 flex size-6 shrink-0 items-center justify-center rounded-full bg-[var(--paper-deep)] text-[10px] font-semibold text-primary">
                满
              </span>
              <div
                {...pressHandlers(message)}
                className="card-paper max-w-[84%] rounded-[20px] rounded-tl-md px-3.5 py-2.5 text-sm leading-6 whitespace-pre-wrap transition-transform duration-150 [-webkit-touch-callout:none] active:scale-[0.98]"
              >
                {message.content}
              </div>
            </div>
          );
        })}

        {state.chat.length ? (
          <p className="pt-0.5 text-center text-[10px] text-[var(--ink-soft)]">长按一条消息可以删除</p>
        ) : null}

        {pendingEcho ? (
          <>
            <div className="flex justify-end">
              <div className="max-w-[82%] rounded-[20px] rounded-br-md bg-primary px-3.5 py-2.5 text-sm leading-6 text-primary-foreground">
                {pendingEcho}
              </div>
            </div>
            <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
              <span className="flex size-6 items-center justify-center rounded-full bg-[var(--paper-deep)] text-primary">
                <Loader2 className="size-3 animate-spin" />
              </span>
              小满正在估时、找空档…
            </div>
          </>
        ) : null}

        {pendingProposal ? (
          <div className="rise-in">
            <ProposalCard
              proposal={pendingProposal.payload}
              today={state.today}
              busy={proposalBusy}
              onConfirm={confirmProposal}
              onCancel={cancelProposal}
              onEdit={() => inputRef.current?.focus()}
            />
          </div>
        ) : null}

        <div ref={endRef} />
      </div>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          void send();
        }}
        className="sticky bottom-0 z-10 -mx-4 mt-4 border-t border-[var(--site-border)] bg-[var(--site-bg)]/95 px-4 py-3 backdrop-blur"
      >
        <div className="flex items-end gap-2">
          <Textarea
            ref={inputRef}
            value={text}
            onChange={(event) => setText(event.target.value)}
            onKeyDown={handleKeyDown}
            rows={1}
            placeholder="明天下午三点复习高数，大概一个半小时"
            className="max-h-28 min-h-11 flex-1 resize-none rounded-2xl border-[var(--site-border)] bg-[var(--site-surface)] px-3.5 py-3 text-sm"
            aria-label="对小满说一句"
          />
          <Button
            type="submit"
            size="icon"
            disabled={sending || !text.trim()}
            className="size-11 shrink-0 rounded-full"
            aria-label="发送"
          >
            {sending ? <Loader2 className="size-4 animate-spin" /> : <ArrowUp className="size-4" />}
          </Button>
        </div>
      </form>

      <AlertDialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除这条对话？</AlertDialogTitle>
            <AlertDialogDescription>
              只从对话记录里删掉这条消息，任务和日程不受影响。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={() => void deleteMessage()}>删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
