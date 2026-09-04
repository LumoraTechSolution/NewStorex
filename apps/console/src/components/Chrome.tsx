'use client';

import { ThemeToggle } from '@/components/ThemeToggle';

/**
 * The page frame every console screen sits in.
 *
 * <h2>Three shapes, not one scaled up</h2>
 *
 * The console was phone-only, and a phone layout stretched to a 27" monitor is not a desktop app —
 * it is a phone app with a lot of wasted glass, and a column of text 1600px wide is genuinely
 * harder to read than one 400px wide. So the container widens in deliberate steps and the content
 * inside it re-flows to match:
 *
 * <ul>
 *   <li><b>Phone</b> (base) — one column, {@code max-w-md}. Unchanged; it was always right.</li>
 *   <li><b>Tablet</b> ({@code md}, 768px) — wider measure, cards in two columns.</li>
 *   <li><b>Desktop</b> ({@code lg}, 1024px) — the nav leaves the top of the content and becomes a
 *       sidebar (see {@code AppShell}), and the content area uses the width it has been given.</li>
 * </ul>
 *
 * <p>Every breakpoint is a CSS one. Nothing here measures the window in JavaScript: a layout that
 * depends on {@code window.innerWidth} renders the wrong shape on the server, then corrects itself
 * after hydration, which is a visible jump on every load.
 */
export function Screen({ children }: { children: React.ReactNode }) {
  return (
    <main className="mx-auto flex w-full max-w-md flex-col gap-4 p-4 pb-24 md:max-w-3xl md:gap-5 md:p-6 lg:max-w-6xl lg:pb-10">
      {children}
    </main>
  );
}

/**
 * Cards side by side once there is room for them.
 *
 * <p>{@code items-start} is load-bearing: without it, grid stretches every card in a row to the
 * height of the tallest, so a three-line card beside a twenty-row list becomes a mostly-empty box.
 *
 * <p>Anything that needs the full width — a headline figure, a chart — simply sits outside a grid
 * rather than inside one with a span. Two columns is the only arrangement this console needs, and a
 * span prop that one caller uses is a prop that gets used wrongly by the next.
 */
export function CardGrid({ children }: { children: React.ReactNode }) {
  return (
    <div className="grid items-start gap-4 md:grid-cols-2 md:gap-5 lg:grid-cols-2">{children}</div>
  );
}

export function Card({
  title,
  aside,
  children,
  footer,
}: {
  title?: string;
  /**
   * A quieter fact belonging to the title — the sync time, the range a list covers.
   *
   * <p>It sits on the title's line rather than under the figure because it qualifies the whole
   * card. The sync time in particular is the only thing on the Today screen that tells an owner
   * whether to believe the number beneath it, and buried under that number is where it stops
   * being read.
   */
  aside?: React.ReactNode;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  return (
    <section className="bg-surface border-hair flex flex-col gap-3 rounded-xl border p-4 shadow-[0_1px_2px_rgba(20,27,24,0.05),0_8px_24px_-16px_rgba(20,27,24,0.25)] md:p-5">
      {(title || aside) && (
        <div className="flex items-baseline justify-between gap-3">
          {title && (
            <h2 className="text-ink-3 text-[11px] font-semibold uppercase tracking-[0.09em]">
              {title}
            </h2>
          )}
          {aside && <span className="text-ink-3 text-[11px] tracking-wide">{aside}</span>}
        </div>
      )}
      {children}
      {footer && <div className="text-ink-3 text-xs leading-relaxed">{footer}</div>}
    </section>
  );
}

/**
 * A word beside a number — "Short", "Low", "Not received".
 *
 * <p>§A in one component: status colour never carries meaning on its own, so the chip is always a
 * label and the colour only ever agrees with it. Outlined rather than filled because a filled chip
 * at these sizes needs an ink token per colour to stay legible in both themes, and four of them
 * would be four more chances to fail contrast silently.
 */
export function Chip({
  tone,
  children,
}: {
  tone: 'danger' | 'pending';
  children: React.ReactNode;
}) {
  return (
    <span
      className={`rounded-full border border-current px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide ${
        tone === 'danger' ? 'text-danger' : 'text-pending'
      }`}
    >
      {children}
    </span>
  );
}

/**
 * A row that is a whole tap target.
 *
 * <p>56px minimum, the same as the till's. §A justifies that for gloved fingers on a shop floor;
 * on a phone the reason is different and the number is the same — this is read one-handed, often
 * while walking.
 *
 * <p>It stays 56px on desktop too, where a mouse would be happy with less. A list that changes row
 * height between a laptop and the tablet beside it looks like two different products, and nothing
 * is gained by the denser version except fitting one more row above the fold.
 */
export function Row({ children }: { children: React.ReactNode }) {
  return (
    <div className="border-hair flex min-h-[56px] items-center justify-between gap-3 border-b py-2 last:border-b-0">
      {children}
    </div>
  );
}

/**
 * The one number a screen is about.
 *
 * <p>Scales with the viewport because a 36px figure that anchors a phone screen is lost in the
 * middle of a desktop one — the type has to grow with the canvas or the hierarchy inverts.
 *
 * <p>Set in the display serif (M6-14). A serif on a dashboard is a deliberate risk: this figure is
 * what somebody opened the app for, and it should read as something written in a ledger rather than
 * emitted by a reporting tool. Tabular figures regardless, because a number that jitters as it
 * updates is a number people stop trusting.
 */
export function Headline({ children }: { children: React.ReactNode }) {
  return (
    <p className="font-display text-4xl font-semibold tabular-nums leading-none tracking-tight md:text-5xl lg:text-6xl">
      {children}
    </p>
  );
}

export function Empty({ children }: { children: React.ReactNode }) {
  return <p className="text-ink-3 py-6 text-center text-sm">{children}</p>;
}

export function ErrorNote({ children }: { children: React.ReactNode }) {
  // Icon plus text, never colour alone — §A. Red on its own is invisible to a chunk of any
  // shopkeeper population and identical to "just a number" in bright sun.
  return (
    <p className="text-danger flex items-start gap-2 text-sm" role="alert">
      <span aria-hidden="true">⚠</span>
      <span>{children}</span>
    </p>
  );
}

/**
 * The frame both sign-in screens sit in.
 *
 * <p>Centred and narrow at every size. A login form is the one screen that must <em>not</em> use
 * the width it is given: two fields stretched across a monitor read as a form somebody has broken.
 */
export function LoginFrame({ children }: { children: React.ReactNode }) {
  return (
    <main className="mx-auto flex min-h-screen w-full max-w-md flex-col justify-center gap-6 p-6 md:max-w-sm">
      {children}
      {/*
        Reachable before signing in, and put here so both sign-in screens get it from one place
        (M4-11). It matters most exactly here: somebody checking their takings in bed meets this
        screen first, and being unable to dim it until after they have typed a password is the
        moment the setting would have been worth having.
      */}
      <div className="flex justify-center pt-2">
        <ThemeToggle />
      </div>
    </main>
  );
}
