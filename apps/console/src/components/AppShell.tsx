'use client';

import { ThemeToggle } from '@/components/ThemeToggle';

/**
 * The navigation frame both consoles share — tabs on a phone, a sidebar on a desktop.
 *
 * <h2>Why the nav changes shape and the content does not move</h2>
 *
 * A horizontal tab strip is right on a phone: it sits under the thumb, three items fit, and
 * vertical space is the scarce thing. On a 1440px monitor the same strip is a row of three buttons
 * marooned across the top with the entire left third of the screen empty — and the scarce thing has
 * become vertical space again, but for a different reason: the content is now short and wide
 * instead of long and narrow.
 *
 * <p>So below {@code lg} the nav is a sticky top bar, and at {@code lg} it becomes a fixed sidebar.
 * Both render from the same {@code items} array, so a section added to one appears in the other —
 * the commonest way a responsive nav rots is two copies of the list that drift apart.
 *
 * <h2>Both navs are in the DOM, and that is deliberate</h2>
 *
 * The alternative is measuring the viewport in JavaScript and rendering one. That renders the wrong
 * nav on the server, then swaps it after hydration — a visible jump on every single load, and on a
 * slow phone a long one. Two small elements with {@code lg:hidden} and {@code hidden lg:flex} cost
 * a few hundred bytes and are correct in the first paint.
 *
 * <p>The duplicate does mean the same labels appear twice to a screen reader, so only the visible
 * one is exposed: each is inside a {@code <nav>} whose hidden twin is removed from the tree by
 * {@code display: none}, which assistive technology honours.
 */
export interface NavItem<T extends string> {
  value: T;
  label: string;
  /** A count worth seeing before the section is opened — variances, shops needing attention. */
  badge?: number;
}

export function AppShell<T extends string>({
  brand,
  brandSuffix,
  items,
  active,
  onSelect,
  onSignOut,
  children,
}: {
  brand: string;
  /** A quieter second word — "Estate" — so the two consoles are never mistaken for each other. */
  brandSuffix?: string;
  items: readonly NavItem<T>[];
  active: T;
  onSelect: (value: T) => void;
  onSignOut: () => void;
  children: React.ReactNode;
}) {
  const wordmark = (
    <span className="text-lg font-semibold">
      {brand}
      {brandSuffix && <span className="text-ink-3 font-normal"> {brandSuffix}</span>}
    </span>
  );

  return (
    <div className="lg:flex lg:min-h-screen">
      {/* ---------------------------------------------------------------- desktop: a sidebar */}
      <aside className="border-hair bg-surface hidden lg:sticky lg:top-0 lg:flex lg:h-screen lg:w-64 lg:shrink-0 lg:flex-col lg:border-r">
        <div className="border-hair flex items-center border-b px-5 py-5">{wordmark}</div>

        <nav className="flex flex-1 flex-col gap-1 p-3" aria-label="Sections">
          {items.map((item) => (
            <button
              key={item.value}
              type="button"
              className={`flex min-h-[48px] items-center justify-between rounded px-3 text-left text-sm font-medium ${
                active === item.value
                  ? 'bg-accent text-accent-ink'
                  : 'text-ink-2 hover:bg-page hover:text-ink'
              }`}
              aria-current={active === item.value ? 'page' : undefined}
              onClick={() => onSelect(item.value)}
            >
              <span>{item.label}</span>
              {item.badge !== undefined && item.badge > 0 && (
                <Badge count={item.badge} onAccent={active === item.value} />
              )}
            </button>
          ))}
        </nav>

        <div className="border-hair flex flex-col gap-3 border-t p-3">
          {/* Settled at the bottom with Sign out, not in the nav: it is a preference about this
              device, not a section of the business. */}
          <ThemeToggle className="self-start" />
          <button
            type="button"
            className="text-ink-3 hover:text-ink min-h-[48px] w-full rounded px-3 text-left text-sm"
            onClick={onSignOut}
          >
            Sign out
          </button>
        </div>
      </aside>

      {/* ------------------------------------------------ phone and tablet: a bar and tab strip */}
      <div className="min-w-0 flex-1">
        <header className="border-hair bg-surface sticky top-0 z-10 border-b lg:hidden">
          <div className="mx-auto flex w-full max-w-md items-center justify-between gap-3 p-4 md:max-w-3xl md:px-6">
            {wordmark}
            <div className="flex items-center gap-3">
              <ThemeToggle />
              <button
                type="button"
                className="text-ink-3 min-h-[44px] text-sm underline"
                onClick={onSignOut}
              >
                Sign out
              </button>
            </div>
          </div>

          <nav className="mx-auto flex w-full max-w-md md:max-w-3xl" aria-label="Sections">
            {items.map((item) => (
              <button
                key={item.value}
                type="button"
                className={`min-h-[56px] flex-1 border-b-2 text-sm font-medium ${
                  active === item.value ? 'border-accent text-ink' : 'text-ink-3 border-transparent'
                }`}
                aria-current={active === item.value ? 'page' : undefined}
                onClick={() => onSelect(item.value)}
              >
                {item.label}
                {item.badge !== undefined && item.badge > 0 && (
                  <span className="ml-2 inline-block">
                    <Badge count={item.badge} onAccent={false} />
                  </span>
                )}
              </button>
            ))}
          </nav>
        </header>

        {children}
      </div>
    </div>
  );
}

/**
 * @param onAccent inverts the badge when it sits on the selected sidebar item, where the amber
 *     would otherwise be amber-on-blue and fail contrast in both themes.
 */
function Badge({ count, onAccent }: { count: number; onAccent: boolean }) {
  return (
    <span
      className={`rounded-full px-2 py-0.5 text-xs ${
        onAccent ? 'bg-accent-ink text-accent' : 'bg-pending text-page'
      }`}
    >
      {count}
    </span>
  );
}
