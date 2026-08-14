export default function Page() {
  return (
    <main className="mx-auto flex max-w-md flex-col gap-3 p-6">
      <h1 className="text-2xl font-semibold">StoreX Console</h1>
      <p className="text-ink-2 text-sm">
        Scaffold only. Today&apos;s sales, the trend, the branch view and the attention feed are
        built in M4-05 through M4-07.
      </p>
      <p className="text-ink-3 text-xs">
        This surface is read-only by design: editing the catalog from a phone while the shop PC is
        offline needs bidirectional merge, which is deferred to v3.
      </p>
    </main>
  );
}
